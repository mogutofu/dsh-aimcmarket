package com.mogutofu.aimcmarket.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 聊天消息工具：统一前缀、颜色、可点击按钮、分页。
 *
 * <p>所有给玩家看的中文都在命令层/引擎层拼好，这里只负责套 Component。
 * 前缀：普通消息 {@code [市场] }，政策公告 {@code [政府] }。</p>
 */
public final class Messages {
    public static final String PREFIX = "[市场] ";
    public static final String GOV_PREFIX = "[政府] ";

    private Messages() {
    }

    // ── 构造 ──

    public static MutableComponent market(String text) {
        return Component.literal(PREFIX).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text == null ? "" : text).withStyle(ChatFormatting.WHITE));
    }

    public static MutableComponent gov(String text) {
        return Component.literal(GOV_PREFIX).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text == null ? "" : text).withStyle(ChatFormatting.WHITE));
    }

    public static MutableComponent ok(String text) {
        return market(text).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent fail(String text) {
        return market(text).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent info(String text) {
        return market(text).withStyle(ChatFormatting.GRAY);
    }

    /** 可点击执行的按钮，例如 [买入] → /ex buy iron 3。 */
    public static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal(label)
                .withStyle(style -> style
                        .withColor(color)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击执行 " + command))));
    }

    // ── 发送 ──

    /** 统一投递：玩家直接发系统消息（不依赖 sendCommandFeedback/命令反馈设置），控制台/RCON 走 sendSuccess。 */
    private static void deliver(CommandSourceStack source, Component component) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player != null) {
            player.sendSystemMessage(component);
            return;
        }
        source.sendSuccess(() -> component, false);
    }

    /** 带 [市场] 前缀的普通消息。 */
    public static void send(CommandSourceStack source, String text) {
        deliver(source, market(text));
    }

    public static void send(CommandSourceStack source, Component component) {
        deliver(source, component);
    }

    public static void sendOk(CommandSourceStack source, String text) {
        deliver(source, ok(text));
    }

    public static void sendFail(CommandSourceStack source, String text) {
        deliver(source, fail(text));
    }

    public static void sendInfo(CommandSourceStack source, String text) {
        deliver(source, info(text));
    }

    public static void sendGov(CommandSourceStack source, String text) {
        deliver(source, gov(text));
    }

    /** 多行文本（引擎层用 \n 分隔）。 */
    public static void sendLines(CommandSourceStack source, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                send(source, line);
            }
        }
    }

    public static void sendRaw(CommandSourceStack source, Component component) {
        deliver(source, component);
    }

    /** 全服广播（政策/破产/IPO）。 */
    public static void broadcast(CommandSourceStack source, String text, boolean government) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        Component component = government ? gov(text) : market(text);
        server.getPlayerList().broadcastSystemMessage(component, false);
    }

    public static void broadcast(MinecraftServer server, String text, boolean government) {
        if (server == null) {
            return;
        }
        Component component = government ? gov(text) : market(text);
        server.getPlayerList().broadcastSystemMessage(component, false);
    }

    /** 只发给某个在线玩家。 */
    public static void tell(MinecraftServer server, String playerName, String text) {
        if (server == null || playerName == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player != null) {
            player.sendSystemMessage(market(text));
        }
    }

    // ── 分页 ──

    /**
     * 发送分页文本。
     *
     * @param lines       每行一条
     * @param page        从 1 开始
     * @param perPage     每页行数
     * @param commandBase 翻页命令前缀，例如 {@code /ex board}
     */
    public static void sendPage(CommandSourceStack source, List<String> lines, int page, int perPage, String commandBase) {
        if (lines == null || lines.isEmpty()) {
            sendInfo(source, "（没有内容）");
            return;
        }
        int pages = (lines.size() + perPage - 1) / perPage;
        int current = Math.max(1, Math.min(page, pages));
        int from = (current - 1) * perPage;
        int to = Math.min(lines.size(), from + perPage);
        for (int i = from; i < to; i++) {
            send(source, lines.get(i));
        }
        MutableComponent nav = Component.literal("第 " + current + "/" + pages + " 页 ").withStyle(ChatFormatting.DARK_GRAY);
        if (current > 1) {
            nav.append(button("[上一页]", commandBase + " " + (current - 1), ChatFormatting.YELLOW)).append(Component.literal(" "));
        }
        if (current < pages) {
            nav.append(button("[下一页]", commandBase + " " + (current + 1), ChatFormatting.YELLOW));
        }
        sendRaw(source, nav);
    }

    // ── 格式化 ──

    /** 12.34 币 */
    public static String money(long cents) {
        return com.mogutofu.aimcmarket.util.Cents.format(cents) + " 币";
    }

    /** 涨跌上色：+x.xx% 绿，-x.xx% 红。 */
    public static MutableComponent change(double ratio) {
        String text = String.format(java.util.Locale.ROOT, "%+.2f%%", ratio * 100.0);
        ChatFormatting color = ratio > 0.0001 ? ChatFormatting.GREEN : (ratio < -0.0001 ? ChatFormatting.RED : ChatFormatting.GRAY);
        return Component.literal(text).withStyle(color);
    }

    /** 涨跌文本（不带色）。 */
    public static String changeText(double ratio) {
        return String.format(java.util.Locale.ROOT, "%+.2f%%", ratio * 100.0);
    }

    public static String percent(double ratio) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100.0);
    }
}
