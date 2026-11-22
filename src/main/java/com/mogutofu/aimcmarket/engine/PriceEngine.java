package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

import java.util.Map;

/**
 * 价格引擎：每个 market tick 更新商品价与股价。
 *
 * <p>公式（任务书 3.3 / 3.4）：</p>
 * <pre>
 *   商品：P' = P × exp(theta×(ln(base)-ln(P)) + sigma×N(0,1) + impact) × eventMultiplier
 *         结果夹在 [base×priceFloorRatio, base×priceCeilingRatio]，至少 1 分；
 *   股票：P' = P × exp(theta×(ln(initial)-ln(P)) + sigma×N(0,1) + drift + impact) × eventMultiplier
 *         drift = driftBias/每交易日tick数 + (行业景气-1)/每交易日tick数，
 *         结果夹在 [initial×0.10, initial×10]，至少 1 分；最后重算大盘指数。
 * </pre>
 *
 * <p>冲击（impact）只读本 tick 由 {@link TradeService} / FinanceService 记下的净买入额，
 * 由本引擎 {@code takeImpacts()} / {@code takeStockImpacts()} 取走并清空，所以每个 tick 恰好消费一次。
 * 计算中任何 NaN/Inf 一律丢弃（保留原价），绝不写进价格。</p>
 *
 * <p>纯 Java + Gson，不 import 任何 Minecraft 类，单测可以直接造 {@link AimcContext} 跑。</p>
 */
public final class PriceEngine {
    /** 单 tick 冲击绝对上限（商品）：卖出砸盘要比物价系数上涨快得多。 */
    private static final double IMPACT_LIMIT = 0.20;
    /** 单 tick 冲击上限（股票）。 */
    private static final double STOCK_IMPACT_LIMIT = 0.08;
    /** 商品冲击系数：impact = +0.08 × 净买入额 / 有效流动性（买推涨、卖压跌）。 */
    private static final double GOODS_IMPACT_FACTOR = 0.08;
    /** 股票冲击系数：与商品同系数，用流通市值折算的流动性。 */
    private static final double STOCK_IMPACT_FACTOR = 0.02;
    /** 商品价格兜底夹取比例（配置坏掉时用）。 */
    private static final double DEFAULT_FLOOR_RATIO = 0.25;
    private static final double DEFAULT_CEILING_RATIO = 4.0;

    private PriceEngine() {
    }

    // ── 商品 ──

