package com.mogutofu.aimcmarket.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.ai.AiJson;
import com.mogutofu.aimcmarket.ai.LocalParser;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.FundStore;
import com.mogutofu.aimcmarket.data.FuturesStore;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.data.OptionsStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.CompanyEngine;
import com.mogutofu.aimcmarket.engine.FinanceService;
import com.mogutofu.aimcmarket.engine.FuturesEngine;
import com.mogutofu.aimcmarket.engine.GovernmentEngine;
import com.mogutofu.aimcmarket.engine.IpoEngine;
import com.mogutofu.aimcmarket.engine.MarketTicker;
import com.mogutofu.aimcmarket.engine.OptionsEngine;
import com.mogutofu.aimcmarket.engine.TradeService;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Cents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /ex}、{@code /exchange}、{@code /market} 命令树。
 *
 * <p>结构化子命令永远不依赖 AI；{@code /ex <自然语言>} 先本地解析，失败才异步问 AI。
 * 回复前缀 {@code [市场]}，政策广播 {@code [政府]}。</p>
 */
public final class MarketCommand {
    private static final int PER_PAGE = 8;

    private MarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String rootName : new String[]{"ex", "exchange", "market"}) {
            dispatcher.register(build(rootName));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String rootName) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(rootName).executes(MarketCommand::rootHelp);
        root.then(Commands.literal("help").executes(MarketCommand::rootHelp));
        root.then(Commands.literal("balance").executes(MarketCommand::balance));
        root.then(Commands.literal("pay")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(context -> pay(context, StringArgumentType.getString(context, "player"),
                                        StringArgumentType.getString(context, "amount"))))));
        root.then(Commands.literal("work").executes(MarketCommand::work));
        root.then(Commands.literal("daily").executes(MarketCommand::daily));
        root.then(Commands.literal("buy")
                .then(Commands.argument("item", StringArgumentType.string()).suggests(itemNames())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 576))
                                .executes(MarketCommand::buy))));
        root.then(Commands.literal("sell")
                .then(Commands.argument("item", StringArgumentType.string()).suggests(itemNames())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 576))
                                .executes(MarketCommand::sell))));
        root.then(Commands.literal("price")
                .then(Commands.argument("item", StringArgumentType.string()).suggests(itemNames())
                        .executes(MarketCommand::price)));
        root.then(Commands.literal("news")
                .executes(context -> news(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 99))
                        .executes(context -> news(context, IntegerArgumentType.getInteger(context, "page")))));
        root.then(Commands.literal("board")
                .executes(context -> board(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 99))
                        .executes(context -> board(context, IntegerArgumentType.getInteger(context, "page")))));
        root.then(Commands.literal("top").executes(MarketCommand::top));
        root.then(Commands.literal("history").executes(MarketCommand::history));
        root.then(Commands.literal("stock")
                .executes(context -> stockList(context, 1))
                .then(Commands.literal("list").executes(context -> stockList(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 99))
                        .executes(context -> stockList(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(stockCodes())
                                .then(Commands.argument("shares", LongArgumentType.longArg(1, 10_000_000))
                                        .executes(MarketCommand::stockBuy))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(stockCodes())
                                .then(Commands.argument("shares", LongArgumentType.longArg(1, 10_000_000))
                                        .executes(MarketCommand::stockSell))))
                .then(Commands.literal("info")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(stockCodes())
                                .executes(MarketCommand::stockInfo))));
        root.then(Commands.literal("fund")
                .executes(context -> fundList(context, 1))
                .then(Commands.literal("list").executes(context -> fundList(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 99))
                        .executes(context -> fundList(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(fundCodes())
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> fundBuy(context, StringArgumentType.getString(context, "code"),
                                                StringArgumentType.getString(context, "amount"))))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(fundCodes())
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> fundSell(context, StringArgumentType.getString(context, "code"),
                                                StringArgumentType.getString(context, "amount"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(fundCodes())
                                .executes(context -> fundInfo(context, StringArgumentType.getString(context, "code"))))));
        root.then(Commands.literal("bank")
                .executes(MarketCommand::bankInfo)
                .then(Commands.literal("balance").executes(MarketCommand::bankInfo))
                .then(Commands.literal("info").executes(MarketCommand::bankInfo))
                .then(Commands.literal("deposit")
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(context -> bankDeposit(context, StringArgumentType.getString(context, "amount")))))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(context -> bankWithdraw(context, StringArgumentType.getString(context, "amount")))))
                .then(Commands.literal("term")
                        .then(Commands.argument("days", IntegerArgumentType.integer(7, 30))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> bankTerm(context, IntegerArgumentType.getInteger(context, "days"),
                                                StringArgumentType.getString(context, "amount"))))))
                .then(Commands.literal("loan")
                        .then(Commands.literal("take").executes(context -> bankLoan(context, null))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> bankLoan(context, StringArgumentType.getString(context, "amount")))))
                        .then(Commands.literal("repay")
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> bankRepay(context, StringArgumentType.getString(context, "amount")))))));
        root.then(Commands.literal("company")
                .executes(MarketCommand::companyList)
                .then(Commands.literal("list").executes(MarketCommand::companyList))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("sector", StringArgumentType.word()).suggests(sectorNames())
                                        .executes(context -> companyCreate(context,
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "sector"))))))
                .then(Commands.literal("deposit")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> companyMoney(context, StringArgumentType.getString(context, "company"),
                                                StringArgumentType.getString(context, "amount"), true)))))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> companyMoney(context, StringArgumentType.getString(context, "company"),
                                                StringArgumentType.getString(context, "amount"), false)))))
                .then(Commands.literal("hire")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> companyHire(context,
                                                StringArgumentType.getString(context, "company"),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("fire")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> companyFire(context,
                                                StringArgumentType.getString(context, "company"),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("levelup")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .executes(context -> companyLevelUp(context, StringArgumentType.getString(context, "company")))))
                .then(Commands.literal("dividend")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("ratio", StringArgumentType.word())
                                        .executes(context -> companyDividend(context,
                                                StringArgumentType.getString(context, "company"),
                                                StringArgumentType.getString(context, "ratio"))))))
                .then(Commands.literal("sector")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("sector", StringArgumentType.word()).suggests(sectorNames())
                                        .executes(context -> companySector(context,
                                                StringArgumentType.getString(context, "company"),
                                                StringArgumentType.getString(context, "sector"))))))
                .then(Commands.literal("close")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .executes(context -> companyClose(context, StringArgumentType.getString(context, "company")))))
                .then(Commands.literal("raise")
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(context -> companyRaise(context,
                                                StringArgumentType.getString(context, "company"),
                                                StringArgumentType.getString(context, "amount"))))))
                .then(Commands.literal("info")
                        .executes(context -> companyInfo(context, null))
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .executes(context -> companyInfo(context, StringArgumentType.getString(context, "company"))))));
        root.then(Commands.literal("ipo")
                .then(Commands.literal("status").executes(context -> ipoStatus(context, null))
                        .then(Commands.argument("company", StringArgumentType.string()).suggests(companyNames())
                                .executes(context -> ipoStatus(context, StringArgumentType.getString(context, "company")))))
                .then(Commands.literal("apply")
                        .then(Commands.argument("ratio", DoubleArgumentType.doubleArg(0.10, 0.49))
                                .executes(MarketCommand::ipoApply)))
                .then(Commands.literal("confirm").executes(MarketCommand::ipoConfirm)));
        root.then(Commands.literal("fut")
                .executes(MarketCommand::futuresList)
                .then(Commands.literal("list").executes(MarketCommand::futuresList))
                .then(Commands.literal("quote")
                        .then(Commands.argument("symbol", StringArgumentType.word()).suggests(futureSymbols())
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                        .executes(MarketCommand::futuresQuote))))
                .then(Commands.literal("long")
                        .then(Commands.argument("symbol", StringArgumentType.word()).suggests(futureSymbols())
                                .then(Commands.argument("lots", LongArgumentType.longArg(1, 10_000))
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                                .executes(context -> futuresOpen(context, true))))))
                .then(Commands.literal("short")
                        .then(Commands.argument("symbol", StringArgumentType.word()).suggests(futureSymbols())
                                .then(Commands.argument("lots", LongArgumentType.longArg(1, 10_000))
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                                .executes(context -> futuresOpen(context, false))))))
                .then(Commands.literal("close")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> futuresClose(context, StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("positions").executes(MarketCommand::futuresPositions))
                .then(Commands.literal("margin")
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(context -> futuresMargin(context, StringArgumentType.getString(context, "id"),
                                                        StringArgumentType.getString(context, "amount"), true)))))
                        .then(Commands.literal("withdraw")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(context -> futuresMargin(context, StringArgumentType.getString(context, "id"),
                                                        StringArgumentType.getString(context, "amount"), false)))))));
        root.then(Commands.literal("opt")
                .executes(context -> optionsList(context, 1))
                .then(Commands.literal("list").executes(context -> optionsList(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 99))
                                .executes(context -> optionsList(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("quote")
                        .then(Commands.argument("code", StringArgumentType.word()).suggests(stockCodes())
                                .then(Commands.argument("strike", StringArgumentType.word())
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                                .executes(context -> optionsQuote(context,
                                                        StringArgumentType.getString(context, "code"),
                                                        StringArgumentType.getString(context, "strike"),
                                                        IntegerArgumentType.getInteger(context, "days")))))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("callOrPut", StringArgumentType.word()).suggests(callPutSuggestions())
                                .then(Commands.argument("code", StringArgumentType.word()).suggests(stockCodes())
                                        .then(Commands.argument("strike", StringArgumentType.word())
                                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                                        .then(Commands.argument("contracts", LongArgumentType.longArg(1, 10_000))
                                                                .executes(context -> optionsOpen(context, false))))))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("callOrPut", StringArgumentType.word()).suggests(callPutSuggestions())
                                .then(Commands.argument("code", StringArgumentType.word()).suggests(stockCodes())
                                        .then(Commands.argument("strike", StringArgumentType.word())
                                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                                        .then(Commands.argument("contracts", LongArgumentType.longArg(1, 10_000))
                                                                .executes(context -> optionsOpen(context, true))))))))
                .then(Commands.literal("close")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> optionsClose(context, StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("positions").executes(MarketCommand::optionsPositions)));
        root.then(Commands.literal("gov")
                .executes(MarketCommand::govStatus)
                .then(Commands.literal("status").executes(MarketCommand::govStatus))
                .then(Commands.literal("budget").executes(MarketCommand::govBudget))
                .then(Commands.literal("policies").executes(MarketCommand::govPolicies))
                .then(Commands.literal("history").executes(MarketCommand::govHistory)));
        root.then(Commands.literal("ai")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(context -> naturalLanguage(context, StringArgumentType.getString(context, "text"), true))));
        root.then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(context -> naturalLanguage(context, StringArgumentType.getString(context, "text"), false)));
        return root;
    }

    // ── 公共上下文 ──

    private static AimcContext ctx() {
        return com.mogutofu.aimcmarket.AimcmarketMod.context();
    }

    private static boolean ready(CommandSourceStack source) {
        if (ctx() == null) {
            Messages.sendFail(source, "市场还没初始化（等服务器启动完成再试）");
            return false;
        }
        if (!ctx().settings.enabled) {
            Messages.sendFail(source, "市场维护中（配置 general.enabled=false）");
            return false;
        }
        return true;
    }

    /** 需要游戏内玩家。 */
    private static Account player(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "这条命令只能游戏内用");
            return null;
        }
        Account account = ctx().accounts.get(player.getUUID().toString());
        if (account == null) {
            Messages.sendFail(source, "账户还没建好，重进一下服务器");
            return null;
        }
        source.getServer().execute(() -> GovernmentEngine.onPlayerMarketAction(ctx(), account));
        return account;
    }

    private static void send(CommandSourceStack source, ActionResult result) {
        if (result == null) {
            Messages.sendFail(source, "操作失败");
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

    private static void tickGovernment(CommandSourceStack source) {
        AimcContext context = ctx();
        if (context != null) {
            source.getServer().execute(() -> MarketTicker.triggerGovernment(context, System.currentTimeMillis()));
        }
    }

    private static long amount(String text) {
        return Cents.parse(text);
    }

    // ── 根命令 ──

    private static int rootHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        Messages.send(source, "AIMCmarket 市场先生。常用命令：");
        Messages.sendInfo(source, "/ex balance｜/ex work｜/ex daily｜/ex buy <物品> <数量>｜/ex sell <物品> <数量>");
        Messages.sendInfo(source, "/ex price <物品>｜/ex board [页]｜/ex news [页]｜/ex top｜/ex history");
        Messages.sendInfo(source, "/ex stock buy|sell <代码> <股数>｜/ex stock info <代码>｜/ex stock list");
        Messages.sendInfo(source, "/ex fund buy|sell <代码> <金额>｜/ex bank deposit|withdraw <金额>｜/ex bank loan take");
        Messages.sendInfo(source, "/ex company create <名字> <行业>｜/ex company hire <人数>｜/ex ipo apply <比例>｜/ex ipo confirm");
        Messages.sendInfo(source, "/ex fut long|short <标的> <手数> <天数>｜/ex opt buy|sell <call|put> <代码> <行权价> <天数> <张数>");
        Messages.sendInfo(source, "/ex gov status｜/ex 直接说人话（例：/ex 买 3 个铁锭）");
        if (account != null) {
            Messages.sendOk(source, "余额 " + Messages.money(account.balanceCents())
                    + "｜大盘 " + ctx().stocks.formatIndex() + "（" + Messages.changeText(ctx().stocks.indexChange7d(ctx().tradingDay())) + "）");
        } else {
            Messages.sendInfo(source, "大盘 " + ctx().stocks.formatIndex());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int balance(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        AimcContext ctx = ctx();
        long stockValue = 0L;
        for (var entry : account.stocks.entrySet()) {
            stockValue += ctx.stocks.priceOf(entry.getKey()) * entry.getValue().shares;
        }
        long fundValue = 0L;
        for (var entry : account.funds.entrySet()) {
            fundValue += ctx.funds.valueOfUnits(entry.getKey(), entry.getValue().unitsMilli);
        }
        Messages.send(source, "余额 " + Messages.money(account.balanceCents())
                + "｜活期 " + Messages.money(account.bank.currentCents)
                + "｜定期 " + Messages.money(account.bank.totalDepositsCents() - account.bank.currentCents)
                + "｜贷款 " + Messages.money(account.bank.totalLoanCents()));
        Messages.sendInfo(source, "股票市值 " + Messages.money(stockValue) + "｜基金市值 " + Messages.money(fundValue)
                + "｜信用分 " + Math.round(account.creditScore));
        return Command.SINGLE_SUCCESS;
    }

    private static int pay(CommandContext<CommandSourceStack> context, String targetName, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account from = player(source);
        if (from == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex pay Steve 12.5");
            return 0;
        }
        Account to = ctx().accounts.findByName(targetName).orElse(null);
        if (to == null) {
            Messages.sendFail(source, "找不到玩家：" + targetName);
            return 0;
        }
        if (to.uuid.equals(from.uuid)) {
            Messages.sendFail(source, "不能给自己转账");
            return 0;
        }
        if (from.balanceCents() < cents) {
            Messages.sendFail(source, "余额不足");
            return 0;
        }
        try {
            ctx().ledger.transfer(from, to, cents, "player.pay", from.name, "转账给 " + to.name);
            Messages.sendOk(source, "转给 " + to.name + " " + Messages.money(cents)
                    + "，余额 " + Messages.money(from.balanceCents()));
            ServerPlayer target = source.getServer().getPlayerList().getPlayer(to.uuid == null ? null
                    : UUID.fromString(to.uuid));
            if (target != null) {
                target.sendSystemMessage(Messages.market(from.name + " 转给你 " + Messages.money(cents)));
            }
        } catch (RuntimeException error) {
            Messages.sendFail(source, "转账失败：" + error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int work(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, com.mogutofu.aimcmarket.engine.IncomeService.work(ctx(), account));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int daily(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, com.mogutofu.aimcmarket.engine.IncomeService.daily(ctx(), account));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    // ── 商品 ──

    private static int buy(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        String itemText = StringArgumentType.getString(context, "item");
        int count = IntegerArgumentType.getInteger(context, "count");
        MarketStore.CatalogItem item = ctx().market.resolve(itemText);
        if (item == null) {
            Messages.sendFail(source, "目录里没有这个物品：" + itemText + "（/ex board 看能买什么）");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        ActionResult result = TradeService.buy(ctx(), account, item.id, count);
        if (!result.ok) {
            send(source, result);
            return 0;
        }
        int left = giveItem(player, item.id, count);
        if (left > 0) {
            Messages.sendInfo(source, "背包放不下 " + left + " 个，已掉在脚下");
        }
        send(source, result);
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int sell(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        String itemText = StringArgumentType.getString(context, "item");
        int count = IntegerArgumentType.getInteger(context, "count");
        MarketStore.CatalogItem item = ctx().market.resolve(itemText);
        if (item == null) {
            Messages.sendFail(source, "目录不收这个物品：" + itemText);
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        int have = countItem(player, item.id);
        if (have < count) {
            Messages.sendFail(source, "背包里只有 " + have + " 个 " + item.nameZh);
            return 0;
        }
        ActionResult result = TradeService.sell(ctx(), account, item.id, count);
        if (!result.ok) {
            send(source, result);
            return 0;
        }
        removeItem(player, item.id, count);
        send(source, result);
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int price(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        String itemText = StringArgumentType.getString(context, "item");
        MarketStore.CatalogItem item = ctx().market.resolve(itemText);
        if (item == null) {
            Messages.sendFail(source, "目录里没有这个物品：" + itemText);
            return 0;
        }
        long price = ctx().market.price(item.id);
        Messages.send(source, item.nameZh + "（" + item.id + "）现价 " + Messages.money(price)
                + "｜买价 " + Messages.money(TradeService.effectiveAskCents(ctx(), item.id))
                + "｜回收价 " + Messages.money(TradeService.effectiveBidCents(ctx(), item.id)));
        Messages.sendInfo(source, "基准价 " + Messages.money(item.basePriceCents)
                + "｜波动 " + Messages.percent(item.volatility) + "｜流动性 " + Messages.money(item.liquidity));
        return Command.SINGLE_SUCCESS;
    }

    private static int board(CommandContext<CommandSourceStack> context) {
        return board(context, 1);
    }

    private static int board(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (MarketStore.CatalogItem item : ctx().market.enabledItems()) {
            long price = ctx().market.price(item.id);
            double ratio = item.basePriceCents <= 0 ? 0 : (double) price / item.basePriceCents - 1.0;
            lines.add(item.nameZh + " " + Messages.money(TradeService.effectiveAskCents(ctx(), item.id))
                    + "/" + Messages.money(TradeService.effectiveBidCents(ctx(), item.id))
                    + " " + Messages.changeText(ratio));
        }
        lines.sort(Comparator.comparing((String line) -> line));
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex board");
        if (source.getPlayer() != null) {
            Messages.sendInfo(source, "点下面按钮直接买（可改数量）：");
            for (MarketStore.CatalogItem item : ctx().market.enabledItems().subList(0,
                    Math.min(PER_PAGE, ctx().market.enabledItems().size()))) {
                MutableComponentHelper.sendBuyButton(source, item.nameZh, item.id);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int news(CommandContext<CommandSourceStack> context) {
        return news(context, 1);
    }

    private static int news(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (var item : ctx().news.recent(80)) {
            lines.add("[" + item.category + "] " + item.headline);
            if (!item.body.isBlank()) {
                lines.add("   " + item.body);
            }
        }
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex news");
        return Command.SINGLE_SUCCESS;
    }

    private static int top(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> result = new ArrayList<>();
        List<Account> accounts = new ArrayList<>(ctx().accounts.all());
        for (Account account : accounts) {
            long value = account.balanceCents() + account.bank.totalDepositsCents() - account.bank.totalLoanCents();
            for (var entry : account.stocks.entrySet()) {
                value += ctx().stocks.priceOf(entry.getKey()) * entry.getValue().shares;
            }
            for (var entry : account.funds.entrySet()) {
                value += ctx().funds.valueOfUnits(entry.getKey(), entry.getValue().unitsMilli);
            }
            result.add(Messages.money(value) + "  " + account.name);
        }
        result.sort(Comparator.comparing((String line) -> line).reversed());
        Messages.sendPage(source, result, 1, 10, "/ex top");
        return Command.SINGLE_SUCCESS;
    }

    private static int history(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        List<String> lines = readLedgerFor(account.name, 12);
        if (lines.isEmpty()) {
            Messages.sendInfo(source, "还没有流水记录");
            return Command.SINGLE_SUCCESS;
        }
        Messages.sendPage(source, lines, 1, 12, "/ex history");
        return Command.SINGLE_SUCCESS;
    }

    // ── AI 自然语言 ──

    private static int naturalLanguage(CommandContext<CommandSourceStack> context, String text, boolean forceAi) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        AimcContext ctx = ctx();
        if (text == null || text.isBlank()) {
            Messages.sendInfo(source, "说点具体的，例如：/ex 买 3 个铁锭");
            return 0;
        }
        if (forceAi && !ctx.settings.aiEnabled) {
            Messages.sendInfo(source, "AI 已关闭（ai.enabled=false），只能用结构化命令，例如 /ex buy iron_ingot 3");
            return 0;
        }
        JsonObject local = LocalParser.parse(text, ctx.market);
        String localIntent = local == null ? "unknown" : local.get("intent").getAsString();
        if (!forceAi && !"unknown".equals(localIntent)) {
            executeParsed(source, account, local);
            return Command.SINGLE_SUCCESS;
        }
        if (!ctx.settings.aiEnabled || ctx.ai == null || ctx.aiSubmitter == null) {
            Messages.sendInfo(source, LocalParser.helpHint());
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - account.lastAiAtMs < ctx.settings.aiPlayerMinIntervalMs) {
            Messages.sendFail(source, "慢一点，AI 每 2 秒只能问一次");
            return 0;
        }
        account.lastAiAtMs = now;
        String playerName = account.name;
        String uuid = account.uuid;
        Messages.sendInfo(source, "市场先生正在看你的请求…");
        ctx.aiSubmitter.submit("parse", () -> ctx.ai.parse(playerName, text, buildParseContext(account)),
                data -> {
                    JsonObject parsed = data == null ? null : AiJson.validateParse(data, ctx.market);
                    if (parsed == null) {
                        parsed = LocalParser.parse(text, ctx.market);
                    }
                    executeParsedForPlayer(uuid, parsed, text);
                });
        return Command.SINGLE_SUCCESS;
    }

    private static JsonObject buildParseContext(Account account) {
        AimcContext ctx = ctx();
        JsonObject context = new JsonObject();
        context.addProperty("balanceCents", account.balanceCents());
        JsonArray catalog = new JsonArray();
        int count = 0;
        for (MarketStore.CatalogItem item : ctx.market.enabledItems()) {
            if (count++ >= 60) {
                break;
            }
            catalog.add(item.id);
        }
        context.add("catalogCandidates", catalog);
        JsonArray stocks = new JsonArray();
        for (StockStore.Stock stock : ctx.stocks.all()) {
            stocks.add(stock.code);
        }
        context.add("stocks", stocks);
        JsonArray funds = new JsonArray();
        for (FundStore.Fund fund : ctx.funds.all()) {
            funds.add(fund.code);
        }
        context.add("funds", funds);
        return context;
    }

    /** 解析结果落地（本地/AI 共用）。 */
    private static void executeParsed(CommandSourceStack source, Account account, JsonObject parsed) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return;
        }
        execute(account, parsed, message -> {
            if (message.ok) {
                player.sendSystemMessage(Messages.ok(message.message));
            } else {
                player.sendSystemMessage(Messages.fail(message.message));
            }
        }, source);
    }

    private static void executeParsedForPlayer(String uuid, JsonObject parsed, String originalText) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        var server = com.mogutofu.aimcmarket.AimcmarketMod.server();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(uuid));
        Account account = ctx.accounts.get(uuid);
        if (player == null || account == null) {
            return;
        }
        execute(account, parsed, message -> {
            if (message.ok) {
                player.sendSystemMessage(Messages.ok(message.message));
            } else {
                player.sendSystemMessage(Messages.fail(message.message));
            }
        }, null);
    }

    private interface MessageSink {
        void accept(ActionResult result);
    }

    private static void execute(Account account, JsonObject parsed, MessageSink sink, CommandSourceStack source) {
        AimcContext ctx = ctx();
        if (ctx == null || parsed == null) {
            sink.accept(ActionResult.fail("没听懂，试试 /ex buy iron_pickaxe 3"));
            return;
        }
        String intent = parsed.has("intent") ? parsed.get("intent").getAsString() : "unknown";
        try {
            switch (intent) {
                case "buy" -> {
                    var items = parsed.getAsJsonArray("items");
                    if (items == null || items.isEmpty()) {
                        sink.accept(ActionResult.fail(LocalParser.helpHint()));
                        return;
                    }
                    for (var element : items) {
                        JsonObject one = element.getAsJsonObject();
                        String id = one.get("id").getAsString();
                        int count = one.has("count") ? one.get("count").getAsInt() : 1;
                        sink.accept(TradeService.buy(ctx, account, id, count));
                    }
                }
                case "sell" -> {
                    var items = parsed.getAsJsonArray("items");
                    if (items == null || items.isEmpty()) {
                        sink.accept(ActionResult.fail(LocalParser.helpHint()));
                        return;
                    }
                    var player = com.mogutofu.aimcmarket.AimcmarketMod.server() == null ? null
                            : com.mogutofu.aimcmarket.AimcmarketMod.server().getPlayerList()
                            .getPlayer(UUID.fromString(account.uuid));
                    for (var element : items) {
                        JsonObject one = element.getAsJsonObject();
                        String id = one.get("id").getAsString();
                        int count = one.has("count") ? one.get("count").getAsInt() : 1;
                        if (player == null || countItem(player, id) < count) {
                            sink.accept(ActionResult.fail("背包里没有足够的 " + id));
                            continue;
                        }
                        ActionResult result = TradeService.sell(ctx, account, id, count);
                        if (result.ok) {
                            removeItem(player, id, count);
                        }
                        sink.accept(result);
                    }
                }
                case "price" -> {
                    var items = parsed.getAsJsonArray("items");
                    String id = items != null && !items.isEmpty() ? items.get(0).getAsJsonObject().get("id").getAsString() : null;
                    MarketStore.CatalogItem item = id == null ? null : ctx.market.item(id);
                    if (item == null) {
                        sink.accept(ActionResult.fail("要点哪个物品的价格？"));
                    } else {
                        sink.accept(ActionResult.ok(item.nameZh + " 现价 " + Messages.money(ctx.market.price(item.id))
                                + "，买价 " + Messages.money(TradeService.effectiveAskCents(ctx, item.id))
                                + "，回收价 " + Messages.money(TradeService.effectiveBidCents(ctx, item.id))));
                    }
                }
                case "balance" -> sink.accept(ActionResult.ok("余额 " + Messages.money(account.balanceCents())
                        + "，存款 " + Messages.money(account.bank.totalDepositsCents())
                        + "，贷款 " + Messages.money(account.bank.totalLoanCents())));
                case "work" -> sink.accept(com.mogutofu.aimcmarket.engine.IncomeService.work(ctx, account));
                case "daily" -> sink.accept(com.mogutofu.aimcmarket.engine.IncomeService.daily(ctx, account));
                case "pay" -> {
                    long cents = parsed.has("amountCents") ? parsed.get("amountCents").getAsLong() : 0L;
                    String target = parsed.has("player") ? parsed.get("player").getAsString() : "";
                    Account to = ctx.accounts.findByName(target).orElse(null);
                    if (to == null || cents <= 0L) {
                        sink.accept(ActionResult.fail("转账要写清玩家和金额，例：/ex pay Steve 12.5"));
                    } else if (account.balanceCents() < cents) {
                        sink.accept(ActionResult.fail("余额不足"));
                    } else {
                        ctx.ledger.transfer(account, to, cents, "player.pay", account.name, "转账");
                        sink.accept(ActionResult.ok("转给 " + to.name + " " + Messages.money(cents)));
                    }
                }
                case "stock_buy" -> sink.accept(FinanceService.buyStock(ctx, account, symbol(parsed), shares(parsed)));
                case "stock_sell" -> sink.accept(FinanceService.sellStock(ctx, account, symbol(parsed), shares(parsed)));
                case "stock_info" -> sink.accept(ActionResult.ok(FinanceService.stockInfo(ctx, account, symbol(parsed))));
                case "fund_buy" -> sink.accept(FinanceService.buyFund(ctx, account, symbol(parsed),
                        parsed.has("amountCents") ? parsed.get("amountCents").getAsLong() : 0L));
                case "fund_sell" -> {
                    long units = parsed.has("unitsMilli") ? parsed.get("unitsMilli").getAsLong() : 0L;
                    sink.accept(FinanceService.sellFund(ctx, account, symbol(parsed), units));
                }
                case "fund_info" -> sink.accept(ActionResult.ok(FinanceService.fundInfo(ctx, account, symbol(parsed))));
                case "deposit" -> sink.accept(FinanceService.deposit(ctx, account,
                        parsed.has("amountCents") ? parsed.get("amountCents").getAsLong() : 0L));
                case "withdraw" -> sink.accept(FinanceService.withdraw(ctx, account,
                        parsed.has("amountCents") ? parsed.get("amountCents").getAsLong() : 0L));
                case "bank_loan" -> sink.accept(FinanceService.takeLoan(ctx, account,
                        parsed.has("amountCents") ? parsed.get("amountCents").getAsLong() : 0L));
                case "company_create" -> sink.accept(CompanyEngine.create(ctx, account,
                        parsed.has("companyName") ? parsed.get("companyName").getAsString() : "我的公司",
                        parsed.has("sector") ? parsed.get("sector").getAsString() : "mining"));
                case "ipo_apply" -> sink.accept(ActionResult.fail("IPO 请用 /ex ipo apply <流通比例>（要确认公司满足条件）"));
                case "futures_long" -> sink.accept(FuturesEngine.open(ctx, account, symbol(parsed), true,
                        parsed.has("lots") ? parsed.get("lots").getAsLong() : 1L,
                        parsed.has("expiryDays") ? parsed.get("expiryDays").getAsInt() : 7));
                case "futures_short" -> sink.accept(FuturesEngine.open(ctx, account, symbol(parsed), false,
                        parsed.has("lots") ? parsed.get("lots").getAsLong() : 1L,
                        parsed.has("expiryDays") ? parsed.get("expiryDays").getAsInt() : 7));
                case "options_buy" -> sink.accept(OptionsEngine.open(ctx, account, false,
                        parsed.has("optionType") ? parsed.get("optionType").getAsString() : "call",
                        symbol(parsed),
                        parsed.has("strikeCents") ? parsed.get("strikeCents").getAsLong() : 0L,
                        parsed.has("expiryDays") ? parsed.get("expiryDays").getAsInt() : 7,
                        parsed.has("contracts") ? parsed.get("contracts").getAsLong() : 1L));
                case "options_sell" -> sink.accept(OptionsEngine.open(ctx, account, true,
                        parsed.has("optionType") ? parsed.get("optionType").getAsString() : "call",
                        symbol(parsed),
                        parsed.has("strikeCents") ? parsed.get("strikeCents").getAsLong() : 0L,
                        parsed.has("expiryDays") ? parsed.get("expiryDays").getAsInt() : 7,
                        parsed.has("contracts") ? parsed.get("contracts").getAsLong() : 1L));
                case "gov_status" -> sink.accept(ActionResult.ok(govStatusText()));
                case "help" -> sink.accept(ActionResult.ok("看 /ex 的帮助，或者直接说：/ex 买 3 个铁锭"));
                default -> sink.accept(ActionResult.fail(LocalParser.helpHint()));
            }
        } catch (RuntimeException error) {
            sink.accept(ActionResult.fail("操作失败：" + error.getMessage()));
        }
    }

    private static String symbol(JsonObject parsed) {
        return parsed.has("symbol") ? parsed.get("symbol").getAsString().toUpperCase(Locale.ROOT) : "";
    }

    private static long shares(JsonObject parsed) {
        return parsed.has("shares") ? parsed.get("shares").getAsLong() : 0L;
    }

    // ── 股票 ──

    private static int stockList(CommandContext<CommandSourceStack> context) {
        return stockList(context, 1);
    }

    private static int stockList(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        List<String> lines = new ArrayList<>();
        for (StockStore.Stock stock : ctx().stocks.all()) {
            long held = account == null ? 0L : account.stockShares(stock.code);
            double change = stock.initialPriceCents <= 0L ? 0.0
                    : (double) stock.priceCents / stock.initialPriceCents - 1.0;
            lines.add(stock.code + " " + stock.name + " " + Messages.money(stock.priceCents)
                    + "（" + stock.sector + " " + Messages.changeText(change) + "）"
                    + (held > 0 ? " 持有 " + held + " 股" : ""));
        }
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex stock list");
        Messages.sendInfo(source, "大盘 " + ctx().stocks.formatIndex()
                + "（7 日 " + Messages.changeText(ctx().stocks.indexChange7d(ctx().tradingDay())) + "）");
        return Command.SINGLE_SUCCESS;
    }

    private static int stockBuy(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, FinanceService.buyStock(ctx(), account, StringArgumentType.getString(context, "code"),
                LongArgumentType.getLong(context, "shares")));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int stockSell(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, FinanceService.sellStock(ctx(), account, StringArgumentType.getString(context, "code"),
                LongArgumentType.getLong(context, "shares")));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int stockInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        String text = FinanceService.stockInfo(ctx(), account, StringArgumentType.getString(context, "code"));
        if (text == null || text.isBlank()) {
            Messages.sendFail(source, "没有这个股票代码");
            return 0;
        }
        Messages.sendLines(source, text);
        return Command.SINGLE_SUCCESS;
    }

    // ── 基金 ──

    private static int fundList(CommandContext<CommandSourceStack> context) {
        return fundList(context, 1);
    }

    private static int fundList(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        List<String> lines = new ArrayList<>();
        for (FundStore.Fund fund : ctx().funds.all()) {
            long units = account == null ? 0L : account.fundUnitsMilli(fund.code);
            lines.add(fund.code + " " + fund.name + " 净值 " + ctx().funds.formatNav(fund.code)
                    + (units > 0 ? " 持有 " + Cents.format(units / 1000L) + " 份" : ""));
        }
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex fund list");
        return Command.SINGLE_SUCCESS;
    }

    private static int fundBuy(CommandContext<CommandSourceStack> context, String code, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex fund buy FND01 100");
            return 0;
        }
        send(source, FinanceService.buyFund(ctx(), account, code, cents));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int fundSell(CommandContext<CommandSourceStack> context, String code, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        FundStore.Fund fund = ctx().funds.fund(code);
        if (fund == null) {
            Messages.sendFail(source, "没有这只基金：" + code);
            return 0;
        }
        long unitsMilli;
        try {
            unitsMilli = Math.round(Double.parseDouble(amountText.trim()) * 1000.0);
        } catch (NumberFormatException error) {
            Messages.sendFail(source, "份额格式不对，例：/ex fund sell FND01 100");
            return 0;
        }
        if (unitsMilli <= 0L) {
            Messages.sendFail(source, "份额格式不对，例：/ex fund sell FND01 100");
            return 0;
        }
        send(source, FinanceService.sellFund(ctx(), account, code, unitsMilli));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int fundInfo(CommandContext<CommandSourceStack> context, String code) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        String text = FinanceService.fundInfo(ctx(), account, code);
        if (text == null || text.isBlank()) {
            Messages.sendFail(source, "没有这只基金：" + code);
            return 0;
        }
        Messages.sendLines(source, text);
        return Command.SINGLE_SUCCESS;
    }

    // ── 银行 ──

    private static int bankInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        Messages.sendLines(source, FinanceService.bankInfo(ctx(), account));
        return Command.SINGLE_SUCCESS;
    }

    private static int bankDeposit(CommandContext<CommandSourceStack> context, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex bank deposit 100");
            return 0;
        }
        send(source, FinanceService.deposit(ctx(), account, cents));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int bankWithdraw(CommandContext<CommandSourceStack> context, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex bank withdraw 100");
            return 0;
        }
        send(source, FinanceService.withdraw(ctx(), account, cents));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int bankTerm(CommandContext<CommandSourceStack> context, int days, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex bank term 7 100");
            return 0;
        }
        send(source, FinanceService.termDeposit(ctx(), account, days, cents));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int bankLoan(CommandContext<CommandSourceStack> context, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amountText == null ? 0L : amount(amountText);
        if (cents <= 0L) {
            MarketSettings settings = ctx().settings;
            long limit = ctx().bank.creditLimit(account, settings, ctx().nowMs());
            long amountCents = Math.min(limit, 100_000L);
            if (amountCents <= 0L) {
                Messages.sendFail(source, "当前没有可借额度（多赚点钱、按时还款能提高额度）");
                return 0;
            }
            cents = amountCents;
        }
        send(source, FinanceService.takeLoan(ctx(), account, cents));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int bankRepay(CommandContext<CommandSourceStack> context, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对，例：/ex bank loan repay 100");
            return 0;
        }
        send(source, FinanceService.repayLoan(ctx(), account, cents));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    // ── 公司 ──

    private static int companyCreate(CommandContext<CommandSourceStack> context, String name, String sector) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, CompanyEngine.create(ctx(), account, name, sector));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static CompanyStore.Company resolveCompany(Account account, String text) {
        if (account == null) {
            return null;
        }
        for (CompanyStore.Company company : ctx().companies.byCeo(account.uuid)) {
            if (company.id.equalsIgnoreCase(text) || company.name.equalsIgnoreCase(text)
                    || (company.code != null && company.code.equalsIgnoreCase(text))) {
                return company;
            }
        }
        CompanyStore.Company byName = ctx().companies.findByName(text);
        if (byName != null && byName.ceoUuid.equals(account.uuid)) {
            return byName;
        }
        return null;
    }

    private static int companyMoney(CommandContext<CommandSourceStack> context, String companyName, String amountText, boolean deposit) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对");
            return 0;
        }
        try {
            if (deposit) {
                ctx().companies.deposit(account, company, cents);
                Messages.sendOk(source, "向 " + company.name + " 注资 " + Messages.money(cents)
                        + "，公司现金 " + Messages.money(company.cashCents));
            } else {
                ctx().companies.withdraw(account, company, cents);
                Messages.sendOk(source, "从 " + company.name + " 取出 " + Messages.money(cents)
                        + "，公司现金 " + Messages.money(company.cashCents));
            }
        } catch (RuntimeException error) {
            Messages.sendFail(source, error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int companyHire(CommandContext<CommandSourceStack> context, String companyName, int count) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        send(source, CompanyEngine.hire(ctx(), account, company, count));
        return Command.SINGLE_SUCCESS;
    }

    private static int companyFire(CommandContext<CommandSourceStack> context, String companyName, int count) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        try {
            ctx().companies.fire(company, count);
            Messages.sendOk(source, company.name + " 裁员 " + count + " 人，现有员工 " + company.employees
                    + "，口碑 " + String.format(Locale.ROOT, "%.2f", company.reputation));
        } catch (RuntimeException error) {
            Messages.sendFail(source, error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int companyLevelUp(CommandContext<CommandSourceStack> context, String companyName) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        send(source, CompanyEngine.levelUp(ctx(), account, company));
        return Command.SINGLE_SUCCESS;
    }

    private static int companyDividend(CommandContext<CommandSourceStack> context, String companyName, String ratioText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        long basisPoints;
        try {
            basisPoints = Math.round(Double.parseDouble(ratioText.trim()) * 100.0);
        } catch (NumberFormatException error) {
            Messages.sendFail(source, "分红比例要写 0-80（百分比），例：/ex company dividend 我司 30");
            return 0;
        }
        if (basisPoints < 0L || basisPoints > 8_000L) {
            Messages.sendFail(source, "分红比例要写 0-80（百分比），例：/ex company dividend 我司 30");
            return 0;
        }
        company.dividendRatio = basisPoints / 10_000.0;
        Messages.sendOk(source, company.name + " 分红比例设为 " + Messages.percent(company.dividendRatio)
                + "（每 7 交易日发一次）");
        return Command.SINGLE_SUCCESS;
    }

    private static int companySector(CommandContext<CommandSourceStack> context, String companyName, String sector) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        String code = sectorCode(sector);
        if (code == null) {
            Messages.sendFail(source, "行业只有：采矿/农业/制造/贸易/服务");
            return 0;
        }
        try {
            ctx().companies.switchSector(company, code, ctx().tradingDay(), 7L, ctx().settings);
            Messages.sendOk(source, company.name + " 转到" + CompanyStore.SECTORS.get(code) + "行业");
        } catch (RuntimeException error) {
            Messages.sendFail(source, error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int companyClose(CommandContext<CommandSourceStack> context, String companyName) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        try {
            ctx().companies.close(company, account);
            Messages.sendOk(source, "已关闭公司 " + company.name + "，剩余现金已退回");
        } catch (RuntimeException error) {
            Messages.sendFail(source, error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int companyRaise(CommandContext<CommandSourceStack> context, String companyName, String amountText) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        CompanyStore.Company company = resolveCompany(account, companyName);
        if (company == null) {
            Messages.sendFail(source, "找不到你名下的公司：" + companyName);
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对");
            return 0;
        }
        long price = company.code == null ? 0L : ctx().stocks.priceOf(company.code);
        try {
            long newShares = ctx().companies.raise(company, cents, ctx().tradingDay(), 7L, ctx().settings,
                    price, ctx().ledger, account);
            Messages.sendOk(source, company.name + " 增发 " + newShares + " 股，募资 " + Messages.money(cents));
        } catch (RuntimeException error) {
            Messages.sendFail(source, error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int companyList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        List<String> lines = new ArrayList<>();
        for (CompanyStore.Company company : ctx().companies.all()) {
            String owner = company.ceoUuid.equals(account == null ? "" : account.uuid) ? "（你的）" : "";
            lines.add(company.name + owner + " " + company.sectorName() + " Lv." + company.level
                    + " 估值 " + Messages.money(company.valuationCents)
                    + (company.listed ? " 已上市 " + company.code : "")
                    + (company.bankrupt ? " 已破产" : ""));
        }
        if (!ctx().companies.bankruptHistory().isEmpty()) {
            lines.add("—— 破产历史：" + String.join("、", ctx().companies.bankruptHistory()));
        }
        Messages.sendPage(source, lines, 1, PER_PAGE, "/ex company list");
        return Command.SINGLE_SUCCESS;
    }

    private static int companyInfo(CommandContext<CommandSourceStack> context, String companyName) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        CompanyStore.Company company = companyName == null ? null : resolveCompany(account, companyName);
        if (company == null) {
            List<CompanyStore.Company> mine = account == null ? List.of() : ctx().companies.byCeo(account.uuid);
            if (mine.isEmpty()) {
                Messages.sendFail(source, "你还没有公司。开公司：/ex company create <名字> <采矿|农业|制造|贸易|服务>");
                return 0;
            }
            company = mine.get(0);
        }
        Messages.send(source, company.name + "（" + company.id + "）" + company.sectorName()
                + " Lv." + company.level + (company.listed ? "｜上市 " + company.code : "") + (company.bankrupt ? "｜已破产" : ""));
        Messages.sendInfo(source, "现金 " + Messages.money(company.cashCents) + "｜估值 " + Messages.money(company.valuationCents)
                + "｜员工 " + company.employees + "/" + company.maxEmployees() + "｜口碑 "
                + String.format(Locale.ROOT, "%.2f", company.reputation));
        Messages.sendInfo(source, "累计营收 " + Messages.money(company.cumulativeRevenueCents)
                + "｜近 7 日均利润 " + Messages.money(company.averageDailyProfit(7))
                + "｜分红比例 " + Messages.percent(company.dividendRatio));
        Messages.sendInfo(source, "股本 " + company.sharesTotal + " 股，CEO 持股 "
                + String.format(Locale.ROOT, "%.1f%%",
                100.0 * company.sharesOf(company.ceoUuid) / Math.max(1L, company.sharesTotal))
                + (company.ipoPending ? "｜IPO 申请中（等 1 个交易日后 /ex ipo confirm）" : ""));
        return Command.SINGLE_SUCCESS;
    }

    private static String sectorCode(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (var entry : CompanyStore.SECTORS.entrySet()) {
            if (entry.getKey().equals(lower) || entry.getValue().equals(text)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ── IPO ──

    private static int ipoStatus(CommandContext<CommandSourceStack> context, String companyName) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = source.getPlayer() == null ? null : ctx().accounts.get(source.getPlayer().getUUID().toString());
        CompanyStore.Company company = companyName == null ? null : resolveCompany(account, companyName);
        if (company == null) {
            List<CompanyStore.Company> mine = account == null ? List.of() : ctx().companies.byCeo(account.uuid);
            if (mine.isEmpty()) {
                Messages.sendFail(source, "你还没有公司");
                return 0;
            }
            company = mine.get(0);
        }
        send(source, IpoEngine.status(ctx(), company));
        return Command.SINGLE_SUCCESS;
    }

    private static int ipoApply(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        double ratio = DoubleArgumentType.getDouble(context, "ratio");
        List<CompanyStore.Company> mine = ctx().companies.byCeo(account.uuid);
        if (mine.isEmpty()) {
            Messages.sendFail(source, "你还没有公司");
            return 0;
        }
        CompanyStore.Company company = mine.get(0);
        StringBuilder whyNot = new StringBuilder();
        if (!IpoEngine.canApply(ctx(), company, whyNot)) {
            Messages.sendFail(source, "还不能 IPO：" + whyNot);
            return 0;
        }
        String uuid = account.uuid;
        Messages.sendInfo(source, "正在准备招股书…");
        if (ctx().ai == null || ctx().aiSubmitter == null || !ctx().settings.aiEnabled) {
            IpoEngine.apply(ctx(), account, ratio, null);
            Messages.sendOk(source, "招股书已提交（本地模板），1 个交易日后 /ex ipo confirm 上市");
            return Command.SINGLE_SUCCESS;
        }
        JsonObject request = IpoEngine.buildProspectusContext(ctx(), company, ratio);
        ctx().aiSubmitter.submit("prospectus", () -> ctx().ai.prospectus(request), data -> {
            AimcContext fresh = ctx();
            if (fresh == null) {
                return;
            }
            Account ceo = fresh.accounts.get(uuid);
            if (ceo == null) {
                return;
            }
            var server = com.mogutofu.aimcmarket.AimcmarketMod.server();
            var player = server == null ? null : server.getPlayerList().getPlayer(UUID.fromString(uuid));
            JsonObject prospectus = data == null ? null : AiJson.validateProspectus(data);
            ActionResult result = IpoEngine.apply(fresh, ceo, ratio, prospectus);
            if (player != null) {
                if (result.ok) {
                    player.sendSystemMessage(Messages.ok(result.message));
                } else {
                    player.sendSystemMessage(Messages.fail(result.message));
                }
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int ipoConfirm(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        List<CompanyStore.Company> mine = ctx().companies.byCeo(account.uuid);
        if (mine.isEmpty()) {
            Messages.sendFail(source, "你还没有公司");
            return 0;
        }
        CompanyStore.Company company = mine.get(0);
        send(source, IpoEngine.confirm(ctx(), account));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    // ── 期货 ──

    private static int futuresList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (var entry : FuturesStore.SYMBOLS.entrySet()) {
            lines.add(entry.getKey() + " 现货 " + Messages.money(TradeService.effectiveAskCents(ctx(), entry.getValue()))
                    + "（1 手 = 64 个）");
        }
        Messages.sendPage(source, lines, 1, PER_PAGE, "/ex fut list");
        Messages.sendInfo(source, "到期可选：7 / 14 / 30 交易日｜/ex fut quote <标的> <天数> 看价格");
        return Command.SINGLE_SUCCESS;
    }

    private static int futuresQuote(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        send(source, FuturesEngine.quote(ctx(), StringArgumentType.getString(context, "symbol"),
                IntegerArgumentType.getInteger(context, "days")));
        return Command.SINGLE_SUCCESS;
    }

    private static int futuresOpen(CommandContext<CommandSourceStack> context, boolean longPosition) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, FuturesEngine.open(ctx(), account, StringArgumentType.getString(context, "symbol"), longPosition,
                LongArgumentType.getLong(context, "lots"), IntegerArgumentType.getInteger(context, "days")));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int futuresClose(CommandContext<CommandSourceStack> context, String id) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, FuturesEngine.close(ctx(), account, id));
        return Command.SINGLE_SUCCESS;
    }

    private static int futuresPositions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        Messages.sendLines(source, FuturesEngine.positionsText(ctx(), account));
        return Command.SINGLE_SUCCESS;
    }

    private static int futuresMargin(CommandContext<CommandSourceStack> context, String id, String amountText, boolean add) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long cents = amount(amountText);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对");
            return 0;
        }
        send(source, add ? FuturesEngine.addMargin(ctx(), account, id, cents)
                : FuturesEngine.withdrawMargin(ctx(), account, id, cents));
        return Command.SINGLE_SUCCESS;
    }

    // ── 期权 ──

    private static int optionsList(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (StockStore.Stock stock : ctx().stocks.all()) {
            lines.add(stock.code + " " + stock.name + " 现价 " + Messages.money(stock.priceCents)
                    + "（期权标的：call/put，1 张 = 100 股）");
        }
        Messages.sendPage(source, lines, page, PER_PAGE, "/ex opt list");
        return Command.SINGLE_SUCCESS;
    }

    private static int optionsQuote(CommandContext<CommandSourceStack> context, String code, String strikeText, int days) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        long strike = amount(strikeText);
        if (strike <= 0L) {
            Messages.sendFail(source, "行权价格式不对，例：/ex opt quote BLK 126 7");
            return 0;
        }
        send(source, OptionsEngine.quote(ctx(), code, strike, days));
        return Command.SINGLE_SUCCESS;
    }

    private static int optionsOpen(CommandContext<CommandSourceStack> context, boolean writer) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        long strike = amount(StringArgumentType.getString(context, "strike"));
        if (strike <= 0L) {
            Messages.sendFail(source, "行权价格式不对");
            return 0;
        }
        send(source, OptionsEngine.open(ctx(), account, writer,
                StringArgumentType.getString(context, "callOrPut"),
                StringArgumentType.getString(context, "code"),
                strike,
                IntegerArgumentType.getInteger(context, "days"),
                LongArgumentType.getLong(context, "contracts")));
        tickGovernment(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int optionsClose(CommandContext<CommandSourceStack> context, String id) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        send(source, OptionsEngine.close(ctx(), account, id));
        return Command.SINGLE_SUCCESS;
    }

    private static int optionsPositions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        Messages.sendLines(source, OptionsEngine.positionsText(ctx(), account));
        return Command.SINGLE_SUCCESS;
    }

    // ── 政府 ──

    private static String govStatusText() {
        AimcContext ctx = ctx();
        GovernmentStore gov = ctx.government;
        var ind = gov.indicators;
        return "政策利率 " + Messages.percent(gov.policyRate)
                + "｜交易税 " + Messages.percent(gov.effectiveTradeTax())
                + "｜公司税 " + Messages.percent(gov.effectiveCompanyTax())
                + "｜预算 " + Messages.money(gov.budgetCents())
                + "\n通胀 7 日 " + Messages.changeText(ind.inflation7d)
                + "｜大盘 7 日 " + Messages.changeText(ind.indexChange7d)
                + "｜就业 " + Messages.percent(ind.employment)
                + "｜压力分 " + ind.stress
                + "\n货币总量 " + Messages.money(ind.moneyCents)
                + "｜中位财富 " + Messages.money(ind.medianWealthCents)
                + "｜7 日成交额 " + Messages.money(ind.volume7dCents);
    }

    private static int govStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        GovernmentEngine.updateIndicators(ctx(), ctx().onlineAccounts);
        Messages.sendLines(source, govStatusText());
        return Command.SINGLE_SUCCESS;
    }

    private static int govBudget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        GovernmentStore gov = ctx().government;
        Messages.send(source, "政府预算 " + Messages.money(gov.budgetCents())
                + "｜政策利率 " + Messages.percent(gov.policyRate));
        Messages.sendInfo(source, "税收进预算，福利/刺激等政策从预算出；预算不足时政策会被拒绝");
        return Command.SINGLE_SUCCESS;
    }

    private static int govPolicies(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (var policy : ctx().government.policies()) {
            if (!ctx().government.isActive(policy, ctx().tradingDay())) {
                continue;
            }
            lines.add(policy.action + " " + policy.paramsString()
                    + (policy.expiresDay >= 0 ? "（到第 " + policy.expiresDay + " 交易日）" : ""));
        }
        if (lines.isEmpty()) {
            Messages.sendInfo(source, "当前没有生效中的政策");
        } else {
            Messages.sendPage(source, lines, 1, PER_PAGE, "/ex gov policies");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int govHistory(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (var policy : ctx().government.recentPolicies(20)) {
            lines.add("#" + policy.id + " " + policy.action + " " + policy.paramsString()
                    + (policy.reason.isBlank() ? "" : " — " + policy.reason));
        }
        Messages.sendPage(source, lines, 1, 10, "/ex gov history");
        return Command.SINGLE_SUCCESS;
    }

    // ── 工具 ──

    private static int giveItem(ServerPlayer player, String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (item == Items.AIR) {
            return count;
        }
        int left = count;
        while (left > 0) {
            int stackSize = Math.min(left, item.getMaxStackSize());
            ItemStack stack = new ItemStack(item, stackSize);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            left -= stackSize;
        }
        return 0;
    }

    private static int countItem(ServerPlayer player, String itemId) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (item == Items.AIR) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeItem(ServerPlayer player, String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (item == Items.AIR) {
            return;
        }
        int left = count;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && left > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
    }

    private static List<String> readLedgerFor(String actor, int limit) {
        List<String> lines = new ArrayList<>();
        try {
            java.nio.file.Path file = ctx().ledger.file();
            if (!java.nio.file.Files.isRegularFile(file)) {
                return lines;
            }
            List<String> all = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            for (int i = all.size() - 1; i >= 0 && lines.size() < limit; i--) {
                if (!all.get(i).contains("\"actor\":\"" + actor + "\"")) {
                    continue;
                }
                try {
                    var obj = com.google.gson.JsonParser.parseString(all.get(i)).getAsJsonObject();
                    lines.add(obj.get("kind").getAsString() + " " + obj.get("memo").getAsString());
                } catch (RuntimeException ignored) {
                    // 跳过坏行
                }
            }
        } catch (Exception error) {
            // 读不到就算了
        }
        return lines;
    }

    // ── 补全 ──

    private static SuggestionProvider<CommandSourceStack> itemNames() {
        return (context, builder) -> {
            AimcContext ctx = ctx();
            if (ctx == null) {
                return builder.buildFuture();
            }
            for (MarketStore.CatalogItem item : ctx.market.items()) {
                builder.suggest(item.id);
                if (!item.nameZh.isBlank()) {
                    builder.suggest('"' + item.nameZh + '"');
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> stockCodes() {
        return (context, builder) -> {
            AimcContext ctx = ctx();
            if (ctx != null) {
                for (StockStore.Stock stock : ctx.stocks.all()) {
                    builder.suggest(stock.code);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> fundCodes() {
        return (context, builder) -> {
            AimcContext ctx = ctx();
            if (ctx != null) {
                for (FundStore.Fund fund : ctx.funds.all()) {
                    builder.suggest(fund.code);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> playerNames() {
        return (context, builder) -> {
            AimcContext ctx = ctx();
            if (ctx != null && context.getSource().getServer() != null) {
                SharedSuggestionProvider.suggest(
                        context.getSource().getServer().getPlayerNames(), builder);
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> companyNames() {
        return (context, builder) -> {
            AimcContext ctx = ctx();
            var player = context.getSource().getPlayer();
            if (ctx != null && player != null) {
                for (CompanyStore.Company company : ctx.companies.byCeo(player.getUUID().toString())) {
                    builder.suggest('"' + company.name + '"');
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> sectorNames() {
        return (context, builder) -> {
            for (String name : CompanyStore.SECTORS.values()) {
                builder.suggest(name);
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> callPutSuggestions() {
        return (context, builder) -> {
            builder.suggest("call");
            builder.suggest("put");
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> futureSymbols() {
        return (context, builder) -> {
            for (String symbol : FuturesStore.SYMBOLS.keySet()) {
                builder.suggest(symbol);
            }
            return builder.buildFuture();
        };
    }

    /** 内部小工具：给 board 页发买入按钮。 */
    private static final class MutableComponentHelper {
        private static void sendBuyButton(CommandSourceStack source, String name, String itemId) {
            var button = Messages.button("[" + name + "]", "/ex buy " + itemId + " 1",
                    net.minecraft.ChatFormatting.YELLOW);
            source.sendSuccess(() -> button, false);
        }
    }
}
