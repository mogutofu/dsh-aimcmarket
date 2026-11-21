package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.AimcmarketMod;
import com.mogutofu.aimcmarket.config.MarketConfig;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.engine.GovernmentEngine;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Cents;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /ex admin ...} 管理命令（需要 OP，level 2+）。
 *
 * <p>所有管理操作都写 ledger（带操作者名字），方便对账时查是谁动的钱。</p>
 */
public final class AdminCommand {
    private AdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"ex", "exchange", "market"}) {
            dispatcher.register(Commands.literal(root).then(adminBuilder()));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminBuilder() {
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(source -> source.hasPermission(2));
        admin.then(Commands.literal("setprice")
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(AdminCommand::setPrice)));
        admin.then(Commands.literal("give")
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> money(context, true))));
        admin.then(Commands.literal("take")
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> money(context, false))));
        admin.then(Commands.literal("freeze")
                .then(Commands.argument("frozen", BoolArgumentType.bool())
                        .executes(AdminCommand::freeze)));
        admin.then(Commands.literal("reload").executes(AdminCommand::reload));
        admin.then(Commands.literal("save").executes(AdminCommand::save));
        admin.then(Commands.literal("syncstocks").executes(AdminCommand::syncStocks));
        admin.then(Commands.literal("stats").executes(AdminCommand::stats));
        admin.then(Commands.literal("selftest").executes(AdminCommand::selfTest));
        admin.then(Commands.literal("cmdtree").executes(AdminCommand::cmdTree));
        admin.then(Commands.literal("gov")
                .then(Commands.literal("set")
                        .then(Commands.argument("field", StringArgumentType.word()).suggests(govFields())
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(AdminCommand::govSet)))));
        return admin;
    }

    private static AimcContext ctx() {
        return AimcmarketMod.context();
    }

    private static boolean ready(CommandSourceStack source) {
        if (ctx() == null) {
            Messages.sendFail(source, "市场还没初始化");
            return false;
        }
        return true;
    }

    /** 拆出最后一段（"铁锭 12.5" → ["铁锭","12.5"]）。 */
    private static String[] splitLast(String text) {
        String trimmed = text == null ? "" : text.trim();
        int index = trimmed.lastIndexOf(' ');
        if (index <= 0 || index == trimmed.length() - 1) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, index).trim(), trimmed.substring(index + 1).trim()};
    }

    private static int setPrice(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        String[] parts = splitLast(StringArgumentType.getString(context, "args"));
        MarketStore.CatalogItem item = ctx().market.resolve(parts[0]);
        if (item == null) {
            Messages.sendFail(source, "目录里没有这个物品（用法：/ex admin setprice <物品> <价格>）");
            return 0;
        }
        long cents = Cents.parse(parts[1]);
        if (cents <= 0L) {
            Messages.sendFail(source, "价格格式不对，单位是币，例：/ex admin setprice 铁锭 12.5");
            return 0;
        }
        ctx().market.setPrice(item.id, cents);
        Messages.sendOk(source, item.nameZh + " 现价改为 " + Messages.money(cents));
        return Command.SINGLE_SUCCESS;
    }

    private static int money(CommandContext<CommandSourceStack> context, boolean give) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        String[] parts = splitLast(StringArgumentType.getString(context, "args"));
        Account target = ctx().accounts.findByName(parts[0]).orElse(null);
        if (target == null) {
            Messages.sendFail(source, "找不到玩家（用法：/ex admin " + (give ? "give" : "take") + " <玩家> <金额>）");
            return 0;
        }
        long cents = Cents.parse(parts[1]);
        if (cents <= 0L) {
            Messages.sendFail(source, "金额格式不对");
            return 0;
        }
        String operator = source.getTextName();
        try {
            if (give) {
                ctx().ledger.issue(target, cents, "admin.give", operator, "管理员发钱");
            } else {
                if (target.balanceCents() < cents) {
                    Messages.sendFail(source, "对方余额不足");
                    return 0;
                }
                ctx().ledger.burn(target, cents, "admin.take", operator, "管理员扣钱");
            }
            Messages.sendOk(source, (give ? "发给 " : "从 ") + target.name
                    + (give ? " " : " 扣除 ") + Messages.money(cents)
                    + "，现有 " + Messages.money(target.balanceCents()));
        } catch (RuntimeException error) {
            Messages.sendFail(source, "操作失败：" + error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int freeze(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        boolean frozen = BoolArgumentType.getBool(context, "frozen");
        ctx().settings.adminFreeze = frozen;
        Messages.sendOk(source, frozen ? "交易已冻结（只影响新下单）" : "交易已恢复");
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        try {
            var settings = AimcmarketMod.reloadSettings();
            ctx().market.reloadCatalog();
            Messages.sendOk(source, "配置已热加载：tick " + settings.marketTickMinutes + " 分钟，交易日 "
                    + settings.tradingDayMinutes + " 分钟，AI " + (settings.aiEnabled ? "开" : "关")
                    + "（" + settings.aiMode + "），商品目录 " + ctx().market.items().size() + " 项");
        } catch (RuntimeException error) {
            Messages.sendFail(source, "热加载失败：" + error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int save(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        try {
            ctx().saveAll();
            Messages.sendOk(source, "已落盘到 world/aimcmarket/");
        } catch (RuntimeException error) {
            Messages.sendFail(source, "落盘失败：" + error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        AimcContext ctx = ctx();
        long now = System.currentTimeMillis();
        long uptimeMinutes = Math.max(1L, (now - ctx.loadedAtMs) / 60_000L);
        Ledger.ReplayResult replay = ctx.reconcile();
        Messages.send(source, "总货币 " + Messages.money(ctx.totalMoneyCents())
                + "｜本 tick 流水 " + ctx.ledger.seqValue() + " 笔"
                + "｜运行 " + uptimeMinutes + " 分钟");
        long perMinute = (ctx.totalMoneyCents() - 0L) / uptimeMinutes;
        Messages.sendInfo(source, "平均每分钟净增（粗算） " + Messages.money(perMinute)
                + "｜成交额 7 日 " + Messages.money(ctx.market.volume7Days(ctx.tradingDay())));
        Messages.sendInfo(source, "账户：钱包 " + ctx.accounts.all().size() + " 个"
                + "｜公司 " + ctx.companies.all().size() + " 家"
                + "｜股票 " + ctx.stocks.all().size() + " 只"
                + "｜期货持仓 " + ctx.futures.all().size()
                + "｜期权持仓 " + ctx.options.all().size());
        Messages.sendInfo(source, "账本恒等式：" + (ctx.ledger.isBalanced() ? "平衡" : "不平衡（有 bug）")
                + "｜重放核对：" + (replay.ok() ? "一致" : ("不一致 " + replay.mismatches + " 处 " + replay.detail)));
        List<String> top = topAssets();
        Messages.sendInfo(source, "—— 资产 Top 10 ——");
        for (String line : top) {
            Messages.sendInfo(source, line);
        }
        Messages.sendInfo(source, com.mogutofu.aimcmarket.engine.MarketPricing.describe(ctx));
        Messages.sendInfo(source, "AI 链路：" + (ctx.ai == null ? "关闭" : ctx.settings.aiMode
                + "（最近 " + ctx.ai.lastRoute() + "）") + "｜预算 " + ctx.settings.aiHourlyBudget + " 次/小时");
        return Command.SINGLE_SUCCESS;
    }

    /** /ex admin syncstocks：立刻同步一次现实行情。 */
    private static int syncStocks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        com.mogutofu.aimcmarket.engine.RealStockRunner runner = com.mogutofu.aimcmarket.AimcmarketMod.realStockRunner();
        if (runner == null) {
            Messages.sendFail(source, "现实股票同步未启用：检查 config/aimcmarket/real-stocks.json 后重启服务器");
            return 0;
        }
        runner.forceSync();
        Messages.sendOk(source, "已触发现实行情同步，几秒后生效");
        Messages.sendInfo(source, runner.summary());
        return Command.SINGLE_SUCCESS;
    }

    private static List<String> topAssets() {
        AimcContext ctx = ctx();
        List<Map.Entry<String, Long>> values = new ArrayList<>();
        for (Account account : ctx.accounts.all()) {
            long value = account.balanceCents() + account.bank.totalDepositsCents();
            for (var entry : account.stocks.entrySet()) {
                value += ctx.stocks.priceOf(entry.getKey()) * entry.getValue().shares;
            }
            for (var entry : account.funds.entrySet()) {
                value += ctx.funds.valueOfUnits(entry.getKey(), entry.getValue().unitsMilli);
            }
            values.add(Map.entry(account.name, value));
        }
        values.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Long> entry : values) {
            if (rank > 10) {
                break;
            }
            lines.add("Top" + rank + " " + entry.getKey() + " " + Messages.money(entry.getValue()));
            rank++;
        }
        return lines;
    }

    private static int selfTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Messages.sendInfo(source, "开始自检（会新建一个独立测试环境，不碰线上数据）…");
        SelfTest.Result result;
        try {
            result = SelfTest.run(ctx());
        } catch (Throwable error) {
            Messages.sendFail(source, "自检异常：" + error);
            org.slf4j.LoggerFactory.getLogger("AIMCmarket").error("selftest 异常", error);
            return 0;
        }
        for (String line : result.lines) {
            Messages.sendRaw(source, net.minecraft.network.chat.Component.literal(line));
        }
        if (result.failed == 0) {
            Messages.sendOk(source, "SELFTEST PASS（" + result.passed + " 项，耗时 " + result.elapsedMs + "ms）");
            net.minecraft.server.MinecraftServer server = source.getServer();
            if (server != null) {
                org.slf4j.LoggerFactory.getLogger("AIMCmarket")
                        .info("SELFTEST PASS（{} 项，耗时 {}ms）", result.passed, result.elapsedMs);
            }
            return Command.SINGLE_SUCCESS;
        }
        org.slf4j.LoggerFactory.getLogger("AIMCmarket")
                .error("SELFTEST FAIL：{} 项失败（共 {} 项，耗时 {}ms）", result.failed, result.passed + result.failed, result.elapsedMs);
        Messages.sendFail(source, "SELFTEST FAIL：失败 " + result.failed + " 项（共 "
                + (result.passed + result.failed) + " 项，耗时 " + result.elapsedMs + "ms）");
        return 0;
    }

    /** 自检：命令树能否编码成网络包（编码失败 = 玩家进服会被踢）。 */
    private static int cmdTree(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            com.mojang.brigadier.tree.RootCommandNode root = source.getServer().getCommands().getDispatcher().getRoot();
            @SuppressWarnings("rawtypes")
            var packet = new net.minecraft.network.protocol.game.ClientboundCommandsPacket(
                    (com.mojang.brigadier.tree.RootCommandNode<net.minecraft.commands.SharedSuggestionProvider>) root);
            if (packet == null) {
                throw new IllegalStateException("packet 为空");
            }
            Messages.sendOk(source, "命令树编码正常，客户端可以正常进服");
            return Command.SINGLE_SUCCESS;
        } catch (Throwable error) {
            Messages.sendFail(source, "命令树编码失败（玩家会进不去服）：" + error);
            return 0;
        }
    }
    private static int govSet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        String field = StringArgumentType.getString(context, "field").toLowerCase(Locale.ROOT);
        String valueText = StringArgumentType.getString(context, "value");
        GovernmentStore gov = ctx().government;
        try {
            switch (field) {
                case "policyrate" -> {
                    double value = Double.parseDouble(valueText.trim());
                    if (value < 0.0 || value > 0.10) {
                        Messages.sendFail(source, "政策利率要在 0-10% 之间，用小数（0.03 = 3%）");
                        return 0;
                    }
                    gov.setPolicyRate(value);
                    Messages.sendOk(source, "政策利率设为 " + Messages.percent(value));
                }
                case "tradetax" -> {
                    double value = Double.parseDouble(valueText.trim());
                    if (value < 0.0 || value > 0.05) {
                        Messages.sendFail(source, "交易税要在 0-5% 之间，用小数（0.005 = 0.5%）");
                        return 0;
                    }
                    gov.setTradeTax(value);
                    Messages.sendOk(source, "交易税设为 " + Messages.percent(value));
                }
                case "companytax" -> {
                    double value = Double.parseDouble(valueText.trim());
                    if (value < 0.0 || value > 0.50) {
                        Messages.sendFail(source, "公司税要在 0-50% 之间，用小数（0.10 = 10%）");
                        return 0;
                    }
                    gov.setCompanyTax(value);
                    Messages.sendOk(source, "公司税设为 " + Messages.percent(value));
                }
                case "budget" -> {
                    long cents = Cents.parse(valueText);
                    if (cents < 0L) {
                        Messages.sendFail(source, "预算金额格式不对");
                        return 0;
                    }
                    long delta = cents - gov.budgetCents();
                    if (delta > 0L) {
                        ctx().ledger.issue(gov.budgetAccount(), delta, "admin.govBudget", source.getTextName(), "管理员调预算");
                    } else if (delta < 0L) {
                        ctx().ledger.burn(gov.budgetAccount(), -delta, "admin.govBudget", source.getTextName(), "管理员调预算");
                    }
                    Messages.sendOk(source, "政府预算设为 " + Messages.money(gov.budgetCents()));
                }
                default -> Messages.sendFail(source, "可设置字段：policyrate｜tradetax｜companytax｜budget");
            }
        } catch (NumberFormatException error) {
            Messages.sendFail(source, "数值格式不对：" + valueText);
        } catch (RuntimeException error) {
            Messages.sendFail(source, "设置失败：" + error.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    // ── 补全 ──

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> itemNames() {
        return (context, builder) -> {
            if (ctx() != null) {
                for (MarketStore.CatalogItem item : ctx().market.items()) {
                    builder.suggest(item.id);
                }
            }
            return builder.buildFuture();
        };
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> playerNames() {
        return (context, builder) -> {
            if (context.getSource().getServer() != null) {
                SharedSuggestionProvider.suggest(context.getSource().getServer().getPlayerNames(), builder);
            }
            return builder.buildFuture();
        };
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> govFields() {
        return (context, builder) -> {
            builder.suggest("policyRate");
            builder.suggest("tradeTax");
            builder.suggest("companyTax");
            builder.suggest("budget");
            return builder.buildFuture();
        };
    }
}
