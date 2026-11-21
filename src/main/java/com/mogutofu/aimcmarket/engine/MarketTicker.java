package com.mogutofu.aimcmarket.engine;

import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.ai.AiGateway;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.util.Log;

import java.util.Collection;

/**
 * 市场 tick 调度器：把时钟、价格、利息、盯市、基金、政府/新闻 AI 串起来。
 *
 * <p>纯 Java，不碰 Minecraft。模组每个服务器 tick 调 {@link #onServerTick}；
 * AI 调用通过 {@link AimcContext#aiSubmitter} 异步跑，回调在主线程执行。</p>
 */
public final class MarketTicker {
    /** 模组侧要实现的回调（广播、私聊、在线玩家列表）。 */
    public interface Hooks {
        void notifyPlayer(String playerName, String text);

        default void info(String message) {
            Log.info(message);
        }
    }

    private long lastDay = Long.MIN_VALUE;
    private long lastIndexCentiPoints = -1L;
    private long lastNoticesFlushMs = 0L;

    /** 每个服务器 tick（20 次/秒）调一次。 */
    public void onServerTick(AimcContext ctx, long nowMs, Hooks hooks) {
        if (ctx == null || !ctx.settings.enabled) {
            return;
        }
        Collection<Account> online = ctx.onlineAccounts;

        // 1) 时钟推进（内部按 marketTickMinutes 判断跨了几个 tick）
        int ticks = ctx.clock.poll(nowMs);

        // 2) 在线工资（现实时间口径），每秒扫一次
        for (Account account : online) {
            try {
                long paid = IncomeService.applySalaryIfDue(ctx, account);
                if (paid > 0L) {
                    hooks.notifyPlayer(account.name, "在线工资 +" + com.mogutofu.aimcmarket.util.Cents.format(paid)
                            + " 币，余额 " + com.mogutofu.aimcmarket.util.Cents.format(account.balanceCents()) + " 币。");
                }
            } catch (RuntimeException error) {
                Log.warn("在线工资发放失败（" + account.name + "）", error);
            }
        }

        // 3) 引擎塞进来的通知（追保/交割/破产等），节流广播
        flushNotices(ctx, nowMs, hooks);

        if (ticks <= 0) {
            return;
        }

        long ticksPerDay = Math.max(1L, ctx.settings.tradingDayMinutes / Math.max(1, ctx.settings.marketTickMinutes));
        for (int i = 0; i < ticks; i++) {
            try {
                GovernmentEngine.updateIndicators(ctx, online);
                PriceEngine.tickPrices(ctx);
                PriceEngine.tickStocks(ctx);
                ctx.market.tickEvents();

                long index = ctx.stocks.indexCentiPoints();
                double indexReturn = lastIndexCentiPoints <= 0L ? 0.0
                        : (double) index / (double) lastIndexCentiPoints - 1.0;
                lastIndexCentiPoints = index;

                ctx.funds.onTick(ctx.tick(), ctx.tradingDay(), ticksPerDay, indexReturn,
                        ctx.accounts, ctx.ledger, ctx.settings, ctx.random);
                FinanceService.accrueInterest(ctx, ctx.settings.marketTickMinutes);
                FuturesEngine.onMarketTick(ctx);
                OptionsEngine.onMarketTick(ctx);
            } catch (RuntimeException error) {
                Log.warn("市场 tick 结算失败（第 " + ctx.tick() + " 个 tick）", error);
            }
        }

        // 4) 交易日切换：财报/分红/定期/贷款/退市
        long today = ctx.tradingDay();
        if (lastDay == Long.MIN_VALUE) {
            lastDay = today;
        }
        if (today != lastDay) {
            long from = lastDay + 1L;
            for (long day = from; day <= today; day++) {
                try {
                    FinanceService.onTradingDay(ctx);
                    CompanyEngine.onTradingDay(ctx);
                    IpoEngine.onTradingDay(ctx);
                    FinanceService.payDividends(ctx);
                } catch (RuntimeException error) {
                    Log.warn("交易日结算失败（第 " + day + " 个交易日）", error);
                }
            }
            lastDay = today;
        }

        // 5) 落盘（每个 market tick）
        try {
            ctx.saveAll();
        } catch (RuntimeException error) {
            Log.warn("市场 tick 落盘失败（下个 tick 重试）", error);
        }

        // 6) AI：新闻 / 政府决策（异步，回调在主线程）
        triggerNews(ctx, nowMs);
        triggerGovernment(ctx, nowMs);
    }

