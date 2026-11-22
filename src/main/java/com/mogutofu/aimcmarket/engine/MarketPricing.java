package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.util.Log;

/**
 * 物价系数：按"服务器人均货币量"整体缩放商品价格。
 *
 * <p>目的：防止经济通胀后物价相对玩家财富变得毫无意义（例如有人已经赚到几百万币）。
 * 人均货币（现金 + 存款）超过基准值后，物价按比例上浮；系数只上调不下调，
 * 上限 50 倍，避免早期玩家被物价卡死。</p>
 *
 * <p>{@code 物价 = 基础价 × 系数}，系数 = clamp((人均货币 / 基准人均)^0.8, 1, 50)。
 * 股票不参与缩放（现实股票按真实价、游戏股票按自己的引擎走）。</p>
 */
public final class MarketPricing {
    /** 人均货币基准：2000 币。低于它时系数固定 1.0。 */
    public static final long REFERENCE_AVG_CENTS = 200_000L;
    /** 弹性：人均货币涨 10 倍，物价涨 10^ELASTICITY 倍。 */
    public static final double ELASTICITY = 1.0;
    /** 系数上下限。 */
    public static final double MIN_SCALE = 1.0;
    public static final double MAX_SCALE = 50.0;
    /** 每人最多贡献多少"货币"到人均里，避免单个巨富把全服物价拉爆。 */
    private static final long CAP_PER_PLAYER_CENTS = 20_000_000L;
    /** 总货币低于这个数不启用缩放（新服/测试环境）。 */
    private static final long MIN_TOTAL_CIRCULATION_CENTS = 5_000_000L;
    /** 平滑时间常数：30 分钟。目标系数变化后，实际系数按这个速度慢慢靠过去。 */
    private static final double SMOOTHING_TAU_MS = 30.0 * 60_000.0;
    /** 单次最多变化的比例（防止长时间停机后一次性跳变）。 */
    private static final double MAX_STEP_RATIO = 0.10;
    /** 平滑后的系数（跨调用保留，<0 表示还没初始化）。 */
    private static volatile double smoothedScale = -1.0;
    private static volatile long lastScaleUpdateMs = 0L;

    private MarketPricing() {
    }

    /** 当前物价系数（>=1）。 */
    public static double scale(AimcContext ctx) {
        if (ctx == null || ctx.accounts == null) {
            return 1.0;
        }
        int players = 0;
        long total = 0L;
        try {
            for (Account account : ctx.accounts.all()) {
                players++;
                long wealth = account.balanceCents() + account.bank.totalDepositsCents();
                total += Math.max(0L, Math.min(wealth, CAP_PER_PLAYER_CENTS));
            }
        } catch (RuntimeException error) {
            Log.warn("计算物价系数失败，按 1.0 处理：" + error.getMessage());
            return 1.0;
        }
        // 小服务器/测试环境不启用（账户太少或总货币太低时按 1.0，避免影响单测和新服）
        if (players <= 0 || total <= 0L || players < 3 || total < MIN_TOTAL_CIRCULATION_CENTS) {
            return 1.0;
        }
        double average = (double) total / (double) players;
        if (average <= REFERENCE_AVG_CENTS) {
            return 1.0;
        }
        double ratio = average / (double) REFERENCE_AVG_CENTS;
        double value = Math.pow(ratio, ELASTICITY);
        if (!Double.isFinite(value)) {
            return MAX_SCALE;
        }
        double target = Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
        return smooth(target);
    }

    /** 把目标系数按时间做指数平滑，避免人均货币一变动物价就跳。 */
    private static double smooth(double target) {
        long now = System.currentTimeMillis();
        double current = smoothedScale;
        if (current < 0.0) {
            smoothedScale = target;
            lastScaleUpdateMs = now;
            return target;
        }
        long elapsed = Math.max(0L, now - lastScaleUpdateMs);
        lastScaleUpdateMs = now;
        if (elapsed <= 0L) {
            return current;
        }
        double alpha = 1.0 - Math.exp(-(double) elapsed / SMOOTHING_TAU_MS);
        double next = current + (target - current) * alpha;
        double maxStep = current * MAX_STEP_RATIO;
        next = Math.max(current - maxStep, Math.min(current + maxStep, next));
        next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, next));
        smoothedScale = next;
        return next;
    }

    /** 把基础价（分）换算成当前市场价（分）。 */
    public static long priceCents(AimcContext ctx, long rawCents) {
        if (rawCents <= 0L) {
            return 1L;
        }
        double scaled = rawCents * scale(ctx);
        if (!Double.isFinite(scaled)) {
            return rawCents;
        }
        return Math.max(1L, Math.round(scaled));
    }

    /** 给玩家看的说明（/ex 价格、/ex admin stats、AI 上下文用）。 */
    public static String describe(AimcContext ctx) {
        double scale = scale(ctx);
        return String.format(java.util.Locale.ROOT, "物价系数 x%.2f（人均货币 %.2f 币，基准 %d 币）",
                scale, cappedAverageWealth(ctx) / 100.0, REFERENCE_AVG_CENTS / 100L);
    }

    /** 服务器人均货币（分，按单人上限截断，和系数算法一致）。 */
    public static double cappedAverageWealth(AimcContext ctx) {
        if (ctx == null || ctx.accounts == null) {
            return 0.0;
        }
        int players = 0;
        long total = 0L;
        for (Account account : ctx.accounts.all()) {
            players++;
            long wealth = account.balanceCents() + account.bank.totalDepositsCents();
            total += Math.max(0L, Math.min(wealth, CAP_PER_PLAYER_CENTS));
        }
        return players <= 0 ? 0.0 : (double) total / (double) players;
    }

    /** 服务器人均货币（分），统计口径：现金 + 活期/定期存款。 */
    public static double averageWealth(AimcContext ctx) {
        if (ctx == null || ctx.accounts == null) {
            return 0.0;
        }
        int players = 0;
        long total = 0L;
        for (Account account : ctx.accounts.all()) {
            players++;
            total += Math.max(0L, account.balanceCents() + account.bank.totalDepositsCents());
        }
        return players <= 0 ? 0.0 : (double) total / (double) players;
    }
}
