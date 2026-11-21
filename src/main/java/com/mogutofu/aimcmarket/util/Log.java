package com.mogutofu.aimcmarket.util;

/**
 * 极简日志门面。
 *
 * <p>数据层和引擎层不直接依赖 Forge 的 Logger，这样单测可以只跑纯 Java 逻辑。
 * 模组启动时把 sink 换成 Forge 的 logger。</p>
 */
public final class Log {
    private static volatile java.util.function.Consumer<String> infoSink =
            message -> System.out.println("[aimcmarket] " + message);
    private static volatile java.util.function.Consumer<String> warnSink =
            message -> System.err.println("[aimcmarket] WARN " + message);

    private Log() {
    }

    public static void setSinks(java.util.function.Consumer<String> info, java.util.function.Consumer<String> warn) {
        if (info != null) {
            infoSink = info;
        }
        if (warn != null) {
            warnSink = warn;
        }
    }

    public static void info(String message) {
        try {
            infoSink.accept(message);
        } catch (RuntimeException ignored) {
            // 日志失败不能影响业务
        }
    }

    public static void warn(String message) {
        try {
            warnSink.accept(message);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    public static void warn(String message, Throwable error) {
        warn(message + "：" + (error == null ? "未知错误" : error.getClass().getSimpleName() + " " + error.getMessage()));
    }
}
