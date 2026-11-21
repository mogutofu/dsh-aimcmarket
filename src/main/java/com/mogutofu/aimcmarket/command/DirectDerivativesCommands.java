package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.FuturesStore;
import com.mogutofu.aimcmarket.data.OptionsStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.FuturesEngine;
import com.mogutofu.aimcmarket.engine.MarketTicker;
import com.mogutofu.aimcmarket.engine.OptionsEngine;
import com.mogutofu.aimcmarket.engine.TradeService;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Log;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 代执行：期货（fut）/ 期权（opt）命令直接调模组引擎，不经过 Brigadier 命令树。
 *
 * <p>为什么单独有这一层：AI 生成的命令如果交给 Minecraft 命令系统嵌套执行，输出常常被
 * {@code sendCommandFeedback} 吞掉，玩家只看到"执行完成"却看不到结果。这里把
 * {@code fut ...} / {@code opt ...} 直接解析成 {@link FuturesEngine} / {@link OptionsEngine}
 * 的调用，结果统一走 {@link Messages} 发给玩家，保证"执行了就一定有反馈"。</p>
 *
 * <p>口径与 {@code /ex fut}、{@code /ex opt} 命令树完全一致：只操作传入的 {@code account}
 * 自己的账户，标的/价格/手数/张数/到期/费用/保证金全部由引擎校验，本类不做任何 OP 操作，
 * 也不直接改余额（资金变动一律走引擎里的记账腿）。</p>
 */
public final class DirectDerivativesCommands {
    /** 列表分页大小（与 /ex fut list、/ex opt list 保持一致）。 */
    private static final int PAGE_SIZE = 8;
    /** 报价没给到期天数时按 7 个交易日算（和命令树的默认口径一致）。 */
    private static final int DEFAULT_EXPIRY_DAYS = 7;

    /** fut 家族的用法提示（参数不全/子命令不认识时复用）。 */
    private static final String FUT_USAGE = "用法：/ex fut list｜/ex fut positions｜"
            + "/ex fut quote <标的> <天数>｜/ex fut long|short <标的> <手数> <天数>｜"
            + "/ex fut close <id>｜/ex fut margin add|withdraw <id> <金额>";
    /** opt 家族的用法提示。 */
    private static final String OPT_USAGE = "用法：/ex opt list｜/ex opt positions｜"
            + "/ex opt quote <代码> <行权价> <天数>｜"
            + "/ex opt buy|sell <call|put> <代码> <行权价> <天数> <张数>｜/ex opt close <id>";

    private DirectDerivativesCommands() {
    }

