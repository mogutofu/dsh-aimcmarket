package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.util.Log;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时拉现实股票行情并写进游戏股市（后台线程拉数据，主线程改数据）。
 *
 * <p>由模组在服务器启动时创建；停止时 close。行情间隔来自 real-stocks.json（默认 5 分钟）。</p>
 */
public final class RealStockRunner implements AutoCloseable {
    private final AimcContext ctx;
    private final MinecraftServer server;
    private final List<RealStockFeed.Spec> specs;
    private final int intervalMinutes;
    private ScheduledExecutorService pool;
    private volatile long lastAttemptMs;
    private volatile long lastSuccessMs;
    private volatile int lastOk;
    private volatile int lastTotal;
    private volatile String lastError = "";

    public RealStockRunner(AimcContext ctx, MinecraftServer server, RealStockFeed.Config config) {
        this.ctx = ctx;
        this.server = server;
        this.specs = new ArrayList<>(config.specs);
        this.intervalMinutes = Math.max(1, config.intervalMinutes);
    }

    /** 先确保股票存在，再开始定时拉行情（首次延迟 3 秒，避免卡启动）。 */
    public void start() {
        for (RealStockFeed.Spec spec : specs) {
            try {
                ctx.stocks.ensureRealStock(spec.code, spec.nameZh, spec.sector, spec.symbol,
                        spec.market, 10_000L);
            } catch (RuntimeException error) {
                Log.warn("创建现实股票失败：" + spec.code, error);
            }
        }
        ctx.stocks.save();
        pool = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aimcmarket-realstock");
            thread.setDaemon(true);
            return thread;
        });
        pool.scheduleWithFixedDelay(this::syncQuietly, 3L, intervalMinutes * 60L, TimeUnit.SECONDS);
        Log.info("现实股票同步已启动：" + specs.size() + " 只，每 " + intervalMinutes + " 分钟一次");
    }

    /** 立刻同步一次（异步）。 */
    public void forceSync() {
        if (pool != null && !pool.isShutdown()) {
            pool.execute(this::syncQuietly);
        }
    }

    private void syncQuietly() {
        try {
            syncOnce();
        } catch (Throwable error) {
            lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            Log.warn("现实行情同步失败：" + lastError);
        }
    }

    /** 拉一次行情并派发到主线程应用。 */
    public void syncOnce() throws IOException {
        lastAttemptMs = System.currentTimeMillis();
        List<String> symbols = new ArrayList<>();
        for (RealStockFeed.Spec spec : specs) {
            symbols.add(spec.symbol);
        }
        Map<String, RealStockFeed.Quote> quotes = RealStockFeed.fetch(symbols);
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        current.execute(() -> apply(quotes));
    }

    private void apply(Map<String, RealStockFeed.Quote> quotes) {
        long now = System.currentTimeMillis();
        int ok = 0;
        for (RealStockFeed.Spec spec : specs) {
            RealStockFeed.Quote quote = quotes.get(spec.symbol);
            if (quote == null || !(quote.price > 0.0)) {
                continue;
            }
            long cents = Math.round(quote.price * spec.multiplier);
            if (cents <= 0L) {
                continue;
            }
            ctx.stocks.applyRealQuote(spec.code, cents, quote.changeRatio(), now);
            ok++;
        }
        lastOk = ok;
        lastTotal = specs.size();
        if (ok > 0) {
            lastSuccessMs = now;
            lastError = "";
            ctx.stocks.save();
            Log.info("现实行情同步完成：" + ok + "/" + specs.size() + " 只");
        } else {
            lastError = "没有拿到任何行情";
            Log.warn("现实行情同步：一只都没拿到（接口可能限流/无网络）");
        }
    }

    /** 状态摘要（/ex admin 用）。 */
    public String summary() {
        if (specs.isEmpty()) {
            return "现实股票同步未配置";
        }
        StringBuilder builder = new StringBuilder("现实股票 ").append(specs.size()).append(" 只，每 ")
                .append(intervalMinutes).append(" 分钟同步");
        builder.append("｜上次成功：").append(lastSuccessMs <= 0L ? "还没成功过" : ago(lastSuccessMs));
        if (lastAttemptMs > 0L) {
            builder.append("（尝试 ").append(ago(lastAttemptMs)).append("，").append(lastOk).append("/")
                    .append(lastTotal).append(" 只）");
        }
        if (!lastError.isBlank()) {
            builder.append("｜最近错误：").append(lastError);
        }
        return builder.toString();
    }

    private static String ago(long timestamp) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60L) {
            return seconds + " 秒前";
        }
        if (seconds < 3600L) {
            return (seconds / 60L) + " 分钟前";
        }
        return (seconds / 3600L) + " 小时前";
    }

    public List<RealStockFeed.Spec> specs() {
        return specs;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }
}
