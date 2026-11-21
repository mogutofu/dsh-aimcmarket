package com.mogutofu.aimcmarket.engine;

/**
 * 引擎层统一的操作结果：成功/失败 + 给玩家看的中文消息 + 可选的广播消息。
 *
 * <p>命令层只负责把 {@code message} 加上 {@code [市场] } 前缀发给玩家，把
 * {@code broadcast}（不为空时）广播给全服。引擎不许直接碰 Minecraft 的 Component。</p>
 */
public final class ActionResult {
    public final boolean ok;
    public final String message;
    /** 需要全服广播的公告（政策/破产/IPO 之类），没有就是空字符串。 */
    public final String broadcast;
    /** 需要写进 news.json 的标题，没有就是空字符串。 */
    public final String newsHeadline;

    private ActionResult(boolean ok, String message, String broadcast, String newsHeadline) {
        this.ok = ok;
        this.message = message == null ? "" : message;
        this.broadcast = broadcast == null ? "" : broadcast;
        this.newsHeadline = newsHeadline == null ? "" : newsHeadline;
    }

    public static ActionResult ok(String message) {
        return new ActionResult(true, message, "", "");
    }

    public static ActionResult ok(String message, String broadcast, String newsHeadline) {
        return new ActionResult(true, message, broadcast, newsHeadline);
    }

    public static ActionResult fail(String message) {
        return new ActionResult(false, message, "", "");
    }

    /** 给命令层拼 tab 补全/单位转换用。 */
    @Override
    public String toString() {
        return (ok ? "OK " : "FAIL ") + message;
    }
}
