package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.IncomeService;
import com.mogutofu.aimcmarket.engine.MarketPricing;
import com.mogutofu.aimcmarket.engine.MarketTicker;
import com.mogutofu.aimcmarket.engine.TradeService;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Log;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 代执行：交易 / 查询类命令（buy / sell / buyback / return / price / balance / pay /
 * work / daily / history / news / board / top / help）的直接实现。
 *
 * <p>和 {@link MarketCommand} 的区别：这里<b>不经过 Brigadier 命令树</b>，直接把玩家
 * （或 AI 代玩家）的意图翻译成模组内部调用，结果统一用 {@link Messages} 发给玩家，
 * 所以不会出现"命令执行了、玩家却看不到反馈"的情况。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code parts} 是去掉 {@code /ex} 后按空格拆开的数组，{@code parts[0]} 是子命令；</li>
 *   <li>已处理（成功或失败提示都已经发给玩家）返回 {@code true}，不认识返回 {@code false}；</li>
 *   <li>不把异常抛给命令层：{@link #handle} 兜住 RuntimeException 并提示失败；</li>
 *   <li>物品解析优先用商品目录 {@link DirectSupport#catalogItem(String)}；目录里没有、
 *       但服务端确实注册了这个物品时，按基准价自动上市后再交易。</li>
 *   <li>所有输出走 {@link Messages}（send / sendOk / sendFail / sendInfo / sendPage），
 *       不直接用 {@code source.sendSuccess}。</li>
 * </ul>
 */
public final class DirectTradeCommands {
    /** 单笔数量上限（和 /ex 命令层的 576 保持一致）。 */
    private static final int MAX_COUNT = 576;
    /** board / news 每页条数（和 MarketCommand 的 PER_PAGE 一致）。 */
    private static final int PER_PAGE = 8;
    /** history 显示的流水条数。 */
    private static final int HISTORY_LIMIT = 12;
    /** 自动上市时的初始基准价（分）：10.00 币，之后跟着价格引擎波动。 */
    private static final long AUTO_LIST_BASE_PRICE_CENTS = 1_000L;
    /** 自动上市时的波动率 / 流动性（和 MarketCommand.ensureCatalogItem 一致）。 */
    private static final double AUTO_LIST_VOLATILITY = 0.05;
    private static final long AUTO_LIST_LIQUIDITY = 12_000L;

    /** 本类负责的子命令（不属于这里的一律返回 false，交给其它 handler）。 */
    private static final Set<String> ROOTS = Set.of(
            "help", "balance", "pay", "work", "daily", "history", "price",
            "buy", "sell", "buyback", "return", "news", "board", "top");

    private DirectTradeCommands() {
    }

    /**
     * 处理 {@code buy/sell/buyback/return/price/balance/pay/work/daily/history/news/board/top/help}。
     *
     * @param source  命令来源（AI 代执行时是玩家自己的命令源）
     * @param account 当前玩家的账户，可能为 null（没建好账户 / 不是游戏内玩家）
     * @param parts   去掉 {@code /ex} 后按空格拆开的参数
     * @return {@code true} = 已处理（成功或失败提示都已发出）；{@code false} = 不是本类负责的子命令
     */
    public static boolean handle(CommandSourceStack source, Account account, String[] parts) {
        if (source == null || parts == null || parts.length == 0 || parts[0] == null) {
            return false;
        }
        String root = parts[0].trim().toLowerCase(Locale.ROOT);
        if (!ROOTS.contains(root)) {
            return false;
        }
        AimcContext ctx = DirectSupport.ctx();
        if (ctx == null) {
            Messages.sendFail(source, "市场还没初始化（等服务器启动完成再试）");
            return true;
        }
        if (ctx.settings == null || !ctx.settings.enabled) {
            Messages.sendFail(source, "市场维护中（配置 general.enabled=false）");
            return true;
        }
        try {
            switch (root) {
                case "help":
                    return help(source, account);
                case "balance":
                    return balance(source, ctx, account);
                case "pay":
                    return pay(source, ctx, account, parts);
                case "work":
                    return work(source, ctx, account);
                case "daily":
                    return daily(source, ctx, account);
                case "history":
                    return history(source, ctx, account);
                case "price":
                    return price(source, ctx, parts);
                case "buy":
                    return buy(source, ctx, account, parts);
                case "sell":
                    return sell(source, ctx, account, parts);
                case "buyback":
                    return buyback(source, ctx, account, parts);
                case "return":
                    return returnItem(source, ctx, account, parts);
                case "news":
                    return news(source, ctx, parts);
                case "board":
                    return board(source, ctx, parts);
                case "top":
                    return top(source, ctx);
                default:
                    return false;
            }
        } catch (RuntimeException error) {
            // 兜底：任何意外都只提示失败，不把异常抛给命令层
            Log.warn("AI 代执行交易命令失败：" + String.join(" ", parts), error);
            Messages.sendFail(source, "执行失败：" + (error.getMessage() == null ? "内部错误" : error.getMessage()));
            return true;
        }
    }

    // ── help ──

    private static boolean help(CommandSourceStack source, Account account) {
        Messages.send(source, "AIMCmarket 市场先生。常用命令：");
        Messages.sendInfo(source, "/ex balance｜/ex work｜/ex daily｜/ex buy <物品> [数量]｜/ex sell <物品> [数量]");
        Messages.sendInfo(source, "/ex price <物品>｜/ex buyback <物品> [数量]｜/ex return <物品> [数量]（7 天内买的按原价退）");
        Messages.sendInfo(source, "/ex board [页]｜/ex news [页]｜/ex top｜/ex history｜/ex pay <玩家> <金额>");
        Messages.sendInfo(source, "/ex stock｜/ex fund｜/ex bank｜/ex company｜/ex ipo｜/ex fut｜/ex opt｜/ex gov");
        if (account != null) {
            Messages.sendOk(source, "余额 " + Messages.money(account.balanceCents()));
        }
        return true;
    }

    // ── balance ──

    private static boolean balance(CommandSourceStack source, AimcContext ctx, Account account) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        long stockValue = 0L;
        for (var entry : account.stocks.entrySet()) {
            var holding = entry.getValue();
            if (holding == null) {
                continue;
            }
            stockValue = safeAdd(stockValue, safeMul(ctx.stocks.priceOf(entry.getKey()), holding.shares));
        }
        long fundValue = 0L;
        for (var entry : account.funds.entrySet()) {
            var holding = entry.getValue();
            if (holding == null) {
                continue;
            }
            fundValue = safeAdd(fundValue, ctx.funds.valueOfUnits(entry.getKey(), holding.unitsMilli));
        }
        long termCents = safeSub(account.bank.totalDepositsCents(), account.bank.currentCents);
        Messages.send(source, "余额 " + Messages.money(account.balanceCents())
                + "｜活期 " + Messages.money(account.bank.currentCents)
                + "｜定期 " + Messages.money(termCents)
                + "｜贷款 " + Messages.money(account.bank.totalLoanCents()));
        Messages.sendInfo(source, "存款合计 " + Messages.money(account.bank.totalDepositsCents())
                + "｜股票市值 " + Messages.money(stockValue)
                + "｜基金市值 " + Messages.money(fundValue)
                + "｜信用分 " + Math.round(account.creditScore));
        return true;
    }

    // ── pay ──

    private static boolean pay(CommandSourceStack source, AimcContext ctx, Account account, String[] parts) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        if (parts.length < 3) {
            Messages.sendFail(source, "用法：/ex pay <玩家> <金额>，例：/ex pay Steve 12.5");
            return true;
        }
        String targetName = parts[1] == null ? "" : parts[1].trim();
        long cents = DirectSupport.amountCents(parts[2]);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex pay " + targetName + " 12.5");
            return true;
        }
        if (ctx.accounts == null || targetName.isEmpty()) {
            Messages.sendFail(source, "找不到玩家：" + targetName);
            return true;
        }
        Account to = ctx.accounts.findByName(targetName).orElse(null);
        if (to == null) {
            Messages.sendFail(source, "找不到玩家：" + targetName);
            return true;
        }
        if (to.uuid != null && to.uuid.equals(account.uuid)) {
            Messages.sendFail(source, "不能给自己转账");
            return true;
        }
        if (account.balanceCents() < cents) {
            Messages.sendFail(source, "余额不足：本次要转 " + Messages.money(cents)
                    + "，你只有 " + Messages.money(account.balanceCents()));
            return true;
        }
        try {
            ctx.ledger.transfer(account, to, cents, "player.pay", account.name, "转账");
        } catch (RuntimeException error) {
            Messages.sendFail(source, "转账失败：" + (error.getMessage() == null ? "内部错误" : error.getMessage()));
            return true;
        }
        Messages.sendOk(source, "转给 " + to.name + " " + Messages.money(cents)
                + "，余额 " + Messages.money(account.balanceCents()));
        return true;
    }

    // ── work / daily ──

    private static boolean work(CommandSourceStack source, AimcContext ctx, Account account) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        report(source, IncomeService.work(ctx, account));
        return true;
    }

    private static boolean daily(CommandSourceStack source, AimcContext ctx, Account account) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        report(source, IncomeService.daily(ctx, account));
        return true;
    }

    // ── history ──

    private static boolean history(CommandSourceStack source, AimcContext ctx, Account account) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        List<String> lines = readLedgerFor(ctx, account.name, HISTORY_LIMIT);
        if (lines.isEmpty()) {
            Messages.sendInfo(source, "还没有流水记录");
            return true;
        }
        Messages.sendPage(source, lines, 1, HISTORY_LIMIT, "/ex history");
        return true;
    }

    /** 从 ledger.jsonl 里倒着读最近 {@code limit} 条该玩家的流水（和 MarketCommand.readLedgerFor 同逻辑）。 */
    private static List<String> readLedgerFor(AimcContext ctx, String actor, int limit) {
        List<String> lines = new ArrayList<>();
        if (ctx == null || ctx.ledger == null || actor == null || actor.isEmpty() || limit <= 0) {
            return lines;
        }
        try {
            Path file = ctx.ledger.file();
            if (file == null || !Files.isRegularFile(file)) {
                return lines;
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            String needle = "\"actor\":\"" + actor + "\"";
            for (int i = all.size() - 1; i >= 0 && lines.size() < limit; i--) {
                String line = all.get(i);
                if (line == null || !line.contains(needle)) {
                    continue;
                }
                try {
                    var object = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    String kind = object.has("kind") ? object.get("kind").getAsString() : "";
                    String memo = object.has("memo") ? object.get("memo").getAsString() : "";
                    lines.add(kind + " " + memo);
                } catch (RuntimeException ignored) {
                    // 单行坏了跳过
                }
            }
        } catch (RuntimeException error) {
            Log.warn("读取流水失败（history 按空处理）", error);
        } catch (java.io.IOException error) {
            Log.warn("读取流水文件失败（history 按空处理）", error);
        }
        return lines;
    }

    // ── price ──

    private static boolean price(CommandSourceStack source, AimcContext ctx, String[] parts) {
        String itemText = DirectSupport.join(parts, 1, parts.length).trim();
        if (itemText.isEmpty()) {
            Messages.sendFail(source, "用法：/ex price <物品>，例：/ex price 铁锭");
            return true;
        }
        MarketStore.CatalogItem item = DirectSupport.catalogItem(itemText);
        if (item == null) {
            // 目录里没有：如果服务端认得这个物品，提示先用 buy 让它上市（上市时会自动定价）
            ItemResolver.Found found = ItemResolver.resolve(itemText);
            if (found != null) {
                Messages.sendFail(source, "目录里还没有「" + found.displayName() + "」（" + found.id + "）。");
                Messages.sendInfo(source, "先用 /ex buy " + found.id + " 1 让它上市定价，再查价。");
            } else {
                Messages.sendFail(source, "市场里找不到这个物品：" + itemText);
                Messages.sendInfo(source, "如果是模组物品，把物品拿在主手用 /ex price 手上的，或用 /ex id 查物品 id。");
            }
            return true;
        }
        long display = TradeService.displayPriceCents(ctx, item.id);
        Messages.send(source, displayName(item) + "（" + item.id + "）现价 " + Messages.money(display)
                + "｜买价 " + Messages.money(TradeService.effectiveAskCents(ctx, item.id))
                + "｜回收价 " + Messages.money(TradeService.effectiveBidCents(ctx, item.id)));
        Messages.sendInfo(source, "基准价 " + Messages.money(item.basePriceCents)
                + "｜波动 " + Messages.percent(item.volatility)
                + "｜流动性 " + Messages.money(item.liquidity));
        return true;
    }

    // ── buy ──

    private static boolean buy(CommandSourceStack source, AimcContext ctx, Account account, String[] parts) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "你已经下线了，交易取消");
            return true;
        }
        ItemArgs args = itemArgs(parts, 1);
        if (args.itemText.isEmpty()) {
            Messages.sendFail(source, "用法：/ex buy <物品> [数量]，例：/ex buy 铁锭 3");
            return true;
        }
        if (args.numeric && args.count <= 0) {
            Messages.sendFail(source, "数量至少要 1 个");
            return true;
        }
        int count = args.numeric ? args.count : 1;
        if (count > MAX_COUNT) {
            Messages.sendFail(source, "单笔最多 " + MAX_COUNT + " 个");
            return true;
        }
        MarketStore.CatalogItem item = ensureCatalogItem(source, ctx, args.itemText);
        if (item == null) {
            // ensureCatalogItem 已经发过失败提示
            return true;
        }
        ActionResult result = TradeService.buy(ctx, account, item.id, count);
        if (result == null || !result.ok) {
            report(source, result);
            return true;
        }
        // 记买入记录，之后才能走 /ex return 退货
        recordPurchase(ctx, account, item, count);
        int left = DirectSupport.giveItem(player, item.id, count);
        if (left > 0) {
            Messages.sendInfo(source, "背包放不下 " + left + " 个，已掉在脚下");
        }
        report(source, result);
        tickGovernment(source);
        return true;
    }

    // ── sell ──

    private static boolean sell(CommandSourceStack source, AimcContext ctx, Account account, String[] parts) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "你已经下线了，交易取消");
            return true;
        }
        ItemArgs args = itemArgs(parts, 1);
        if (args.itemText.isEmpty()) {
            Messages.sendFail(source, "用法：/ex sell <物品> [数量]，例：/ex sell 铁锭 3（不写数量=背包全部）");
            return true;
        }
        if (args.numeric && args.count <= 0) {
            Messages.sendFail(source, "数量至少要 1 个");
            return true;
        }
        if (args.numeric && args.count > MAX_COUNT) {
            Messages.sendFail(source, "单笔最多 " + MAX_COUNT + " 个");
            return true;
        }
        MarketStore.CatalogItem item = ensureCatalogItem(source, ctx, args.itemText);
        if (item == null) {
            return true;
        }
        String name = displayName(item);
        int have = DirectSupport.countItem(player, item.id);
        if (have <= 0) {
            Messages.sendFail(source, "背包里没有 " + name + "，没法卖");
            return true;
        }
        int count;
        if (args.numeric) {
            count = args.count;
            if (count > have) {
                Messages.sendFail(source, "背包里只有 " + have + " 个 " + name);
                return true;
            }
        } else {
            // 不写数量 = 背包里全卖；单笔上限内一次卖完，剩下的提示再卖一次
            count = Math.min(have, MAX_COUNT);
            if (have > count) {
                Messages.sendInfo(source, "一次最多卖 " + MAX_COUNT + " 个，本次先卖 " + count
                        + " 个，剩下的再卖一次。");
            }
        }
        ActionResult result = TradeService.sell(ctx, account, item.id, count);
        if (result == null || !result.ok) {
            report(source, result);
            return true;
        }
        DirectSupport.removeItem(player, item.id, count);
        report(source, result);
        tickGovernment(source);
        return true;
    }

    // ── buyback（按当前市场价收购背包里的物品）──

    private static boolean buyback(CommandSourceStack source, AimcContext ctx, Account account, String[] parts) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "你已经下线了，收购取消");
            return true;
        }
        ItemArgs args = itemArgs(parts, 1);
        if (args.itemText.isEmpty()) {
            Messages.sendFail(source, "用法：/ex buyback <物品> [数量]，例：/ex buyback 齿轮");
            return true;
        }
        if (args.numeric && args.count <= 0) {
            Messages.sendFail(source, "数量至少要 1 个");
            return true;
        }
        if (args.numeric && args.count > MAX_COUNT) {
            Messages.sendFail(source, "单笔最多 " + MAX_COUNT + " 个");
            return true;
        }
        MarketStore.CatalogItem item = DirectSupport.catalogItem(args.itemText);
        if (item == null) {
            Messages.sendFail(source, "市场里没有这个物品：" + args.itemText);
            Messages.sendInfo(source, "想让它上市可以先 /ex buy <物品> 1（会自动定价上市）。");
            return true;
        }
        String name = displayName(item);
        int available = DirectSupport.countItem(player, item.id);
        if (available <= 0) {
            Messages.sendFail(source, "背包里没有 " + name);
            return true;
        }
        int count = args.numeric ? Math.min(args.count, available) : Math.min(available, MAX_COUNT);
        if (count <= 0) {
            Messages.sendFail(source, "背包里没有可收购的 " + name);
            return true;
        }
        // 收购按回收价（现价 × bidRatio）结算，资源易得时压低收购价
        long unit = TradeService.effectiveBidCents(ctx, item.id);
        if (unit <= 0L) {
            unit = item.basePriceCents;
        }
        ActionResult result = TradeService.sellWithPrice(ctx, account, item.id, count, unit,
                "收购", "trade.buyback");
        if (result == null || !result.ok) {
            report(source, result);
            return true;
        }
        DirectSupport.removeItem(player, item.id, count);
        report(source, result);
        tickGovernment(source);
        return true;
    }

    // ── return（7 天内买的物品按原买入价退款）──

    private static boolean returnItem(CommandSourceStack source, AimcContext ctx, Account account, String[] parts) {
        if (account == null) {
            failAccount(source);
            return true;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "你已经下线了，退货取消");
            return true;
        }
        ItemArgs args = itemArgs(parts, 1);
        if (args.itemText.isEmpty()) {
            Messages.sendFail(source, "用法：/ex return <物品> [数量]，例：/ex return 铁锭 3（不写数量=能退的全退）");
            return true;
        }
        if (args.numeric && args.count <= 0) {
            Messages.sendFail(source, "数量至少要 1 个");
            return true;
        }
        if (args.numeric && args.count > MAX_COUNT) {
            Messages.sendFail(source, "单笔最多 " + MAX_COUNT + " 个");
            return true;
        }
        MarketStore.CatalogItem item = DirectSupport.catalogItem(args.itemText);
        if (item == null) {
            Messages.sendFail(source, "目录里没有「" + args.itemText + "」，没买过的东西不能退。");
            return true;
        }
        String name = displayName(item);
        long now = ctx.nowMs();
        int available = DirectSupport.countItem(player, item.id);
        int returnable = ctx.purchases.returnable(account.uuid, item.id, now);
        int limit = Math.min(available, returnable);
        int count = args.numeric ? Math.min(args.count, limit) : Math.min(limit, MAX_COUNT);
        if (count <= 0) {
            if (returnable <= 0) {
                Messages.sendFail(source, "没有可退货的「" + name + "」记录（只支持 7 天内在本市场买的）。");
                Messages.sendInfo(source, "想按市价卖可以走收购：/ex buyback " + name);
            } else {
                Messages.sendFail(source, "背包里只剩 " + available + " 个，可退 " + limit + " 个。");
            }
            return true;
        }
        long refund = ctx.purchases.refund(account.uuid, item.id, count, now);
        if (refund <= 0L) {
            Messages.sendFail(source, "退货记录已失效，稍后再试");
            return true;
        }
        try {
            ctx.ledger.issue(account, refund, "trade.return", account.name,
                    "退货 " + name + " ×" + count);
        } catch (RuntimeException error) {
            Messages.sendFail(source, "退货记账失败：" + (error.getMessage() == null ? "内部错误" : error.getMessage()));
            return true;
        }
        DirectSupport.removeItem(player, item.id, count);
        Messages.sendOk(source, "退货 " + name + " ×" + count + "，退款 " + Messages.money(refund)
                + "，余额 " + Messages.money(account.balanceCents()));
        tickGovernment(source);
        return true;
    }

    // ── news ──

    private static boolean news(CommandSourceStack source, AimcContext ctx, String[] parts) {
        int page = pageArg(parts, 1);
        if (page < 1) {
            Messages.sendFail(source, "页码要是 1-99 的数字，例：/ex news 2");
            return true;
        }
        List<String> lines = new ArrayList<>();
        for (var item : ctx.news.recent(80)) {
            if (item == null) {
                continue;
            }
            lines.add("[" + item.category + "] " + item.headline);
            if (item.body != null && !item.body.isBlank()) {
                lines.add("   " + item.body);
            }
        }
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex news");
        return true;
    }

    // ── board ──

    private static boolean board(CommandSourceStack source, AimcContext ctx, String[] parts) {
        int page = pageArg(parts, 1);
        if (page < 1) {
            Messages.sendFail(source, "页码要是 1-99 的数字，例：/ex board 2");
            return true;
        }
        List<String> lines = new ArrayList<>();
        for (MarketStore.CatalogItem item : ctx.market.enabledItems()) {
            if (item == null) {
                continue;
            }
            double ratio = item.basePriceCents <= 0L
                    ? 0.0
                    : (double) ctx.market.price(item.id) / (double) item.basePriceCents - 1.0;
            lines.add(displayName(item) + " " + Messages.money(TradeService.effectiveAskCents(ctx, item.id))
                    + "/" + Messages.money(TradeService.effectiveBidCents(ctx, item.id))
                    + " " + Messages.changeText(ratio));
        }
        lines.sort(Comparator.naturalOrder());
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex board");
        // 物价系数（按人均货币整体缩放，/ex price 的单品价格已经含它）
        Messages.sendInfo(source, MarketPricing.describe(ctx));
        return true;
    }

    // ── top ──

    private static boolean top(CommandSourceStack source, AimcContext ctx) {
        List<Rank> ranks = new ArrayList<>();
        for (Account one : ctx.accounts.all()) {
            if (one == null) {
                continue;
            }
            ranks.add(new Rank(netWorth(ctx, one), one.name == null ? "" : one.name));
        }
        // 资产从高到低排（数值排序，避免按字符串排出现 999 > 1000 的问题）
        ranks.sort((left, right) -> Long.compare(right.value, left.value));
        List<String> lines = new ArrayList<>();
        for (Rank rank : ranks) {
            lines.add(Messages.money(rank.value) + "  " + rank.name);
        }
        Messages.sendPage(source, lines, 1, 10, "/ex top");
        return true;
    }

    /** 资产估值：钱包 + 存款 - 贷款 + 股票市值 + 基金市值。 */
    private static long netWorth(AimcContext ctx, Account account) {
        if (ctx == null || account == null) {
            return 0L;
        }
        long value = safeAdd(account.balanceCents(), account.bank.totalDepositsCents());
        value = safeSub(value, account.bank.totalLoanCents());
        for (var entry : account.stocks.entrySet()) {
            var holding = entry.getValue();
            if (holding == null) {
                continue;
            }
            value = safeAdd(value, safeMul(ctx.stocks.priceOf(entry.getKey()), holding.shares));
        }
        for (var entry : account.funds.entrySet()) {
            var holding = entry.getValue();
            if (holding == null) {
                continue;
            }
            value = safeAdd(value, ctx.funds.valueOfUnits(entry.getKey(), holding.unitsMilli));
        }
        return value;
    }

    /** 排行用的一行。 */
    private static final class Rank {
        final long value;
        final String name;

        Rank(long value, String name) {
            this.value = value;
            this.name = name;
        }
    }

    // ── 物品解析 / 自动上市 ──

    /**
     * 目录里没有这个物品时，看服务端是否注册过：注册过就按基准价自动上市（和
     * MarketCommand.ensureCatalogItem 的本地分支一致，只是这里不调用 AI 估价）。
     *
     * @return 可交易的目录项；解析失败返回 null（失败提示已经发出）
     */
    private static MarketStore.CatalogItem ensureCatalogItem(CommandSourceStack source, AimcContext ctx, String text) {
        MarketStore.CatalogItem item = DirectSupport.catalogItem(text);
        if (item != null) {
            return item;
        }
        ItemResolver.Found found = ItemResolver.resolve(text);
        if (found == null) {
            Messages.sendFail(source, "市场里找不到这个物品：" + text);
            Messages.sendInfo(source, "如果是模组物品，把物品拿在主手用 /ex buy 手上的 1；"
                    + "或用 /ex id 查物品 id 再 /ex buy <id> 1。");
            return null;
        }
        if (ctx.market == null) {
            Messages.sendFail(source, "市场数据还没加载好，稍后再试");
            return null;
        }
        try {
            MarketStore.CatalogItem created = ctx.market.addItem(found.id, found.displayName(),
                    new ArrayList<>(Collections.singletonList(found.nameEn == null ? "" : found.nameEn)),
                    AUTO_LIST_BASE_PRICE_CENTS, AUTO_LIST_VOLATILITY, AUTO_LIST_LIQUIDITY);
            if (created == null) {
                Messages.sendFail(source, "自动上市失败：" + found.id);
                return null;
            }
            Messages.sendInfo(source, "目录里没有「" + displayName(created) + "」，已按基准价 "
                    + Messages.money(created.basePriceCents) + " 自动上市，之后价格会跟着行情走。");
            return created;
        } catch (RuntimeException error) {
            Log.warn("自动上市失败：" + found.id, error);
            Messages.sendFail(source, "自动上市失败：" + (error.getMessage() == null ? "内部错误" : error.getMessage()));
            return null;
        }
    }

    /** 记一条买入记录（退货用）；记失败只写日志，不影响本次成交。 */
    private static void recordPurchase(AimcContext ctx, Account account, MarketStore.CatalogItem item, int count) {
        if (ctx == null || ctx.purchases == null || account == null || item == null || count <= 0) {
            return;
        }
        try {
            ctx.purchases.record(account.uuid, item.id, count,
                    TradeService.effectiveAskCents(ctx, item.id), ctx.nowMs());
        } catch (RuntimeException error) {
            Log.warn("记录买入失败（这单可能无法退货）", error);
        }
    }

    // ── 参数解析 ──

    /** {@code "<物品...> [数量]"} 的解析结果。 */
    private static final class ItemArgs {
        final String itemText;
        /** 只在 {@link #numeric} 为 true 时有意义；0 / 负数表示玩家写错了。 */
        final int count;
        /** 最后一段是不是数字。 */
        final boolean numeric;

        ItemArgs(String itemText, int count, boolean numeric) {
            this.itemText = itemText == null ? "" : itemText;
            this.count = count;
            this.numeric = numeric;
        }
    }

    /**
     * 把 {@code parts[from..]} 拆成"物品名 + 可选数量"。
     *
     * <p>只有最后一段是纯数字时才当数量，物品名本身带数字（例如"64K存储组件"）不受影响。</p>
     */
    private static ItemArgs itemArgs(String[] parts, int from) {
        String body = DirectSupport.join(parts, from, parts == null ? 0 : parts.length).trim();
        if (body.isEmpty()) {
            return new ItemArgs("", 0, false);
        }
        int space = body.lastIndexOf(' ');
        if (space > 0 && space < body.length() - 1) {
            String last = body.substring(space + 1).trim();
            Integer parsed = parseCount(last);
            if (parsed != null) {
                return new ItemArgs(body.substring(0, space).trim(), parsed, true);
            }
        }
        return new ItemArgs(body, 0, false);
    }

    /** 纯数字（可带正负号）解析成 int；超出 int 范围的按边界值算，非数字返回 null。 */
    private static Integer parseCount(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        int start = (value.startsWith("-") || value.startsWith("+")) ? 1 : 0;
        if (start >= value.length()) {
            return null;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (parsed < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return Integer.valueOf((int) parsed);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 页码：没传返回 1；传了但不是 1-99 的数字返回 -1。 */
    private static int pageArg(String[] parts, int index) {
        if (parts == null || parts.length <= index || parts[index] == null) {
            return 1;
        }
        String text = parts[index].trim();
        if (text.isEmpty()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(text);
            return page >= 1 && page <= 99 ? page : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    // ── 小工具 ──

    /** 目录项显示名（中文名优先）。 */
    private static String displayName(MarketStore.CatalogItem item) {
        if (item == null) {
            return "未知物品";
        }
        return item.nameZh == null || item.nameZh.isBlank() ? item.id : item.nameZh;
    }

    /** 把引擎返回的 {@link ActionResult} 发给玩家（带广播）。 */
    private static void report(CommandSourceStack source, ActionResult result) {
        if (result == null) {
            Messages.sendFail(source, "操作失败");
            return;
        }
        if (result.ok) {
            Messages.sendOk(source, result.message);
            if (!result.broadcast.isEmpty()) {
                Messages.broadcast(source, result.broadcast, true);
            }
        } else {
            Messages.sendFail(source, result.message);
        }
    }

    private static void failAccount(CommandSourceStack source) {
        Messages.sendFail(source, "账户还没建好，重进一下服务器");
    }

    /**
     * 成交后让政府引擎重新算一次宏观指标（和手动 /ex 命令的节奏一致）。
     *
     * <p>拿不到服务器（单测 / 已停服）就直接跳过，绝不影响已经完成的交易。</p>
     */
    private static void tickGovernment(CommandSourceStack source) {
        AimcContext ctx = DirectSupport.ctx();
        if (ctx == null || source == null) {
            return;
        }
        try {
            if (source.getServer() == null) {
                return;
            }
            source.getServer().execute(() -> MarketTicker.triggerGovernment(ctx, System.currentTimeMillis()));
        } catch (RuntimeException ignored) {
            // 不是真实的服务器命令源，忽略即可
        }
    }

    /** 加法，溢出时保持原值（只用于展示统计，不参与账本）。 */
    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return left;
        }
    }

    /** 减法，溢出时保持原值（只用于展示统计）。 */
    private static long safeSub(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return left;
        }
    }

    /** 乘法，溢出时按 0 处理（只用于展示统计）。 */
    private static long safeMul(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            return 0L;
        }
    }
}
