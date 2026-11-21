package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.BalanceAccount;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.data.OptionsStore;
import com.mogutofu.aimcmarket.data.OptionsStore.OptionPosition;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 期权引擎：Black-Scholes 定价 / 报价 / 买卖开仓 / 提前平仓 / 每 tick 卖方保证金盯市 + 到期现金交割。
 *
 * <p>纯 Java 静态方法，不 import 任何 Minecraft 类；所有可预期失败返回
 * {@link ActionResult#fail(String)}，意外异常在这里吃掉（记日志 + 返回失败）。</p>
 *
 * <h2>定价口径（任务书 3.11）</h2>
 * <ul>
 *   <li>标的：全部上市股票（NPC + 玩家公司），S = {@code stock.priceCents}；</li>
 *   <li>r = {@code government.policyRate}；σ = 标的年化波动 = {@code stock.volatility × √365}；
 *       T = 到期交易日 / 365；</li>
 *   <li>1 张 = {@link OptionsStore#CONTRACT_SIZE}（100）股，欧式、现金交割；</li>
 *   <li>{@link #blackScholesCents} 的入参和返回值都是「每股」的分，调用方要再 ×100×张数；
 *       之所以这么定，是为了让函数和标的价格同量纲、方便单测；</li>
 *   <li>到期内在价值：call = max(0, S-K)，put = max(0, K-S)，乘以 100×张数现金结算。</li>
 * </ul>
 *
 * <h2>资金口径</h2>
 * <ul>
 *   <li>买方：权利金从钱包付给 {@code market:maker}；到期由 maker 支付内在价值；</li>
 *   <li>卖方：收 maker 的权利金、把保证金（max(20% 名义, 3×权利金)）从钱包划给 maker；
 *       到期时 maker 退回保证金，卖方从钱包付内在价值给 maker（钱包不够时 maker 承担差额并进 notices）；</li>
 *   <li>提前平仓按当前 BS 价反向成交，手续费 0.2% 进 {@code fee:pool}；</li>
 *   <li>所有腿都走 {@link Ledger}，加起来为 0，绝不直接改余额字段。</li>
 * </ul>
 */
public final class OptionsEngine {
    /** 1 张 = 100 股（与 {@link OptionsStore#CONTRACT_SIZE} 同源）。 */
    public static final long CONTRACT_SIZE = OptionsStore.CONTRACT_SIZE;
    /** 卖方追保宽限期：5 现实分钟，过期强平。 */
    public static final long MARGIN_CALL_GRACE_MS = 5L * 60_000L;
    /** 单笔张数上限（纯防溢出）。 */
    public static final long MAX_CONTRACTS = 100_000L;

    private static final double SQRT_365 = Math.sqrt(365.0);
    private static final double SQRT_2 = Math.sqrt(2.0);

    private OptionsEngine() {
    }

    // ══════════════════════════════════════════════════════════════
    // Black-Scholes
    // ══════════════════════════════════════════════════════════════

    /**
     * Black-Scholes 欧式期权理论价。
     *
     * <p><b>单位约定：</b>{@code spotCents} / {@code strikeCents} 是「每股」价格（分），
     * 返回值也是「每股」权利金（分）。一张合约的价格 = 本返回值 × 100；一笔订单 = 本返回值 × 100 × 张数。</p>
     *
     * <p>d1 = (ln(S/K) + (r + σ²/2)·T) / (σ·√T)，d2 = d1 − σ·√T；
     * N(x) 用 {@code 0.5·(1+erf(x/√2))}，erf 用 Abramowitz-Stegun 7.1.26 近似（绝对误差 &lt; 1.5e-7）。
     * T ≤ 0（到期）或 σ 无效时退化为内在价值。</p>
     *
     * @param spotCents   标的价格（分/股）
     * @param strikeCents 行权价（分/股）
     * @param rate        无风险利率（小数，0.03 = 3%），一般传 government.policyRate
     * @param sigma       年化波动率（小数），= 日波动 × √365
     * @param years       剩余年限（交易日 / 365）
     * @param call        true = 看涨，false = 看跌
     * @return 每股权利金（分），非负
     */
    public static long blackScholesCents(double spotCents, double strikeCents, double rate, double sigma,
                                         double years, boolean call) {
        if (!(spotCents > 0.0) || !(strikeCents > 0.0)) {
            return 0L;
        }
        long intrinsic = call
                ? Math.max(0L, Math.round(spotCents - strikeCents))
                : Math.max(0L, Math.round(strikeCents - spotCents));
        if (!(years > 0.0) || !(sigma > 0.0) || !Double.isFinite(rate)) {
            return intrinsic;
        }
        double volatility = Cents.clampDouble(sigma, 1.0e-6, 10.0);
        double sqrtT = Math.sqrt(years);
        double d1 = (Math.log(spotCents / strikeCents) + (rate + 0.5 * volatility * volatility) * years)
                / (volatility * sqrtT);
        double d2 = d1 - volatility * sqrtT;
        double discount = Math.exp(-rate * years);
        double price = call
                ? spotCents * normCdf(d1) - strikeCents * discount * normCdf(d2)
                : strikeCents * discount * normCdf(-d2) - spotCents * normCdf(-d1);
        if (Double.isNaN(price) || Double.isInfinite(price)) {
            return intrinsic;
        }
        return Math.max(0L, Math.round(price));
    }

    /** 标准正态分布 CDF：N(x) = 0.5·(1+erf(x/√2))。 */
    private static double normCdf(double x) {
        return 0.5 * (1.0 + erf(x / SQRT_2));
    }

    /**
     * erf 近似（Abramowitz &amp; Stegun 7.1.26，最大绝对误差约 1.5e-7）。
     * erf(x) = 1 − (a1·t + a2·t² + a3·t³ + a4·t⁴ + a5·t⁵)·e^(−x²)，t = 1/(1+p·|x|)。
     */
    private static double erf(double x) {
        double sign = x < 0.0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double polynomial = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t;
        return sign * (1.0 - polynomial * Math.exp(-ax * ax));
    }

    /** 期权 Delta：call = N(d1)，put = N(d1)−1；到期时退化成 0/±1。 */
    private static double deltaOf(double spotCents, double strikeCents, double rate, double sigma,
                                  double years, boolean call) {
        if (!(spotCents > 0.0) || !(strikeCents > 0.0)) {
            return 0.0;
        }
        if (!(years > 0.0) || !(sigma > 0.0) || !Double.isFinite(rate)) {
            long intrinsic = call
                    ? Math.round(spotCents - strikeCents)
                    : Math.round(strikeCents - spotCents);
            if (call) {
                return intrinsic > 0L ? 1.0 : 0.0;
            }
            return intrinsic > 0L ? -1.0 : 0.0;
        }
        double volatility = Cents.clampDouble(sigma, 1.0e-6, 10.0);
        double sqrtT = Math.sqrt(years);
        double d1 = (Math.log(spotCents / strikeCents) + (rate + 0.5 * volatility * volatility) * years)
                / (volatility * sqrtT);
        double nd1 = normCdf(d1);
        return call ? nd1 : nd1 - 1.0;
    }

    // ══════════════════════════════════════════════════════════════
    // 报价
    // ══════════════════════════════════════════════════════════════

    /**
     * 报价文本：列出 5 个行权价（现价 ±5%、±10% 的网格，取整到分）在 7 / 14 / 30 交易日下的
     * call/put 理论权利金（每股）与 Delta。
     *
     * @param strikeCents 想看的行权价（0 表示只看网格）；不在网格内时会在末尾给个提示
     */
    public static ActionResult quote(AimcContext ctx, String code, long strikeCents, int expiryDays) {
        if (ctx == null) {
            return ActionResult.fail("市场数据还没加载好，稍后再试");
        }
        try {
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            StockStore.Stock stock = resolveStock(ctx, code);
            if (stock == null) {
                return ActionResult.fail("没有这只股票：" + clean(code) + "（用 /ex stock list 看可交易的代码）");
            }
            long spot = stock.priceCents;
            if (spot <= 0L) {
                return ActionResult.fail(stock.name + " 当前价格异常，暂时不能报价");
            }
            double rate = ctx.government.policyRate;
            double sigma = annualVol(stock);
            long requestedStrike = Math.max(0L, strikeCents);
            boolean onGrid = false;

            List<String> lines = new ArrayList<>(32);
            lines.add(stock.name + "（" + stock.code + "）期权报价｜现价 " + Cents.format(spot)
                    + " 币/股｜年化波动 " + Cents.formatPercent(sigma) + "｜r = "
                    + Cents.formatPercent(rate) + "｜1 张 = " + CONTRACT_SIZE + " 股，欧式现金交割");
            for (double offset : OptionsStore.STRIKE_OFFSETS) {
                long strike = gridStrike(spot, offset);
                if (requestedStrike > 0L && strike == requestedStrike) {
                    onGrid = true;
                }
                lines.add("K = " + Cents.format(strike) + "（" + offsetText(offset) + "）");
                for (int days : OptionsStore.EXPIRY_DAYS) {
                    double years = days / 365.0;
                    long callPremium = blackScholesCents(spot, strike, rate, sigma, years, true);
                    long putPremium = blackScholesCents(spot, strike, rate, sigma, years, false);
                    double callDelta = deltaOf(spot, strike, rate, sigma, years, true);
                    double putDelta = deltaOf(spot, strike, rate, sigma, years, false);
                    lines.add("  " + days + " 交易日：C " + Cents.format(callPremium) + " 币/股（Δ"
                            + formatDelta(callDelta) + "）｜P " + Cents.format(putPremium) + " 币/股（Δ"
                            + formatDelta(putDelta) + "）");
                }
            }
            if (requestedStrike > 0L && !onGrid) {
                lines.add("注意：K = " + Cents.format(requestedStrike)
                        + " 不在当前标准网格内，下单请从上面 5 个行权价里选（现价 ±5%、±10%）。");
            }
            lines.add("一张权利金 = 每股报价 × " + CONTRACT_SIZE + "；手续费 "
                    + Cents.formatPercent(settings.optionsFeeRatio) + "（按当前价值）；卖方保证金 = max(名义 × "
                    + Cents.formatPercent(settings.optionsMarginRatio) + ", 权利金 × 3)，维持保证金 = 名义 × "
                    + Cents.formatPercent(settings.optionsMarginMaintenance) + "；到期按内在价值现金结算。");
            return ActionResult.ok(String.join("\n", lines));
        } catch (RuntimeException error) {
            Log.warn("期权报价失败（" + clean(code) + "）", error);
            return ActionResult.fail("报价失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 开仓
    // ══════════════════════════════════════════════════════════════

    /**
     * 期权开仓。
     *
     * <ul>
     *   <li>买方（{@code writer=false}）：付权利金 = 每股理论价 × 100 × 张数，钱从钱包转给 maker；</li>
     *   <li>卖方（{@code writer=true}）：从 maker 收权利金，同时把保证金
     *       {@code max(名义 × optionsMarginRatio, 权利金 × 3)}（名义 = 现价 × 100 × 张数）从钱包划给 maker；
     *       两者用同一笔三腿以内的账轧差，保证记账平衡。</li>
     * </ul>
     *
     * @param callOrPut   call / put（也接受 看涨 / 看跌）
     * @param strikeCents 行权价（分/股），必须落在现价 ±10% 内
     * @param expiryDays  7 / 14 / 30 交易日
     * @param contracts   张数（1 张 = 100 股）
     */
    public static ActionResult open(AimcContext ctx, Account account, boolean writer, String callOrPut, String code,
                                    long strikeCents, int expiryDays, long contracts) {
        if (ctx == null || account == null) {
            return ActionResult.fail("市场数据或账户还没加载好，稍后再试");
        }
        try {
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            if (!settings.enabled) {
                return ActionResult.fail("市场已关闭，暂时不能交易期权");
            }
            if (settings.adminFreeze) {
                return ActionResult.fail("管理员冻结了市场交易，稍后再试");
            }
            Boolean callFlag = parseOptionType(callOrPut);
            if (callFlag == null) {
                return ActionResult.fail("期权类型只能是 call 或 put（看涨 / 看跌），你给的是：" + clean(callOrPut));
            }
            boolean call = callFlag.booleanValue();
            StockStore.Stock stock = resolveStock(ctx, code);
            if (stock == null) {
                return ActionResult.fail("没有这只股票：" + clean(code) + "（用 /ex stock list 看可交易的代码）");
            }
            long spot = stock.priceCents;
            if (spot <= 0L) {
                return ActionResult.fail(stock.name + " 当前价格异常，暂时不能交易期权");
            }
            if (!isExpiryDays(expiryDays)) {
                return ActionResult.fail("到期期限只能选 7 / 14 / 30 个交易日（当前 " + expiryDays + "）");
            }
            if (contracts <= 0L) {
                return ActionResult.fail("张数必须是正整数（1 张 = " + CONTRACT_SIZE + " 股）");
            }
            if (contracts > MAX_CONTRACTS) {
                return ActionResult.fail("单笔最多 " + MAX_CONTRACTS + " 张，请拆成多笔");
            }
            if (strikeCents <= 0L) {
                return ActionResult.fail("行权价必须是正数（单位：分，例如 120 币填 12000）");
            }
            long minStrike = gridStrike(spot, -0.10);
            long maxStrike = gridStrike(spot, 0.10);
            if (strikeCents < minStrike || strikeCents > maxStrike) {
                return ActionResult.fail("行权价需在现价 ±10% 内（" + Cents.format(minStrike) + " ~ "
                        + Cents.format(maxStrike) + " 币），可用 /ex opt quote " + stock.code + " 看标准网格");
            }

            double rate = ctx.government.policyRate;
            double sigma = annualVol(stock);
            double years = expiryDays / 365.0;
            long premiumPerShare = blackScholesCents(spot, strikeCents, rate, sigma, years, call);
            if (premiumPerShare <= 0L) {
                return ActionResult.fail("按当前波动率算出的权利金不足 0.01 币/股（太虚值），暂不开放这个行权价");
            }
            long premiumPerContract = Cents.mul(premiumPerShare, CONTRACT_SIZE);
            long premiumTotal = Cents.mul(premiumPerContract, contracts);
            long notional = Cents.mul(Cents.mul(spot, CONTRACT_SIZE), contracts);
            double delta = deltaOf(spot, strikeCents, rate, sigma, years, call);
            String typeLabel = call ? "看涨(call)" : "看跌(put)";
            String sideLabel = writer ? "卖方" : "买方";
            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);

            OptionPosition position = new OptionPosition();
            position.uuid = account.uuid;
            position.playerName = actorName(account);
            position.underlying = stock.code;
            position.type = call ? "call" : "put";
            position.strikeCents = strikeCents;
            position.expiryDays = expiryDays;
            position.expiryDay = ctx.tradingDay() + (long) expiryDays;
            position.contracts = contracts;
            position.premiumPerContractCents = premiumPerContract;
            position.openedTick = ctx.tick();
            position.marginCallAtMs = 0L;
            position.writer = writer;

            if (!writer) {
                // 买方：钱包 → maker，付权利金。
                if (account.balanceCents() < premiumTotal) {
                    return ActionResult.fail("余额不足：权利金 " + Cents.format(premiumTotal) + " 币，钱包只有 "
                            + Cents.format(account.balanceCents()) + " 币");
                }
                position.marginCents = 0L;
                ctx.ledger.post("options.open", actorName(account),
                        "买入 " + stock.code + " " + position.type + " K=" + Cents.format(strikeCents) + " × "
                                + contracts + " 张",
                        Ledger.leg(account, -premiumTotal), Ledger.leg(maker, premiumTotal));
                ctx.options.add(position);
                return ActionResult.ok("买入成功 #" + position.id + "：" + sideLabel + " " + stock.name + "（"
                        + stock.code + "）" + expiryDays + " 交易日 " + typeLabel + " 行权价 "
                        + Cents.format(strikeCents) + " 币 × " + contracts + " 张；权利金 "
                        + Cents.format(premiumPerShare) + " 币/股（" + Cents.format(premiumPerContract)
                        + " 币/张），共付 " + Cents.format(premiumTotal) + " 币；Δ " + formatDelta(delta)
                        + "；余额 " + Cents.format(account.balanceCents()) + " 币；第 " + position.expiryDay
                        + " 交易日到期现金结算。");
            }

            // 卖方：收权利金、缴保证金。保证金取「名义比例」和「权利金 ×3」的大者。
            long margin = Math.max(Cents.percent(notional, settings.optionsMarginRatio),
                    Cents.mul(premiumTotal, 3L));
            margin = Math.max(margin, Math.max(0L, settings.optionsMinMarginCents));
            long cashNeeded = Math.max(0L, Math.subtractExact(margin, premiumTotal));
            if (account.balanceCents() < cashNeeded) {
                return ActionResult.fail("保证金不够：卖出 " + contracts + " 张需要划出保证金 "
                        + Cents.format(margin) + " 币（可先收权利金 " + Cents.format(premiumTotal) + " 币，还需 "
                        + Cents.format(cashNeeded) + " 币），钱包只有 "
                        + Cents.format(account.balanceCents()) + " 币");
            }
            position.marginCents = margin;
            // 两腿轧差：玩家净额 = 权利金 - 保证金（通常为负 = 从钱包扣），maker 相反；相加为 0。
            long playerNet = Math.subtractExact(premiumTotal, margin);
            ctx.ledger.post("options.open", actorName(account),
                    "卖出开仓 " + stock.code + " " + position.type + " K=" + Cents.format(strikeCents) + " × "
                            + contracts + " 张",
                    Ledger.leg(account, playerNet), Ledger.leg(maker, Math.subtractExact(0L, playerNet)));
            ctx.options.add(position);
            return ActionResult.ok("卖出开仓成功 #" + position.id + "：" + sideLabel + " " + stock.name + "（"
                    + stock.code + "）" + expiryDays + " 交易日 " + typeLabel + " 行权价 "
                    + Cents.format(strikeCents) + " 币 × " + contracts + " 张；收取权利金 "
                    + Cents.format(premiumPerShare) + " 币/股（" + Cents.format(premiumPerContract)
                    + " 币/张）共 " + Cents.format(premiumTotal) + " 币；保证金 " + Cents.format(margin)
                    + " 币（名义 " + Cents.format(notional) + " × " + Cents.formatPercent(settings.optionsMarginRatio)
                    + " 与 权利金×3 取大）；Δ " + formatDelta(delta) + "；余额 "
                    + Cents.format(account.balanceCents()) + " 币；维持保证金 "
                    + Cents.format(Cents.percent(notional, settings.optionsMarginMaintenance)) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("期权开仓失败（" + actorName(account) + " " + clean(code) + "）", error);
            return ActionResult.fail("开仓失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 提前平仓
    // ══════════════════════════════════════════════════════════════

    /**
     * 提前平仓：按当前 BS 理论价反向成交，手续费 = 当前价值 × {@code optionsFeeRatio}。
     *
     * <ul>
     *   <li>买方：maker 付当前价值给玩家，玩家付手续费（到账 = 价值 − 手续费）；</li>
     *   <li>卖方：maker 退回全额保证金，玩家付「当前价值 + 手续费」（用保证金轧差，钱包不够时才需要额外补钱）；</li>
     *   <li>手续费进 {@code fee:pool}；所有腿相加为 0。</li>
     * </ul>
     */
    public static ActionResult close(AimcContext ctx, Account account, String positionId) {
        if (ctx == null || account == null) {
            return ActionResult.fail("市场数据或账户还没加载好，稍后再试");
        }
        try {
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            OptionPosition position = ctx.options.find(clean(positionId));
            if (position == null) {
                return ActionResult.fail("找不到这个期权持仓：" + clean(positionId) + "（用 /ex opt positions 查编号）");
            }
            if (!account.uuid.equals(position.uuid)) {
                return ActionResult.fail("这不是你的持仓，不能平仓");
            }
            StockStore.Stock stock = ctx.stocks.stock(position.underlying);
            long spot = stock == null ? 0L : Math.max(0L, stock.priceCents);
            long daysLeft = Math.max(0L, position.expiryDay - ctx.tradingDay());
            double years = daysLeft / 365.0;
            double sigma = stock == null ? 0.0 : annualVol(stock);
            boolean call = isCall(position.type);
            long premiumPerShare = blackScholesCents(spot, position.strikeCents, ctx.government.policyRate,
                    sigma, years, call);
            long premiumPerContract = Cents.mul(premiumPerShare, CONTRACT_SIZE);
            long currentValue = Cents.mul(premiumPerContract, position.contracts);
            long fee = Cents.percent(currentValue, settings.optionsFeeRatio);
            long margin = position.writer ? position.marginCents : 0L;
            long netCash = position.writer
                    ? Math.subtractExact(Math.subtractExact(margin, currentValue), fee)
                    : Math.subtractExact(currentValue, fee);
            if (netCash < 0L && account.balanceCents() < Math.subtractExact(0L, netCash)) {
                return ActionResult.fail("平仓需要倒贴 " + Cents.format(Math.subtractExact(0L, netCash))
                        + " 币（当前价值 " + Cents.format(currentValue) + " + 手续费 " + Cents.format(fee)
                        + " - 保证金 " + Cents.format(margin) + "），钱包只有 "
                        + Cents.format(account.balanceCents()) + " 币");
            }

            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
            BalanceAccount feePool = ledgerAccount(ctx, Ledger.FEE_POOL);
            // 腿：玩家 +netCash，maker -(netCash+fee)，fee:pool +fee；三者相加为 0。
            if (netCash != 0L || fee > 0L) {
                List<Ledger.Leg> legs = new ArrayList<>(3);
                if (netCash != 0L) {
                    legs.add(Ledger.leg(account, netCash));
                }
                legs.add(Ledger.leg(maker, Math.subtractExact(Math.subtractExact(0L, netCash), fee)));
                legs.add(Ledger.leg(feePool, fee));
                ctx.ledger.post("options.close", actorName(account),
                        "平仓 #" + position.id + " " + (position.writer ? "卖方 " : "买方 ") + position.underlying
                                + " " + position.type + " K=" + Cents.format(position.strikeCents),
                        legs.toArray(new Ledger.Leg[0]));
            }
            long premiumPaid = position.totalPremiumCents();
            long pnl = position.writer
                    ? Math.subtractExact(premiumPaid, currentValue)
                    : Math.subtractExact(currentValue, premiumPaid);
            ctx.options.remove(position);

            StringBuilder message = new StringBuilder();
            message.append("平仓成功 #").append(position.id).append("：")
                    .append(position.writer ? "卖方 " : "买方 ").append(position.underlying).append(' ')
                    .append(position.expiryDays).append(" 交易日 ").append(call ? "看涨(call)" : "看跌(put)")
                    .append(" 行权价 ").append(Cents.format(position.strikeCents)).append(" 币 × ")
                    .append(position.contracts).append(" 张；当前权利金 ").append(Cents.format(premiumPerShare))
                    .append(" 币/股（").append(Cents.format(premiumPerContract)).append(" 币/张），合约价值 ")
                    .append(Cents.format(currentValue)).append(" 币；手续费 ").append(Cents.format(fee))
                    .append(" 币");
            if (position.writer) {
                message.append("；退回保证金 ").append(Cents.format(margin)).append(" 币");
            }
            message.append("；").append(netCash >= 0L ? "到账 " : "额外支付 ").append(Cents.format(Math.abs(netCash)))
                    .append(" 币；盈亏（相对权利金）").append(Cents.formatSigned(pnl)).append(" 币；余额 ")
                    .append(Cents.format(account.balanceCents())).append(" 币。");
            return ActionResult.ok(message.toString());
        } catch (RuntimeException error) {
            Log.warn("期权平仓失败（" + actorName(account) + " " + clean(positionId) + "）", error);
            return ActionResult.fail("平仓失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 每个 market tick：卖方保证金盯市 / 追保 / 强平 / 到期结算
    // ══════════════════════════════════════════════════════════════

    /**
     * 每个 market tick 调一次：
     * <ul>
     *   <li>卖方：维持保证金 = 名义（现价 × 100 × 张数）× {@code optionsMarginMaintenance}，
     *       保证金不足 → 追保通知（5 分钟），到期未补足 → 按当前 BS 价买回强平；</li>
     *   <li>所有持仓：{@code ctx.tradingDay() >= expiryDay} 时按内在价值现金交割并删除持仓。</li>
     * </ul>
     */
    public static void onMarketTick(AimcContext ctx) {
        if (ctx == null || ctx.options == null) {
            return;
        }
        List<OptionPosition> snapshot;
        try {
            snapshot = new ArrayList<>(ctx.options.all());
        } catch (RuntimeException error) {
            Log.warn("读取期权持仓失败（跳过本 tick 的期权结算）", error);
            return;
        }
        long nowMs = ctx.nowMs();
        long day = ctx.tradingDay();
        for (OptionPosition position : snapshot) {
            try {
                settleOrMark(ctx, position, day, nowMs);
            } catch (RuntimeException error) {
                Log.warn("期权盯市失败（#" + position.id + " " + position.underlying + "）", error);
            }
        }
    }

    private static void settleOrMark(AimcContext ctx, OptionPosition position, long day, long nowMs) {
        StockStore.Stock stock = ctx.stocks.stock(position.underlying);
        long spot = stock == null ? 0L : Math.max(0L, stock.priceCents);
        long daysLeft = position.expiryDay - day;
        if (daysLeft <= 0L) {
            settleAtExpiry(ctx, position, spot, stock != null);
            return;
        }
        // 买方不缴保证金，不需要盯市；卖方才有维持保证金要求。
        if (!position.writer || spot <= 0L || stock == null) {
            return;
        }
        double sigma = annualVol(stock);
        long notional = position.notionalCents(spot);
        long maintenance = Cents.percent(notional, ctx.settings.optionsMarginMaintenance);
        if (position.marginCents >= maintenance) {
            position.marginCallAtMs = 0L;
            return;
        }
        if (position.marginCallAtMs <= 0L) {
            position.marginCallAtMs = nowMs;
            addNotice(ctx, "【期权追保】" + playerName(ctx, position) + " 的 #" + position.id + " 卖方 "
                    + position.underlying + " " + position.expiryDays + " 交易日 " + typeText(position) + " K="
                    + Cents.format(position.strikeCents) + " × " + position.contracts + " 张：保证金 "
                    + Cents.format(position.marginCents) + " 币 < 维持保证金 " + Cents.format(maintenance)
                    + " 币，缺口 " + Cents.format(maintenance - position.marginCents)
                    + " 币。5 分钟内 /ex opt close " + position.id + " 平仓，否则按当前价强制买回。");
            return;
        }
        if (nowMs - position.marginCallAtMs >= MARGIN_CALL_GRACE_MS) {
            forceLiquidateWriter(ctx, position, spot, sigma, daysLeft);
        }
    }

    /** 卖方强平：按当前 BS 价买回，手续费 0.2%，从保证金里扣；不足部分由 maker 承担。 */
    private static void forceLiquidateWriter(AimcContext ctx, OptionPosition position,
                                             long spot, double sigma, long daysLeft) {
        Account account = ctx.accounts.get(position.uuid);
        if (account == null) {
            ctx.options.remove(position);
            Log.warn("期权强平但账户已不存在，直接移除持仓 #" + position.id + "（" + position.uuid + "）");
            return;
        }
        double years = Math.max(0L, daysLeft) / 365.0;
        long premiumPerShare = blackScholesCents(spot, position.strikeCents, ctx.government.policyRate, sigma,
                years, isCall(position.type));
        long currentValue = Cents.mul(Cents.mul(premiumPerShare, CONTRACT_SIZE), position.contracts);
        MarketSettings settings = ctx.settings;
        long fee = Cents.percent(currentValue, settings.optionsFeeRatio);
        long margin = position.marginCents;
        long refund = Math.max(0L, Math.subtractExact(Math.subtractExact(margin, currentValue), fee));

        BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
        BalanceAccount feePool = ledgerAccount(ctx, Ledger.FEE_POOL);
        // 腿：玩家 +refund，maker -(refund+fee)，fee:pool +fee；相加为 0。至少两腿才记账。
        if (refund > 0L || fee > 0L) {
            List<Ledger.Leg> legs = new ArrayList<>(3);
            if (refund > 0L) {
                legs.add(Ledger.leg(account, refund));
            }
            legs.add(Ledger.leg(maker, Math.subtractExact(Math.subtractExact(0L, refund), fee)));
            if (fee > 0L) {
                legs.add(Ledger.leg(feePool, fee));
            }
            ctx.ledger.post("options.liquidate", actorName(account),
                    "强平 #" + position.id + " 卖方 " + position.underlying + " " + position.type,
                    legs.toArray(new Ledger.Leg[0]));
        }
        long premiumPaid = position.totalPremiumCents();
        long pnl = Math.subtractExact(premiumPaid, currentValue);
        ctx.options.remove(position);
        addNotice(ctx, "【期权强平】" + actorName(account) + " 的 #" + position.id + " 卖方 " + position.underlying
                + " " + position.expiryDays + " 交易日 " + typeText(position) + " K="
                + Cents.format(position.strikeCents) + " × " + position.contracts + " 张被强平（现价 "
                + Cents.format(spot) + " 币，当前价值 " + Cents.format(currentValue) + " 币）：保证金 "
                + Cents.format(margin) + " 币，扣手续费 " + Cents.format(fee) + " 币，退回 "
                + Cents.format(refund) + " 币，盈亏 " + Cents.formatSigned(pnl) + " 币。");
        Log.info("期权卖方强平 #" + position.id + " " + position.underlying + " → " + actorName(account)
                + "，退回 " + Cents.format(refund) + " 币");
    }

    /**
     * 到期现金交割：内在价值 = max(0, S−K)（call）/ max(0, K−S)（put），每股，再 ×100 ×张数。
     *
     * <p>买方：maker 支付内在价值；卖方：maker 退回保证金，卖方从钱包付内在价值给 maker；
     * 钱包不够时由 maker 承担差额，并写进 {@code ctx.notices}。</p>
     */
    private static void settleAtExpiry(AimcContext ctx, OptionPosition position, long spot, boolean stockAlive) {
        Account account = ctx.accounts.get(position.uuid);
        if (account == null) {
            ctx.options.remove(position);
            Log.warn("期权到期但账户已不存在，直接移除持仓 #" + position.id + "（" + position.uuid + "）");
            return;
        }
        BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
        // OptionsStore 里的方法名是 intrinsicPerContract，公式是「每股」内在价值，这里按每股用，再 ×100×张数。
        long intrinsicPerShare = position.intrinsicPerContract(spot);
        long intrinsicTotal = Cents.mul(Cents.mul(intrinsicPerShare, CONTRACT_SIZE), position.contracts);
        long premiumPaid = position.totalPremiumCents();
        String underlyingNote = stockAlive ? "" : "（标的已退市，现价按 0 结算）";
        String actor = actorName(account);

        if (!position.writer) {
            if (intrinsicTotal > 0L) {
                ctx.ledger.transfer(maker, account, intrinsicTotal, "options.expire", actor,
                        "到期结算 #" + position.id + " " + position.underlying + " " + position.type);
            }
            long pnl = Math.subtractExact(intrinsicTotal, premiumPaid);
            ctx.options.remove(position);
            addNotice(ctx, "【期权到期】" + actor + " 的 #" + position.id + " 买方 " + position.underlying + " "
                    + position.expiryDays + " 交易日 " + typeText(position) + " K="
                    + Cents.format(position.strikeCents) + " × " + position.contracts + " 张到期" + underlyingNote
                    + "：结算价 " + Cents.format(spot) + " 币/股，内在价值 " + Cents.format(intrinsicPerShare)
                    + " 币/股，共收 " + Cents.format(intrinsicTotal) + " 币（权利金成本 "
                    + Cents.format(premiumPaid) + " 币，净盈亏 " + Cents.formatSigned(pnl) + " 币）。");
            Log.info("期权到期 #" + position.id + " 买方 " + position.underlying + " → " + actor + "，结算 "
                    + Cents.format(intrinsicTotal) + " 币");
            return;
        }

        // 卖方：先退保证金、再从钱包付内在价值，轧成一笔账（一次 post 保证原子性：不会出现
        // 「保证金退了但付款失败」导致下个 tick 再退一次）。可动用 = 原钱包 + 退回的保证金；
        // 差额由 maker 承担（账本上表现为 maker 少收，不会凭空造钱）。
        long marginRefund = position.marginCents;
        long wallet = Math.max(0L, account.balanceCents());
        long available = Math.addExact(wallet, marginRefund);
        long paid = intrinsicTotal > 0L ? Math.min(available, intrinsicTotal) : 0L;
        long playerNet = Math.subtractExact(marginRefund, paid);
        if (playerNet != 0L) {
            ctx.ledger.post("options.expire", actor,
                    "到期结算 #" + position.id + " 卖方 " + position.underlying + " " + position.type
                            + "（内在价值 " + Cents.format(intrinsicTotal) + "）",
                    Ledger.leg(account, playerNet), Ledger.leg(maker, Math.subtractExact(0L, playerNet)));
        }
        long shortfall = Math.max(0L, Math.subtractExact(intrinsicTotal, paid));
        long pnl = Math.subtractExact(premiumPaid, intrinsicTotal);
        ctx.options.remove(position);
        addNotice(ctx, "【期权到期】" + actor + " 的 #" + position.id + " 卖方 " + position.underlying + " "
                + position.expiryDays + " 交易日 " + typeText(position) + " K="
                + Cents.format(position.strikeCents) + " × " + position.contracts + " 张到期" + underlyingNote
                + "：结算价 " + Cents.format(spot) + " 币/股，内在价值 " + Cents.format(intrinsicPerShare)
                + " 币/股，应付 " + Cents.format(intrinsicTotal) + " 币（保证金+钱包共支付 "
                + Cents.format(paid) + " 币，退回保证金 " + Cents.format(marginRefund) + " 币）；权利金 "
                + Cents.format(premiumPaid) + " 币，净盈亏 " + Cents.formatSigned(pnl) + " 币"
                + (shortfall > 0L ? "；钱包不足，做市商承担差额 " + Cents.format(shortfall) + " 币" : "") + "。");
        if (shortfall > 0L) {
            Log.warn("期权到期卖方钱包不足，做市商承担差额 " + Cents.format(shortfall) + " 币（#" + position.id
                    + " " + position.underlying + " " + actor + "）");
        }
        Log.info("期权到期 #" + position.id + " 卖方 " + position.underlying + " → " + actor + "，内在价值 "
                + Cents.format(intrinsicTotal) + " 币，钱包扣 " + Cents.format(paid) + " 币");
    }

    // ══════════════════════════════════════════════════════════════
    // 查询文本（给命令层）
    // ══════════════════════════════════════════════════════════════

    /**
     * {@code /ex opt positions} 的多行文本（{@code \n} 分隔）；没有持仓时返回一句提示。
     *
     * <p>只读，不抛异常：行情读不到时标注"行情不可用"。</p>
     */
    public static String positionsText(AimcContext ctx, Account account) {
        if (ctx == null || account == null) {
            return "期权持仓不可用（账户还没加载好）";
        }
        try {
            List<OptionPosition> positions = ctx.options.of(account.uuid);
            if (positions.isEmpty()) {
                return "你当前没有期权持仓。用 /ex opt quote <代码> 看报价，/ex opt buy|sell <call|put> <代码> <行权价> <到期> <张数> 开仓。";
            }
            long day = ctx.tradingDay();
            long totalPnl = 0L;
            StringBuilder text = new StringBuilder();
            text.append("期权持仓 ").append(positions.size()).append(" 笔（1 张 = ").append(CONTRACT_SIZE)
                    .append(" 股）：");
            for (OptionPosition position : positions) {
                text.append("\n#").append(position.id).append("　").append(position.writer ? "卖方" : "买方")
                        .append(position.underlying).append(' ').append(position.expiryDays).append(" 交易日 ")
                        .append(typeText(position)).append(" K=").append(Cents.format(position.strikeCents))
                        .append(" × ").append(position.contracts).append(" 张｜第 ").append(position.expiryDay)
                        .append(" 交易日到期");
                long daysLeft = Math.max(0L, position.expiryDay - day);
                text.append(daysLeft <= 0L ? "（今日结算）" : "（还剩 " + daysLeft + " 交易日）");
                StockStore.Stock stock = ctx.stocks.stock(position.underlying);
                if (stock == null || stock.priceCents <= 0L) {
                    text.append("\n   行情不可用｜开仓权利金 ")
                            .append(Cents.format(position.premiumPerContractCents)).append(" 币/张");
                    if (position.writer) {
                        text.append("｜保证金 ").append(Cents.format(position.marginCents)).append(" 币");
                    }
                    continue;
                }
                long spot = stock.priceCents;
                double sigma = annualVol(stock);
                long premiumPerShare = blackScholesCents(spot, position.strikeCents, ctx.government.policyRate,
                        sigma, daysLeft / 365.0, isCall(position.type));
                long premiumPerContract = Cents.mul(premiumPerShare, CONTRACT_SIZE);
                long value = Cents.mul(premiumPerContract, position.contracts);
                long premiumPaid = position.totalPremiumCents();
                long pnl = position.writer
                        ? Math.subtractExact(premiumPaid, value)
                        : Math.subtractExact(value, premiumPaid);
                totalPnl = Math.addExact(totalPnl, pnl);
                text.append("\n   现价 ").append(Cents.format(spot)).append(" 币/股｜当前权利金 ")
                        .append(Cents.format(premiumPerShare)).append(" 币/股（")
                        .append(Cents.format(premiumPerContract)).append(" 币/张），市值 ")
                        .append(Cents.format(value)).append(" 币｜权利金 ")
                        .append(Cents.format(premiumPaid)).append(" 币｜盈亏 ")
                        .append(Cents.formatSigned(pnl)).append(" 币");
                if (position.writer) {
                    long maintenance = Cents.percent(position.notionalCents(spot),
                            ctx.settings.optionsMarginMaintenance);
                    text.append("｜保证金 ").append(Cents.format(position.marginCents)).append(" 币，维持 ")
                            .append(Cents.format(maintenance)).append(" 币")
                            .append(position.marginCallAtMs > 0L ? "｜追保中" : "");
                }
            }
            text.append("\n合计盈亏（相对权利金）").append(Cents.formatSigned(totalPnl)).append(" 币。");
            return text.toString();
        } catch (RuntimeException error) {
            Log.warn("读取期权持仓文本失败（" + actorName(account) + "）", error);
            return "期权持仓读取失败，稍后再试。";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════════════════════

    /** 年化波动率：任务书 3.11 口径 = 标的日波动 × √365。 */
    private static double annualVol(StockStore.Stock stock) {
        if (stock == null) {
            return 0.0;
        }
        double daily = Double.isFinite(stock.volatility) ? Math.max(0.0, stock.volatility) : 0.0;
        return Cents.clampDouble(daily * SQRT_365, 1.0e-6, 10.0);
    }

    /** 网格行权价：现价 × (1+offset)，四舍五入到分，至少 1 分。 */
    private static long gridStrike(long spotCents, double offset) {
        double value = (double) spotCents * (1.0 + offset);
        if (!Double.isFinite(value) || value >= (double) Long.MAX_VALUE) {
            return Math.max(1L, spotCents);
        }
        return Math.max(1L, Math.round(value));
    }

    /** 期权类型解析：call/c/看涨 → true，put/p/看跌 → false，其它返回 null。 */
    private static Boolean parseOptionType(String text) {
        if (text == null) {
            return null;
        }
        String type = text.trim().toLowerCase(Locale.ROOT);
        if (type.equals("call") || type.equals("c") || type.equals("看涨")) {
            return Boolean.TRUE;
        }
        if (type.equals("put") || type.equals("p") || type.equals("看跌")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isCall(String type) {
        return type != null && "call".equalsIgnoreCase(type.trim());
    }

    private static String typeText(OptionPosition position) {
        return isCall(position.type) ? "看涨(call)" : "看跌(put)";
    }

    /** 到期天数只允许 7 / 14 / 30（与 {@link OptionsStore#EXPIRY_DAYS} 一致）。 */
    private static boolean isExpiryDays(int expiryDays) {
        for (int days : OptionsStore.EXPIRY_DAYS) {
            if (days == expiryDays) {
                return true;
            }
        }
        return false;
    }

    /** 行权价偏移显示：-0.10 → "-10%"，0.05 → "+5%"。 */
    private static String offsetText(double offset) {
        return String.format(Locale.ROOT, "%+.0f%%", offset * 100.0);
    }

    private static String formatDelta(double delta) {
        return String.format(Locale.ROOT, "%.2f", delta);
    }

    /** 股票解析：代码优先，其次公司中文名；找不到返回 null。 */
    private static StockStore.Stock resolveStock(AimcContext ctx, String text) {
        if (ctx == null || ctx.stocks == null || text == null || text.isBlank()) {
            return null;
        }
        StockStore.Stock stock = ctx.stocks.stock(text);
        if (stock != null) {
            return stock;
        }
        String needle = text.trim();
        for (StockStore.Stock candidate : ctx.stocks.all()) {
            if (candidate.name != null && candidate.name.equalsIgnoreCase(needle)) {
                return candidate;
            }
        }
        return null;
    }

    private static String playerName(AimcContext ctx, OptionPosition position) {
        if (position.playerName != null && !position.playerName.isBlank()) {
            return position.playerName;
        }
        Account account = ctx == null ? null : ctx.accounts.get(position.uuid);
        return account == null ? position.uuid : actorName(account);
    }

    private static void addNotice(AimcContext ctx, String notice) {
        if (ctx != null && ctx.notices != null && notice != null && !notice.isBlank()) {
            ctx.notices.add(notice);
        }
    }

    private static BalanceAccount ledgerAccount(AimcContext ctx, String accountId) {
        BalanceAccount account = ctx.ledger.find(accountId);
        if (account == null) {
            throw new IllegalStateException("记账账户还没就绪：" + accountId);
        }
        return account;
    }

    private static String actorName(Account account) {
        if (account == null) {
            return "系统";
        }
        return account.name == null || account.name.isBlank() ? account.uuid : account.name;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static String brief(RuntimeException error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "内部错误，请稍后再试" : message;
    }
}
