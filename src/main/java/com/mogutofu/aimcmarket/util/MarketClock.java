package com.mogutofu.aimcmarket.util;

/**
 * 市场时钟。
 *
 * <p>三种时间口径（DESIGN.md 里有完整说明，代码里不许混用）：</p>
 * <ul>
 *   <li><b>现实时间</b>：工资、打工冷却、签到、AI 限流用现实毫秒；</li>
 *   <li><b>市场 tick</b>：每 {@code tickMinutes} 现实分钟一次，驱动价格、利息、盯市；</li>
 *   <li><b>交易日</b>：{@code tradingDayMinutes} 现实分钟 = 1 交易日，财报/分红/到期用它。</li>
 * </ul>
 *
 * <p>时钟是纯 Java 的，测试里可以直接给假时间戳推进，不依赖服务器 tick。</p>
 */
public final class MarketClock {
    private long tickMillis;
    private long dayMillis;
    private long startMs;
    private long lastPollMs;
    private boolean started;
    private long tick;
    private long day;

    public MarketClock(int tickMinutes, int tradingDayMinutes) {
        configure(tickMinutes, tradingDayMinutes);
    }

    public void configure(int tickMinutes, int tradingDayMinutes) {
        this.tickMillis = Math.max(1, tickMinutes) * 60_000L;
        this.dayMillis = Math.max(1, tradingDayMinutes) * 60_000L;
    }

    public long tickMillis() {
        return tickMillis;
    }

    public long dayMillis() {
        return dayMillis;
    }

    public long tick() {
        return tick;
    }

    public long day() {
        return day;
    }

    public long nowMs() {
        return lastPollMs;
    }

    public long lastPollMs() {
        return lastPollMs;
    }

    public boolean started() {
        return started;
    }

    /**
     * 用当前现实时间推进时钟。
     *
     * @return 这次跨过了几个完整 tick（0 表示还没到下一个 tick）；调用方对每个 tick 做一次市场结算。
     */
    public int poll(long nowMs) {
        if (!started) {
            started = true;
            startMs = nowMs;
            lastPollMs = nowMs;
            lastTickAtMs = nowMs;
            return 0;
        }
        if (nowMs < lastPollMs) {
            // 系统时间被往回拨：不倒退，等它重新走上来。
            return 0;
        }
        lastPollMs = nowMs;
        int ticks = 0;
        while (nowMs - lastTickAtMs >= tickMillis && ticks < 24) {
            lastTickAtMs += tickMillis;
            tick++;
            ticks++;
        }
        long newDay = (nowMs - startMs) / dayMillis;
        if (newDay > day) {
            day = newDay;
        }
        return ticks;
    }

    private long lastTickAtMs;

    /** 距下一个 tick 还有多少毫秒（命令里显示倒计时用）。 */
    public long msToNextTick(long nowMs) {
        if (!started) {
            return tickMillis;
        }
        long elapsed = nowMs - lastTickAtMs;
        long remain = tickMillis - elapsed;
        return Math.max(0L, remain);
    }

    // ── 测试用 ──

    /** 测试：直接设定起点和计数。 */
    public void resetForTest(long startMs, long tick, long day) {
        this.started = true;
        this.startMs = startMs;
        this.lastPollMs = startMs;
        this.lastTickAtMs = startMs;
        this.tick = tick;
        this.day = day;
    }

    /** 测试：把时钟往后拨 n 个 tick（同时更新现实时间）。 */
    public long advanceTicksForTest(int n) {
        lastPollMs += lastPollMs - lastTickAtMs >= tickMillis ? 0 : 0; // 保持可读
        lastPollMs += tickMillis * n;
        lastTickAtMs += tickMillis * n;
        tick += n;
        return lastPollMs;
    }
}