    /**
     * 每个 market tick：商品价格随机游走 + 均值回归 + 冲击 + 事件乘数。
     *
     * <p>只处理 {@code enabled=true} 的条目；价格写回 {@code MarketStore}，
     * 由 MarketStore 自己再兜底夹到 [1, base×100]。</p>
     */
    public static void tickPrices(AimcContext ctx) {
        if (ctx == null || ctx.settings == null || ctx.market == null) {
            return;
        }
        MarketSettings settings = ctx.settings;
        long day = ctx.tradingDay();
        Map<String, Long> impacts = ctx.market.takeImpacts();

        // 流动性支持政策：扩大有效流动性，同等成交额的价格冲击更小。
        double liquidityMultiplier = 1.0;
        if (ctx.government != null) {
            try {
                liquidityMultiplier = ctx.government.liquidityMultiplier(day);
            } catch (RuntimeException error) {
                Log.warn("读取流动性政策失败，按 1.0 处理", error);
            }
        }
        if (!Double.isFinite(liquidityMultiplier) || liquidityMultiplier < 0.01) {
            liquidityMultiplier = 1.0;
        }

        double theta = safeFinite(settings.priceTheta, 0.05);
        double floorRatio = safeFinite(settings.priceFloorRatio, DEFAULT_FLOOR_RATIO);
        double ceilingRatio = safeFinite(settings.priceCeilingRatio, DEFAULT_CEILING_RATIO);
        if (floorRatio <= 0.0) {
            floorRatio = DEFAULT_FLOOR_RATIO;
        }
        if (ceilingRatio < floorRatio) {
            ceilingRatio = floorRatio;
        }

        for (MarketStore.CatalogItem item : ctx.market.items()) {
            // 任务书 3.3：只处理 enabled=true 的条目，停用的价格原样保留。
            if (item == null || !item.enabled) {
                continue;
            }
            try {
                long base = Math.max(1L, item.basePriceCents);
                long current = ctx.market.price(item.id);
                if (current <= 0L) {
                    current = base;
                }

                // 有效流动性 = 条目流动性 × 政策倍数（流动性支持会摊薄冲击）。
                double effectiveLiquidity = (double) Math.max(1L, item.liquidity) * liquidityMultiplier;
                long netBuy = impacts.getOrDefault(item.id, 0L);
                double impact = effectiveLiquidity <= 0.0
                        ? 0.0
                        : clampImpact(GOODS_IMPACT_FACTOR * (double) netBuy / effectiveLiquidity, settings,
                                IMPACT_LIMIT);

                double sigma = safeNonNegative(item.volatility);
                double drift = theta * (Math.log(base) - Math.log(current))
                        + sigma * ctx.random.nextGaussian()
                        + impact;
                double eventMultiplier = safeEventMultiplier(ctx.market.eventMultiplier(item.id));

                double next = (double) current * Math.exp(drift) * eventMultiplier;
                long cents = clampCents(next, base * floorRatio, base * ceilingRatio);
                if (cents < 0L) {
                    // NaN/Inf：保留原价，宁可价格不动也不写坏数据。
                    Log.warn("商品价格算出非法值，保留原价：" + item.id);
                    continue;
                }
                ctx.market.setPrice(item.id, cents);
            } catch (RuntimeException error) {
                // 单个条目出错不能影响整轮定价。
                Log.warn("更新商品价格失败（" + item.id + "）", error);
            }
        }
    }

    // ── 股票 ──

    /**
     * 每个 market tick：股价随机游走 + 均值回归 + 财报漂移 + 行业景气 + 冲击 + 事件乘数，
     * 然后重算大盘指数。
     *
     * <p>NPC 公司和玩家 IPO 的公司走同一套公式。{@code driftBias} 是"每交易日"的漂移
     * （财报 outlook 已经在 StockStore 里折算进去），所以按每交易日 tick 数摊到每个 tick；
     * 行业景气（{@link StockStore#sectorClimate(String)} × 政府刺激）同理，
     * 按 {@code (景气-1)/每交易日tick数} 加到 drift 上。</p>
     */
    public static void tickStocks(AimcContext ctx) {
        if (ctx == null || ctx.settings == null || ctx.stocks == null) {
            return;
        }
        MarketSettings settings = ctx.settings;
        long day = ctx.tradingDay();
        Map<String, Long> impacts;
        if (ctx.market == null) {
            impacts = java.util.Collections.emptyMap();
        } else {
            impacts = ctx.market.takeStockImpacts();
        }

        double theta = safeFinite(settings.priceTheta, 0.05);
        // 一交易日含多少个 market tick（配置成 tick > 交易日时至少按 1 算，避免漂移被放大）。
        double ticksPerDay = Math.max(1.0,
                (double) ctx.clock.dayMillis() / (double) Math.max(1L, ctx.clock.tickMillis()));

        for (StockStore.Stock stock : ctx.stocks.all()) {
            if (stock == null || stock.code == null) {
                continue;
            }
            if (stock.realSymbol != null && !stock.realSymbol.isBlank()) {
                // 现实股票的价格由行情同步覆盖，不参与游戏内的随机波动
                continue;
            }
            try {
                long initial = Math.max(1L, stock.initialPriceCents);
                long current = stock.priceCents;
                if (current <= 0L) {
                    current = initial;
                }

                long netBuy = impacts.getOrDefault(stock.code, 0L);
                double impact = stockImpact(stock, current, netBuy, settings);

                // 行业景气：行业气候 × 政府行业刺激，都按"每交易日偏差"摊到每个 tick。
                double climate = 1.0;
                try {
                    double sector = ctx.stocks.sectorClimate(stock.sector);
                    double stimulus = ctx.government == null
                            ? 1.0
                            : ctx.government.sectorStimulusMultiplier(stock.sector, day);
                    if (Double.isFinite(sector) && Double.isFinite(stimulus)) {
                        climate = sector * stimulus;
                    }
                } catch (RuntimeException error) {
                    Log.warn("读取行业景气失败，按 1.0 处理（" + stock.sector + "）", error);
                }
                if (!Double.isFinite(climate)) {
                    climate = 1.0;
                }

                double drift = (safeFinite(stock.driftBias, 0.0) + (climate - 1.0)) / ticksPerDay;
                double sigma = safeNonNegative(stock.volatility);
                double shock = theta * (Math.log(initial) - Math.log(current))
                        + sigma * ctx.random.nextGaussian()
                        + drift
                        + impact;
                double eventMultiplier = ctx.market == null
                        ? 1.0
                        : safeEventMultiplier(ctx.market.eventMultiplier(stock.code));

                double next = (double) current * Math.exp(shock) * eventMultiplier;
                long cents = clampCents(next, initial * 0.10, initial * 10.0);
                if (cents < 0L) {
                    Log.warn("股价算出非法值，保留原价：" + stock.code);
                    continue;
                }
                stock.priceCents = cents;
            } catch (RuntimeException error) {
                Log.warn("更新股价失败（" + stock.code + "）", error);
            }
        }

        try {
            ctx.stocks.tickIndex(day);
        } catch (RuntimeException error) {
            Log.warn("重算大盘指数失败", error);
        }
    }

