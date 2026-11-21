package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

/**
 * 收入引擎：在线工资 / 打工 / 每日签到。
 *
 * <p>时间口径（任务书 3.0）：收入模块全部用<b>现实时间</b>——工资间隔、打工冷却用现实毫秒，
 * "每天"用 {@link Account#dayEpoch(long)}（UTC 现实日序号，跨日自动重置）。金融模块的"交易日"
 * 和这里无关。</p>
 *
 * <p>发钱一律 {@code ledger.issue}（系统增发）并 {@link Account#recordIncome(long)}
 * （贷款额度按累计收入估算）。所有方法都不把 RuntimeException 抛给命令层。</p>
 */
public final class IncomeService {
    private IncomeService() {
    }

    // ── 打工 /ex work ──

    /**
     * 打工：冷却 {@code workCooldownMinutes} 分钟，随机 {@code [workMin, workMax]} 分，
     * {@code workDoubleChance} 概率双倍，每现实日最多 {@code workMaxPerDay} 次（0 = 不限次数）。
     */
    public static ActionResult work(AimcContext ctx, Account account) {
        try {
            if (ctx == null || account == null) {
                return ActionResult.fail("账户不存在");
            }
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            long now = ctx.nowMs();
            long cooldownMs = Math.max(0L, (long) settings.workCooldownMinutes * 60_000L);
            if (cooldownMs > 0L && account.lastWorkAtMs > 0L) {
                long elapsed = now - account.lastWorkAtMs;
                if (elapsed < cooldownMs) {
                    long waitMs = elapsed < 0L ? cooldownMs : cooldownMs - elapsed;
                    return ActionResult.fail("刚干过活，喘口气，" + durationText(waitMs) + "后再来");
                }
            }

            // 现实日重置当日次数。
            long today = Account.dayEpoch(now);
            if (account.workDayEpoch != today) {
                account.workDayEpoch = today;
                account.workCount = 0;
            }
            if (settings.workMaxPerDay > 0 && account.workCount >= settings.workMaxPerDay) {
                return ActionResult.fail("今天打工次数用完了（每天 " + settings.workMaxPerDay
                        + " 次），明天再来");
            }

            long min = Math.max(0L, settings.workMinCents);
            long max = Math.max(min, settings.workMaxCents);
            if (max <= 0L) {
                return ActionResult.fail("打工奖励还没配置，找管理员看看");
            }
            long amount;
            long span = max - min;
            if (span <= 0L) {
                amount = min;
            } else {
                // 均匀取 min..max：roll 乘以 (span+1) 后向下取整，再兜底不超过 max。
                double roll = ctx.random.nextDouble();
                long add = (long) Math.floor(roll * ((double) span + 1.0));
                if (add > span) {
                    add = span;
                }
                amount = min + add;
            }
            double chance = Double.isFinite(settings.workDoubleChance)
                    ? Cents.clampDouble(settings.workDoubleChance, 0.0, 1.0)
                    : 0.05;
            boolean doubled = ctx.random.nextDouble() < chance;
            if (doubled && amount <= Long.MAX_VALUE / 2L) {
                amount *= 2L;
            }
            if (amount <= 0L) {
                return ActionResult.fail("打工奖励还没配置，找管理员看看");
            }

            ctx.ledger.issue(account, amount, "income.work", account.name,
                    "打工收入" + (doubled ? "（双倍）" : ""));
            // 先把冷却/次数落好，再做统计：万一 recordIncome 出问题也不会重复发钱。
            account.lastWorkAtMs = now;
            account.workCount = account.workCount >= Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : account.workCount + 1;
            account.recordIncome(amount);

            StringBuilder message = new StringBuilder();
            message.append("打工赚了 ").append(Cents.format(amount)).append(" 币");
            if (doubled) {
                message.append("（运气不错，双倍！）");
            }
            if (settings.workMaxPerDay > 0) {
                int remaining = Math.max(0, settings.workMaxPerDay - account.workCount);
                message.append(remaining > 0 ? "，今天还能打 " + remaining + " 次" : "，今天的次数用完了");
            }
            message.append("，余额 ").append(Cents.format(account.balanceCents())).append(" 币");
            return ActionResult.ok(message.toString());
        } catch (RuntimeException error) {
            Log.warn("打工失败", error);
            return ActionResult.fail("打工失败：内部错误，本次没有发钱");
        }
    }

    // ── 每日签到 /ex daily ──