    /**
     * 处理 "fut" / "opt" 子命令。
     *
     * @param source  命令来源（一定是玩家，结果用它发给玩家）
     * @param account 发起人的账户（可以是 null，读写类命令会提示账户没建好）
     * @param parts   去掉 /ex 后按空格拆好的 token，parts[0] 是 fut 或 opt
     * @return true = 已经处理完（成功或失败提示都已发给玩家）；false = 不是期货/期权命令
     */
    public static boolean handle(CommandSourceStack source, Account account, String[] parts) {
        if (source == null || parts == null || parts.length == 0) {
            return false;
        }
        String root = lower(parts[0]);
        if (!"fut".equals(root) && !"opt".equals(root)) {
            return false;
        }
        String sub = parts.length > 1 ? lower(parts[1]) : "";
        try {
            if ("fut".equals(root)) {
                return handleFutures(source, account, sub, parts);
            }
            return handleOptions(source, account, sub, parts);
        } catch (RuntimeException error) {
            // 引擎层大部分异常已经自己吃掉了，这里再兜一层：AI 代执行无论如何都要给玩家一句话
            Log.warn("AI 代执行期货/期权命令失败：" + DirectSupport.join(parts, 0, parts.length), error);
            Messages.sendFail(source, "执行失败：" + brief(error));
            return true;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 期货：fut list / positions / quote / long|short / close / margin
    // ══════════════════════════════════════════════════════════════

    private static boolean handleFutures(CommandSourceStack source, Account account, String sub, String[] parts) {
        AimcContext ctx = ready(source);
        if (ctx == null) {
            return true;
        }
        switch (sub) {
            case "":
            case "list":
                // /ex fut 和 /ex fut list 等价：列出 5 个标的的现货价
                futuresList(source, ctx, pageOf(parts, 2));
                return true;
            case "help":
                Messages.sendInfo(source, FUT_USAGE);
                return true;
            case "positions": {
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                Messages.sendLines(source, FuturesEngine.positionsText(ctx, holder));
                return true;
            }
            case "quote": {
                String symbol = arg(parts, 2);
                if (symbol.isEmpty()) {
                    Messages.sendFail(source, "用法：/ex fut quote <标的> <天数>"
                            + "（标的：IRON/GOLD/DIAMOND/EMERALD/WHEAT，天数 7/14/30）");
                    return true;
                }
                int days = DEFAULT_EXPIRY_DAYS;
                String daysText = arg(parts, 3);
                if (!daysText.isEmpty()) {
                    Integer parsed = parseInteger(daysText);
                    if (parsed == null) {
                        Messages.sendFail(source, "到期天数要填数字（7 / 14 / 30），你给的是：" + daysText);
                        return true;
                    }
                    days = parsed.intValue();
                }
                sendResult(source, FuturesEngine.quote(ctx, symbol, days));
                return true;
            }
            case "long":
            case "short": {
                boolean longPosition = "long".equals(sub);
                if (parts.length < 5) {
                    Messages.sendFail(source, "用法：/ex fut " + sub + " <标的> <手数> <天数>（例：/ex fut "
                            + sub + " IRON 1 7）");
                    return true;
                }
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                String symbol = parts[2];
                Long lots = parseLong(parts[3]);
                if (lots == null || lots.longValue() <= 0L) {
                    Messages.sendFail(source, "手数要填正整数（1 手 = " + FuturesStore.LOT_SIZE
                            + " 个），你给的是：" + parts[3]);
                    return true;
                }
                Integer days = parseInteger(parts[4]);
                if (days == null) {
                    Messages.sendFail(source, "到期天数要填 7 / 14 / 30，你给的是：" + parts[4]);
                    return true;
                }
                sendResult(source, FuturesEngine.open(ctx, holder, symbol, longPosition,
                        lots.longValue(), days.intValue()));
                tickGovernment(source);
                return true;
            }
            case "close": {
                String id = arg(parts, 2);
                if (id.isEmpty()) {
                    Messages.sendFail(source, "用法：/ex fut close <持仓编号>（编号用 /ex fut positions 查）");
                    return true;
                }
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                sendResult(source, FuturesEngine.close(ctx, holder, id));
                return true;
            }
            case "margin": {
                if (parts.length < 5) {
                    Messages.sendFail(source, "用法：/ex fut margin add|withdraw <id> <金额>"
                            + "（例：/ex fut margin add f-00001 100）");
                    return true;
                }
                String action = lower(parts[2]);
                boolean add;
                if ("add".equals(action)) {
                    add = true;
                } else if ("withdraw".equals(action)) {
                    add = false;
                } else {
                    Messages.sendFail(source, "margin 只支持 add（追加）或 withdraw（提取），你给的是：" + parts[2]);
                    return true;
                }
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                long amountCents = DirectSupport.amountCents(parts[4]);
                if (amountCents <= 0L) {
                    Messages.sendFail(source, "金额格式不对（例：100 或 100.50），你给的是：" + parts[4]);
                    return true;
                }
                sendResult(source, add
                        ? FuturesEngine.addMargin(ctx, holder, parts[3], amountCents)
                        : FuturesEngine.withdrawMargin(ctx, holder, parts[3], amountCents));
                return true;
            }
            default:
                Messages.sendFail(source, "不认识的期货子命令：" + arg(parts, 1) + "。" + FUT_USAGE);
                return true;
        }
    }

    /** /ex fut list：列出 5 个标的的现货价（分页）。 */
    private static void futuresList(CommandSourceStack source, AimcContext ctx, int page) {
        List<String> lines = new ArrayList<>();
        for (var entry : FuturesStore.SYMBOLS.entrySet()) {
            lines.add(entry.getKey() + " 现货 "
                    + Messages.money(TradeService.effectiveAskCents(ctx, entry.getValue()))
                    + "（1 手 = " + FuturesStore.LOT_SIZE + " 个）");
        }
        Messages.sendPage(source, lines, page, PAGE_SIZE, "/ex fut list");
        Messages.sendInfo(source, "到期可选：7 / 14 / 30 交易日｜/ex fut quote <标的> <天数> 看合约价");
    }

    // ══════════════════════════════════════════════════════════════
    // 期权：opt list / positions / quote / buy|sell / close
    // ══════════════════════════════════════════════════════════════

    private static boolean handleOptions(CommandSourceStack source, Account account, String sub, String[] parts) {
        AimcContext ctx = ready(source);
        if (ctx == null) {
            return true;
        }
        switch (sub) {
            case "":
            case "list":
                // /ex opt 和 /ex opt list 等价：列出可做期权的股票标的
                optionsList(source, ctx, pageOf(parts, 2));
                return true;
            case "help":
                Messages.sendInfo(source, OPT_USAGE);
                return true;
            case "positions": {
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                Messages.sendLines(source, OptionsEngine.positionsText(ctx, holder));
                return true;
            }
            case "quote": {
                if (parts.length < 4) {
                    Messages.sendFail(source, "用法：/ex opt quote <代码> <行权价> <天数>"
                            + "（例：/ex opt quote BLK 126 7；行权价单位是分，120 币填 12000）");
                    return true;
                }
                long strikeCents = DirectSupport.amountCents(parts[3]);
                if (strikeCents <= 0L) {
                    Messages.sendFail(source, "行权价格式不对（单位：分，例如 120 币填 12000），你给的是：" + parts[3]);
                    return true;
                }
                int days = DEFAULT_EXPIRY_DAYS;
                String daysText = arg(parts, 4);
                if (!daysText.isEmpty()) {
                    Integer parsed = parseInteger(daysText);
                    if (parsed == null) {
                        Messages.sendFail(source, "到期天数要填数字（7 / 14 / 30），你给的是：" + daysText);
                        return true;
                    }
                    days = parsed.intValue();
                }
                sendResult(source, OptionsEngine.quote(ctx, parts[2], strikeCents, days));
                return true;
            }
            case "buy":
            case "sell": {
                boolean writer = "sell".equals(sub);
                if (parts.length < 7) {
                    Messages.sendFail(source, "用法：/ex opt " + sub
                            + " <call|put> <代码> <行权价> <天数> <张数>（例：/ex opt " + sub
                            + " call BLK 126 7 1）");
                    return true;
                }
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                String callOrPut = parts[2];
                String code = parts[3];
                long strikeCents = DirectSupport.amountCents(parts[4]);
                if (strikeCents <= 0L) {
                    Messages.sendFail(source, "行权价格式不对（单位：分，例如 120 币填 12000），你给的是：" + parts[4]);
                    return true;
                }
                Integer days = parseInteger(parts[5]);
                if (days == null) {
                    Messages.sendFail(source, "到期天数要填 7 / 14 / 30，你给的是：" + parts[5]);
                    return true;
                }
                Long contracts = parseLong(parts[6]);
                if (contracts == null || contracts.longValue() <= 0L) {
                    Messages.sendFail(source, "张数要填正整数（1 张 = " + OptionsStore.CONTRACT_SIZE
                            + " 股），你给的是：" + parts[6]);
                    return true;
                }
                sendResult(source, OptionsEngine.open(ctx, holder, writer, callOrPut, code,
                        strikeCents, days.intValue(), contracts.longValue()));
                tickGovernment(source);
                return true;
            }
            case "close": {
                String id = arg(parts, 2);
                if (id.isEmpty()) {
                    Messages.sendFail(source, "用法：/ex opt close <持仓编号>（编号用 /ex opt positions 查）");
                    return true;
                }
                Account holder = requireAccount(source, account);
                if (holder == null) {
                    return true;
                }
                sendResult(source, OptionsEngine.close(ctx, holder, id));
                return true;
            }
            default:
                Messages.sendFail(source, "不认识的期权子命令：" + arg(parts, 1) + "。" + OPT_USAGE);
                return true;
        }
    }

    /** /ex opt list：列出可做期权的股票（分页）。 */
    private static void optionsList(CommandSourceStack source, AimcContext ctx, int page) {
        List<String> lines = new ArrayList<>();
        for (var stock : ctx.stocks.all()) {
            lines.add(stock.code + " " + stock.name + " 现价 " + Messages.money(stock.priceCents)
                    + "（期权标的：call/put，1 张 = " + OptionsStore.CONTRACT_SIZE + " 股）");
        }
        Messages.sendPage(source, lines, page, PAGE_SIZE, "/ex opt list");
    }

    // ══════════════════════════════════════════════════════════════
    // 公共小工具
    // ══════════════════════════════════════════════════════════════

    /** 市场上下文；没加载好/维护中时直接给玩家提示并返回 null（对齐命令树的 ready 口径）。 */
    private static AimcContext ready(CommandSourceStack source) {
        AimcContext ctx = DirectSupport.ctx();
        if (ctx == null) {
            Messages.sendFail(source, "市场还没初始化（等服务器启动完成再试）");
            return null;
        }
        if (ctx.settings == null) {
            Messages.sendFail(source, "市场配置还没加载好，稍后再试");
            return null;
        }
        if (!ctx.settings.enabled) {
            Messages.sendFail(source, "市场维护中（配置 general.enabled=false）");
            return null;
        }
        return ctx;
    }

    /** 需要游戏内账户；为空时提示并返回 null。 */
    private static Account requireAccount(CommandSourceStack source, Account account) {
        if (account != null) {
            return account;
        }
        Messages.sendFail(source, "账户还没建好，重进一下服务器");
        return null;
    }

    /** 把引擎结果按成功/失败发给玩家；引擎带回广播时一并广播（期货/期权目前一般不广播）。 */
    private static void sendResult(CommandSourceStack source, ActionResult result) {
        if (result == null) {
            Messages.sendFail(source, "操作失败：引擎没有返回结果");
            return;
        }
        if (result.ok) {
            Messages.sendOk(source, result.message);
        } else {
            Messages.sendFail(source, result.message);
        }
        if (result.ok && !result.broadcast.isEmpty()) {
            Messages.broadcast(source, result.broadcast, true);
        }
    }

    /**
     * 开仓后让政府引擎重算一次宏观指标（和手动 /ex fut、/ex opt 开仓的节奏一致）。
     *
     * <p>拿不到服务器（单测/已停服）就直接跳过，绝不影响已经完成的交易。</p>
     */
    private static void tickGovernment(CommandSourceStack source) {
        AimcContext ctx = DirectSupport.ctx();
        if (ctx == null || source == null) {
            return;
        }
        try {
            source.getServer().execute(() -> MarketTicker.triggerGovernment(ctx, System.currentTimeMillis()));
        } catch (RuntimeException ignored) {
            // 不是真实的服务器命令源，忽略即可
        }
    }

    /** 取第 index 个 token；越界返回空串（方便判空，不用到处判断数组长度）。 */
    private static String arg(String[] parts, int index) {
        if (parts == null || index < 0 || index >= parts.length || parts[index] == null) {
            return "";
        }
        return parts[index].trim();
    }

    /** 分页页码：缺省/非法/小于 1 一律当第 1 页（列表是只读的，宽容一点）。 */
    private static int pageOf(String[] parts, int index) {
        Integer parsed = parseInteger(arg(parts, index));
        if (parsed == null || parsed.intValue() <= 0) {
            return 1;
        }
        return parsed.intValue();
    }

    /** 小写化（null 安全）。 */
    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /** 解析整数；非法返回 null（容忍千分位逗号）。 */
    private static Integer parseInteger(String text) {
        String value = text == null ? "" : text.trim().replace(",", "");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 解析长整数（手数/张数）；非法返回 null（容忍千分位逗号）。 */
    private static Long parseLong(String text) {
        String value = text == null ? "" : text.trim().replace(",", "");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 异常信息压成一行给玩家看。 */
    private static String brief(RuntimeException error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "内部错误，请稍后再试" : message;
    }
}
