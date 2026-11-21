package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.ui.Messages;
import net.minecraft.commands.CommandSourceStack;

import java.util.Locale;

/**
 * AI 代执行统一入口：把 AI 生成的 /ex 命令直接翻译成模组内部调用，不经过 Brigadier。
 *
 * <p>这样所有命令（交易/查询/银行/基金/公司/期货/期权/IPO/政府）都会由模组自己
 * 用 Messages 把结果发给玩家，不会出现"命令执行了但没有反馈"。</p>
 */
public final class DirectCommandRunner {
    private DirectCommandRunner() {
    }

    private static boolean logResult(CommandSourceStack source, Account account, String command, boolean handled) {
        if (!handled) {
            com.mogutofu.aimcmarket.util.Log.warn("[内部执行] 未识别，回退命令系统：" + command);
        }
        return handled;
    }

    /**
     * @return true = 已由内部逻辑执行（成功或失败提示都已发给玩家）
     */
    public static boolean run(CommandSourceStack source, Account account, String command) {
        String[] parts = DirectSupport.tokens(command);
        if (parts.length == 0 || source == null) {
            return false;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        com.mogutofu.aimcmarket.util.Log.info("[内部执行] 玩家=" + (account == null ? "?" : account.name)
                + " 命令=" + command);
        try {
            switch (root) {
                case "help", "balance", "pay", "work", "daily", "history", "price",
                        "buy", "sell", "buyback", "return", "news", "board", "top":
                    return logResult(source, account, command, DirectTradeCommands.handle(source, account, parts));
                case "stock", "fund", "bank":
                    return logResult(source, account, command, DirectFinanceCommands.handle(source, account, parts));
                case "company", "ipo", "gov":
                    return logResult(source, account, command, DirectCompanyCommands.handle(source, account, parts));
                case "fut", "opt":
                    return logResult(source, account, command, DirectDerivativesCommands.handle(source, account, parts));
                default:
                    return false;
            }
        } catch (RuntimeException error) {
            Messages.sendFail(source, "执行失败：" + (error.getMessage() == null ? "内部错误" : error.getMessage()));
            com.mogutofu.aimcmarket.util.Log.warn("AI 代执行内部命令失败：" + command, error);
            return true;
        }
    }
}