    /**
     * 签到：每现实日 1 次；连续签到 +1 格，断签重置为 1；
     * 金额 = {@code dailyBase + (streak-1) × dailyStep}，封顶 {@code dailyMax}。
     */
    public static ActionResult daily(AimcContext ctx, Account account) {
        try {
            if (ctx == null || account == null) {
                return ActionResult.fail("账户不存在");
            }
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            long now = ctx.nowMs();
            long today = Account.dayEpoch(now);
            if (account.lastDailyDayEpoch == today) {
                return ActionResult.fail("今天已经签过了（连续 " + Math.max(0, account.dailyStreak)
                        + " 天），明天再来");
            }
            // 昨天签过就续上，否则从头算。
            long streak = account.lastDailyDayEpoch == today - 1L
                    ? Math.max(0, account.dailyStreak) + 1L
                    : 1L;

            long base = Math.max(0L, settings.dailyBaseCents);
            long step = Math.max(0L, settings.dailyStepCents);
            long cap = Math.max(base, settings.dailyMaxCents);
            long raw;
            try {
                raw = Math.addExact(base, Math.multiplyExact(streak - 1L, step));
            } catch (ArithmeticException overflow) {
                raw = cap;
            }
            long amount = Math.min(raw, cap);
            if (amount <= 0L) {
                return ActionResult.fail("签到奖励还没配置，找管理员看看");
            }

            ctx.ledger.issue(account, amount, "income.daily", account.name,
                    "每日签到（连续 " + streak + " 天）");
            // 先记签到状态（防重复领），再累加收入统计。
            account.lastDailyDayEpoch = today;
            account.dailyStreak = (int) Math.min(Integer.MAX_VALUE, streak);
            account.recordIncome(amount);
            return ActionResult.ok("签到成功！连续签到 " + streak + " 天，获得 "
                    + Cents.format(amount) + " 币，余额 " + Cents.format(account.balanceCents()) + " 币");
        } catch (RuntimeException error) {
            Log.warn("签到失败", error);
            return ActionResult.fail("签到失败：内部错误，本次没有发钱");
        }
    }

    // ── 在线工资（每个 tick 调，必须便宜）──

    /**
     * 在线工资：每 {@code salaryMinutes} 现实分钟发 {@code salaryAmountCents} 分，
     * 每现实日最多 {@code salaryMaxPerDay} 次；{@code lastSalaryAtMs == 0} 时只初始化成 now，
     * 不补发离线时间的工资。
     *
     * <p>这个方法每 20 个服务器 tick 会被调一次（可能对所有在线玩家），所以第一段全是
     * 低成本判断：金额/间隔/次数没配好直接返回 0，时间没到直接返回 0，不发消息、不碰账本。</p>
     *
     * @return 本次实际发了多少分；没发返回 0
     */
    public static long applySalaryIfDue(AimcContext ctx, Account account) {
        try {
            if (ctx == null || account == null) {
                return 0L;
            }
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return 0L;
            }
            long amount = settings.salaryAmountCents;
            if (amount <= 0L || settings.salaryMaxPerDay <= 0 || settings.salaryMinutes <= 0) {
                return 0L;
            }
            long now = ctx.nowMs();
            if (now <= 0L) {
                return 0L;
            }
            if (account.lastSalaryAtMs == 0L) {
                // 第一次见到这个账户：从现在开始计时，离线时间不补发。
                account.lastSalaryAtMs = now;
                account.salaryDayEpoch = Account.dayEpoch(now);
                account.salaryCount = 0;
                return 0L;
            }
            long intervalMs = Math.max(60_000L, (long) settings.salaryMinutes * 60_000L);
            if (now - account.lastSalaryAtMs < intervalMs) {
                return 0L;
            }
            long today = Account.dayEpoch(now);
            if (account.salaryDayEpoch != today) {
                account.salaryDayEpoch = today;
                account.salaryCount = 0;
            }
            if (account.salaryCount >= settings.salaryMaxPerDay) {
                return 0L;
            }

            ctx.ledger.issue(account, amount, "income.salary", account.name,
                    "在线工资（每 " + settings.salaryMinutes + " 分钟）");
            // 先推进计时和当日次数，再记收入统计：即使统计失败也不会在同一时间段重复发工资。
            account.lastSalaryAtMs = now;
            account.salaryCount = account.salaryCount >= Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : account.salaryCount + 1;
            account.recordIncome(amount);
            return amount;
        } catch (RuntimeException error) {
            // 工资发失败不能让服务器 tick 崩，下一轮还会再试。
            Log.warn("发放在线工资失败", error);
            return 0L;
        }
    }

    // ── 内部工具 ──

    /** 冷却时间的中文提示：90 秒 → "2 分钟"；45 秒 → "45 秒"。 */
    private static String durationText(long millis) {
        if (millis <= 0L) {
            return "马上";
        }
        long seconds = (millis + 999L) / 1000L;
        if (seconds < 60L) {
            return seconds + " 秒";
        }
        long minutes = (seconds + 59L) / 60L;
        if (minutes < 60L) {
            return minutes + " 分钟";
        }
        return ((minutes + 59L) / 60L) + " 小时";
    }
}