    // ── 内部工具 ──

    /**
     * 股票冲击：净买入额 / (股价 × 流通股本 × 0.01) 折算的流动性，结果夹在 ±8%。
     *
     * @param netBuyValueCents 本 tick 净买入额（分，正=净买入）
     */
    private static double stockImpact(StockStore.Stock stock, long priceCents,
                                      long netBuyValueCents, MarketSettings settings) {
        if (netBuyValueCents == 0L) {
            return 0.0;
        }
        double liquidity = (double) Math.max(1L, priceCents)
                * (double) Math.max(1L, stock.sharesOutstanding) * 0.01;
        if (!Double.isFinite(liquidity) || liquidity <= 0.0) {
            return 0.0;
        }
        return clampImpact(STOCK_IMPACT_FACTOR * (double) netBuyValueCents / liquidity, settings,
                STOCK_IMPACT_LIMIT);
    }

    /** 冲击夹取：商品默认 ±0.20、股票 ±0.08；管理员把 {@code maxImpactPerTick} 调小则更小。 */
    private static double clampImpact(double rawImpact, MarketSettings settings, double maxLimit) {
        if (!Double.isFinite(rawImpact)) {
            return 0.0;
        }
        double limit = maxLimit;
        if (settings != null && Double.isFinite(settings.maxImpactPerTick)) {
            limit = Cents.clampDouble(settings.maxImpactPerTick, 0.0, maxLimit);
        }
        return Cents.clampDouble(rawImpact, -limit, limit);
    }

    /**
     * 把浮点价格夹到 [floorCents, ceilingCents] 并转成整数分。
     *
     * @return 合法价格（≥1 分）；NaN/Inf 返回 -1，调用方应保留原价
     */
    private static long clampCents(double value, double floorCents, double ceilingCents) {
        if (!Double.isFinite(value)) {
            return -1L;
        }
        // 配置异常（NaN）时退化到 1 分～原值，绝不把价格压成 0。
        double floor = Double.isFinite(floorCents) ? floorCents : 1.0;
        double ceiling = Double.isFinite(ceilingCents) ? ceilingCents : floor;
        double low = Math.max(1.0, floor);
        double high = Math.max(low, ceiling);
        long lowCents = Math.max(1L, (long) Math.ceil(low));
        long highCents = Math.max(lowCents, Math.round(high));
        if (value <= low) {
            return lowCents;
        }
        if (value >= high) {
            return highCents;
        }
        return Cents.clamp(Math.round(value), lowCents, highCents);
    }

    private static double safeEventMultiplier(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static double safeNonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private static double safeFinite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