    private void flushNotices(AimcContext ctx, long nowMs, Hooks hooks) {
        if (nowMs - lastNoticesFlushMs < 1000L) {
            return;
        }
        lastNoticesFlushMs = nowMs;
        if (ctx.notices.isEmpty()) {
            return;
        }
        java.util.List<String> copy;
        synchronized (ctx.notices) {
            copy = new java.util.ArrayList<>(ctx.notices);
            ctx.notices.clear();
        }
        for (String notice : copy) {
            if (notice == null || notice.isBlank()) {
                continue;
            }
            if (ctx.broadcaster != null) {
                ctx.broadcaster.broadcast(notice, notice.startsWith("[政府]"));
            }
        }
    }

    // ── AI 触发（命令层也可以直接调用）──

    /** 到点就生成一条新闻（优先 AI，失败/没配置就用本地模板）。 */
    public static void triggerNews(AimcContext ctx, long nowMs) {
        if (ctx == null || !NewsEngine.dueForNews(ctx, nowMs)) {
            return;
        }
        if (ctx.newsInFlight) {
            return;
        }
        AiGateway ai = ctx.settings.aiEnabled ? ctx.ai : null;
        AimcContext.AiSubmitter submitter = ctx.aiSubmitter;
        JsonObject context = NewsEngine.buildNewsContext(ctx);
        if (ai == null || submitter == null) {
            String headline = NewsEngine.generateLocalNews(ctx);
            if (headline != null && !headline.isBlank() && ctx.broadcaster != null) {
                ctx.broadcaster.broadcast("市场新闻：" + headline, false);
            }
            return;
        }
        ctx.newsInFlight = true;
        submitter.submit("news", () -> ai.news(context), data -> {
            try {
                if (data != null) {
                    String headline = NewsEngine.applyAiNews(ctx, data);
                    if (headline != null && !headline.isBlank() && ctx.broadcaster != null) {
                        ctx.broadcaster.broadcast("市场新闻：" + headline, false);
                    }
                } else {
                    String headline = NewsEngine.generateLocalNews(ctx);
                    if (headline != null && !headline.isBlank() && ctx.broadcaster != null) {
                        ctx.broadcaster.broadcast("市场新闻：" + headline, false);
                    }
                }
                ctx.news.markGenerated(System.currentTimeMillis());
            } catch (RuntimeException error) {
                Log.warn("应用新闻失败", error);
            } finally {
                ctx.newsInFlight = false;
            }
        });
    }

    /** 本地体检认为需要政府决策时，异步问一次 AI；AI 不可用就用本地规则。 */
    public static void triggerGovernment(AimcContext ctx, long nowMs) {
        if (ctx == null || !ctx.settings.aiGovernmentEnabled || ctx.govInFlight) {
            return;
        }
        if (!GovernmentEngine.shouldAskAi(ctx, nowMs)) {
            return;
        }
        GovernmentEngine.markChecked(ctx, nowMs);
        JsonObject request = GovernmentEngine.buildRequest(ctx);
        AiGateway ai = ctx.settings.aiEnabled ? ctx.ai : null;
        AimcContext.AiSubmitter submitter = ctx.aiSubmitter;
        if (ai == null || submitter == null) {
            applyGovernmentDecision(ctx, GovernmentEngine.localFallback(ctx), false);
            return;
        }
        ctx.govInFlight = true;
        submitter.submit("government", () -> ai.government(request), data -> {
            try {
                JsonObject decision = data != null ? data : GovernmentEngine.localFallback(ctx);
                applyGovernmentDecision(ctx, decision, data != null);
            } catch (RuntimeException error) {
                Log.warn("应用政府决策失败", error);
            } finally {
                ctx.govInFlight = false;
            }
        });
    }

    private static void applyGovernmentDecision(AimcContext ctx, JsonObject decision, boolean fromAi) {
        try {
            ActionResult result = GovernmentEngine.applyDecision(ctx, decision, fromAi, ctx.onlineAccounts);
            if (result.ok && !result.broadcast.isEmpty() && ctx.broadcaster != null) {
                ctx.broadcaster.broadcast(result.broadcast, true);
            }
        } catch (RuntimeException error) {
            Log.warn("政府决策落地失败", error);
        }
    }
}
