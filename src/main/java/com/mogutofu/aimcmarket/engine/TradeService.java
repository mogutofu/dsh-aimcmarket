package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品交易：买 / 卖 / 有效报价 / 幂等。
 *
 * <p>价格口径：</p>
 * <ul>
 *   <li>买价 ask = 市场价 × (1 + {@code askSpread}) × 政府补贴/关税乘数；</li>
 *   <li>回收价 bid = 市场价 × {@code bidRatio} × 政府补贴/关税乘数；</li>
 *   <li>下单校验：{@code adminFreeze} 冻结、目录存在且 enabled、数量 1..maxItemPerOrder、
 *       每人 1 单/3 秒、单笔 ≤ maxOrderValue、余额够；</li>
 *   <li>钱：买 = {@code ledger.burn}（点差和货款都回收到系统，货币回收）；
 *       卖 = {@code ledger.issue}（系统增发回收金）。</li>
 * </ul>
 *
 * <p>幂等：带 {@code requestId} 的订单在 5 分钟内重复提交会直接返回第一次的结果，
 * 缓存在静态 {@link LinkedHashMap} 里，最多 500 条，按时间淘汰。命令层负责背包扣加，
 * 引擎只动账本和统计。</p>
 *
 * <p>纯 Java，不 import Minecraft 类；所有可预期失败都返回 {@link ActionResult#fail}，不抛异常给命令层。</p>
 */
public final class TradeService {
    /** 同一个人两单之间的最小间隔（毫秒）：1 单 / 3 秒。 */
    private static final long TRADE_INTERVAL_MS = 3_000L;
    /** requestId 幂等结果保留时长（毫秒）：5 分钟。 */
    private static final long REQUEST_TTL_MS = 5L * 60_000L;
    /** 幂等表最多缓存多少条。 */
    private static final int REQUEST_CACHE_MAX = 500;
    /** requestId 最长长度，超长的视作没传（防止恶意大 key 撑爆内存）。 */
    private static final int REQUEST_ID_MAX_LENGTH = 128;
    /** 幂等表：key = 账户 uuid + "|" + requestId，value = 当时的返回结果。 */
    private static final Map<String, CachedResult> REQUEST_CACHE = new LinkedHashMap<>();

    private TradeService() {
    }

    // ── 有效报价 ──

    /**
     * 当前买价（分/个）：市场价 × (1+点差) 后再乘政府补贴/关税乘数，四舍五入，至少 1 分。
     */
    public static long effectiveAskCents(AimcContext ctx, String itemId) {
        try {
            if (ctx == null || ctx.market == null || itemId == null || itemId.isBlank()) {
                return 1L;
            }
            MarketStore.CatalogItem item = ctx.market.item(itemId);
            if (item == null) {
                return 1L;
            }
            double spread = 0.02;
            if (ctx.settings != null && Double.isFinite(ctx.settings.askSpread)) {
                spread = Cents.clampDouble(ctx.settings.askSpread, 0.0, 1.0);
            }
            long raw = ctx.market.askRawCents(item.id, spread);
            return applyPolicyMultiplier(ctx, item.id, raw);
        } catch (RuntimeException error) {
            Log.warn("计算买价失败（" + itemId + "）", error);
            return 1L;
        }
    }

    /**
     * 当前回收价（分/个）：市场价 × 回收比例后再乘政府补贴/关税乘数，四舍五入，至少 1 分。
     */
    public static long effectiveBidCents(AimcContext ctx, String itemId) {
        try {
            if (ctx == null || ctx.market == null || itemId == null || itemId.isBlank()) {
                return 1L;
            }
            MarketStore.CatalogItem item = ctx.market.item(itemId);
            if (item == null) {
                return 1L;
            }
            double ratio = 0.60;
            if (ctx.settings != null && Double.isFinite(ctx.settings.bidRatio)) {
                ratio = Cents.clampDouble(ctx.settings.bidRatio, 0.01, 1.0);
            }
            long raw = ctx.market.bidRawCents(item.id, ratio);
            return applyPolicyMultiplier(ctx, item.id, raw);
        } catch (RuntimeException error) {
            Log.warn("计算回收价失败（" + itemId + "）", error);
            return 1L;
        }
    }

    /** 补贴降价 / 关税涨价：在原始买卖价上再乘政府乘数（默认 1.0）。 */
    private static long applyPolicyMultiplier(AimcContext ctx, String itemId, long rawCents) {
        long raw = Math.max(0L, rawCents);
        double multiplier = 1.0;
        if (ctx.government != null) {
            try {
                multiplier = ctx.government.commodityMultiplier(itemId, ctx.tradingDay());
            } catch (RuntimeException error) {
                Log.warn("读取补贴/关税政策失败，按原价处理（" + itemId + "）", error);
            }
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double value = (double) raw * multiplier;
        if (!Double.isFinite(value) || value >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, Math.round(value));
    }

    // ── 买入 ──

    public static ActionResult buy(AimcContext ctx, Account account, String itemId, int count) {
        return buy(ctx, account, itemId, count, null);
    }

    /**
     * 买入（带幂等 requestId）。
     *
     * @param requestId 订单幂等 id（一般用 UUID）；null/空串表示不幂等，直接执行
     */
    public static ActionResult buy(AimcContext ctx, Account account, String itemId, int count, String requestId) {
        String cacheKey = cacheKey(account, requestId);
        if (cacheKey != null) {
            ActionResult cached = recall(ctx, cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        ActionResult result;
        try {
            result = doBuy(ctx, account, itemId, count);
        } catch (RuntimeException error) {
            // ArithmeticException 也是 RuntimeException 的子类，这里一并兜住。
            Log.warn("买入异常（" + itemId + " ×" + count + "）", error);
            result = ActionResult.fail("交易失败：内部错误，本次没有扣钱");
        }
        if (cacheKey != null) {
            remember(ctx, cacheKey, result);
        }
        return result;
    }

    private static ActionResult doBuy(AimcContext ctx, Account account, String itemId, int count) {
        if (ctx == null || ctx.market == null || account == null) {
            return ActionResult.fail("账户或市场数据不存在，交易取消");
        }
        MarketSettings settings = ctx.settings;
        if (settings == null) {
            return ActionResult.fail("市场配置还没加载好，稍后再试");
        }
        if (!settings.enabled) {
            return ActionResult.fail("市场已关闭");
        }
        if (settings.adminFreeze) {
            return ActionResult.fail("管理员冻结了市场交易，稍后再试");
        }
        MarketStore.CatalogItem item = ctx.market.item(itemId);
        if (item == null) {
            return ActionResult.fail("目录里没有这个物品，用 /ex price 查一下");
        }
        String name = displayName(item);
        if (!item.enabled) {
            return ActionResult.fail("这个物品暂时不开放交易：" + name);
        }
        int maxCount = Math.max(1, settings.maxItemPerOrder);
        if (count < 1) {
            return ActionResult.fail("数量至少是 1");
        }
        if (count > maxCount) {
            return ActionResult.fail("单笔最多 " + maxCount + " 个");
        }
        long now = ctx.nowMs();
        long waitMs = tradeCooldownRemainingMs(account, now);
        if (waitMs > 0L) {
            return ActionResult.fail("手速太快了，" + secondsOf(waitMs) + " 秒后再买");
        }
        long unit = effectiveAskCents(ctx, item.id);
        long total;
        try {
            total = Math.multiplyExact(unit, (long) count);
        } catch (ArithmeticException overflow) {
            return ActionResult.fail("金额太大，单笔订单超出上限");
        }
        if (settings.maxOrderValueCents > 0L && total > settings.maxOrderValueCents) {
            return ActionResult.fail("单笔最多 " + Cents.format(settings.maxOrderValueCents)
                    + " 币，本次要 " + Cents.format(total) + " 币，减点数量吧");
        }
        long balance = account.balanceCents();
        if (balance < total) {
            return ActionResult.fail("余额不足：本次要 " + Cents.format(total) + " 币，你只有 "
                    + Cents.format(balance) + " 币");
        }
        // 点差和货款都回收到系统（货币回收），所以买单用 burn（销毁），不做市商转账。
        ctx.ledger.burn(account, total, "trade.buy", account.name,
                "买入 " + name + " ×" + count + "，单价 " + Cents.format(unit) + " 币");
        try {
            account.lastTradeAtMs = now;
            ctx.market.addImpact(item.id, total);
            ctx.market.recordVolume(ctx.tradingDay(), total);
        } catch (RuntimeException error) {
            // 钱已扣、货由命令层发，统计出错不能把整单判失败，记日志继续。
            Log.warn("买入后记录冲击/成交额失败（订单已成交）", error);
        }
        return ActionResult.ok("买入 " + name + " ×" + count
                + "，单价 " + Cents.format(unit) + " 币，共 " + Cents.format(total)
                + " 币，余额 " + Cents.format(account.balanceCents()) + " 币");
    }

    // ── 卖出 ──

    public static ActionResult sell(AimcContext ctx, Account account, String itemId, int count) {
        return sell(ctx, account, itemId, count, null);
    }

    /**
     * 卖出（带幂等 requestId）。
     *
     * <p>命令层负责先扣背包物品再调用本方法；引擎按目录白名单校验，回收价走 bid，
     * 钱由系统增发给玩家（回收金），并记负向价格冲击和成交额。</p>
     */
    public static ActionResult sell(AimcContext ctx, Account account, String itemId, int count, String requestId) {
        String cacheKey = cacheKey(account, requestId);
        if (cacheKey != null) {
            ActionResult cached = recall(ctx, cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        ActionResult result;
        try {
            result = doSell(ctx, account, itemId, count, -1L, "trade.sell", "卖出");
        } catch (RuntimeException error) {
            Log.warn("卖出异常（" + itemId + " ×" + count + "）", error);
            result = ActionResult.fail("交易失败：内部错误，物品不会被扣");
        }
        if (cacheKey != null) {
            remember(ctx, cacheKey, result);
        }
        return result;
    }

    /**
     * 按指定单价收购/退货：unitOverride > 0 时不用回收价（60%）。
     *
     * <p>命令层负责先扣背包物品；本方法只负责账本、冲击、成交额和回执。</p>
     */
    public static ActionResult sellWithPrice(AimcContext ctx, Account account, String itemId, int count,
                                             long unitCents, String actionLabel, String ledgerKind) {
        return doSell(ctx, account, itemId, count, Math.max(1L, unitCents),
                ledgerKind == null || ledgerKind.isBlank() ? "trade.buyback" : ledgerKind,
                actionLabel == null || actionLabel.isBlank() ? "收购" : actionLabel);
    }

    private static ActionResult doSell(AimcContext ctx, Account account, String itemId, int count,
                                      long unitOverride, String ledgerKind, String actionLabel) {
        if (ctx == null || ctx.market == null || account == null) {
            return ActionResult.fail("账户或市场数据不存在，交易取消");
        }
        MarketSettings settings = ctx.settings;
        if (settings == null) {
            return ActionResult.fail("市场配置还没加载好，稍后再试");
        }
        if (!settings.enabled) {
            return ActionResult.fail("市场已关闭");
        }
        if (settings.adminFreeze) {
            return ActionResult.fail("管理员冻结了市场交易，稍后再试");
        }
        MarketStore.CatalogItem item = ctx.market.item(itemId);
        if (item == null) {
            return ActionResult.fail("目录里没有这个物品，系统不收");
        }
        String name = displayName(item);
        if (!item.enabled) {
            return ActionResult.fail("这个物品暂时不开放交易：" + name);
        }
        int maxCount = Math.max(1, settings.maxItemPerOrder);
        if (count < 1) {
            return ActionResult.fail("数量至少是 1");
        }
        if (count > maxCount) {
            return ActionResult.fail("单笔最多 " + maxCount + " 个");
        }
        long now = ctx.nowMs();
        long waitMs = tradeCooldownRemainingMs(account, now);
        if (waitMs > 0L) {
            return ActionResult.fail("手速太快了，" + secondsOf(waitMs) + " 秒后再卖");
        }
        long unit = unitOverride > 0L ? unitOverride : effectiveBidCents(ctx, item.id);
        long total;
        try {
            total = Math.multiplyExact(unit, (long) count);
        } catch (ArithmeticException overflow) {
            return ActionResult.fail("金额太大，单笔交易超出上限");
        }
        if (total <= 0L) {
            return ActionResult.fail("这个物品现在的回收价是 0，先别卖");
        }
        // 回收金是系统增发（不是别人口袋里的钱），所以用 issue。
        ctx.ledger.issue(account, total, ledgerKind, account.name,
                actionLabel + " " + name + " ×" + count + "，单价 " + Cents.format(unit) + " 币");
        try {
            account.lastTradeAtMs = now;
            ctx.market.addImpact(item.id, Math.negateExact(total));
            ctx.market.recordVolume(ctx.tradingDay(), total);
        } catch (RuntimeException error) {
            Log.warn("卖出后记录冲击/成交额失败（订单已成交）", error);
        }
        return ActionResult.ok(actionLabel + " " + name + " ×" + count
                + "，单价 " + Cents.format(unit) + " 币/个，共 " + Cents.format(total)
                + " 币，余额 " + Cents.format(account.balanceCents()) + " 币");
    }

    // ── 内部工具 ──

    /** 下单冷却还剩多少毫秒；0 表示可以下单。 */
    private static long tradeCooldownRemainingMs(Account account, long nowMs) {
        if (account.lastTradeAtMs <= 0L) {
            return 0L;
        }
        long elapsed = nowMs - account.lastTradeAtMs;
        if (elapsed >= TRADE_INTERVAL_MS) {
            return 0L;
        }
        // 时钟被往回拨（elapsed < 0）也按冷却处理，避免刷单。
        return elapsed < 0L ? TRADE_INTERVAL_MS : TRADE_INTERVAL_MS - elapsed;
    }

    private static String displayName(MarketStore.CatalogItem item) {
        if (item.nameZh == null || item.nameZh.isBlank()) {
            return item.id;
        }
        return item.nameZh;
    }

    /** 毫秒向上取整成秒，用于提示"再等 N 秒"。 */
    private static long secondsOf(long millis) {
        return (Math.max(1L, millis) + 999L) / 1000L;
    }

    // ── requestId 幂等表 ──

    /** 缓存的一条幂等结果。 */
    private static final class CachedResult {
        final long atMs;
        final ActionResult result;

        CachedResult(long atMs, ActionResult result) {
            this.atMs = atMs;
            this.result = result;
        }
    }

    /**
     * 幂等 key：账户 uuid + requestId。
     *
     * <p>带上账户是为了防止别的玩家拿同一个 requestId 读到不属于自己的结果；
     * 空 requestId 返回 null，表示本次调用不幂等。</p>
     */
    private static String cacheKey(Account account, String requestId) {
        if (requestId == null) {
            return null;
        }
        String trimmed = requestId.trim();
        if (trimmed.isEmpty() || trimmed.length() > REQUEST_ID_MAX_LENGTH) {
            return null;
        }
        String owner = account == null ? "" : account.uuid;
        return owner + "|" + trimmed;
    }

    private static ActionResult recall(AimcContext ctx, String key) {
        long now = ctx == null ? System.currentTimeMillis() : ctx.nowMs();
        synchronized (REQUEST_CACHE) {
            purgeExpired(now);
            CachedResult cached = REQUEST_CACHE.get(key);
            return cached == null ? null : cached.result;
        }
    }

    private static void remember(AimcContext ctx, String key, ActionResult result) {
        long now = ctx == null ? System.currentTimeMillis() : ctx.nowMs();
        synchronized (REQUEST_CACHE) {
            purgeExpired(now);
            while (REQUEST_CACHE.size() >= REQUEST_CACHE_MAX) {
                Iterator<String> iterator = REQUEST_CACHE.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
            REQUEST_CACHE.put(key, new CachedResult(now, result));
        }
    }

    /** 按插入顺序清理超过 5 分钟的旧结果（LinkedHashMap 插入有序，遇到新记录就停）。 */
    private static void purgeExpired(long nowMs) {
        Iterator<Map.Entry<String, CachedResult>> iterator = REQUEST_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            CachedResult cached = iterator.next().getValue();
            if (nowMs - cached.atMs < REQUEST_TTL_MS) {
                break;
            }
            iterator.remove();
        }
    }
}
