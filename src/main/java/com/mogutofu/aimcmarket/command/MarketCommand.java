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
        root.then(Commands.literal("id").executes(MarketCommand::heldItemInfo));
        root.then(Commands.literal("return").then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(MarketCommand::returnArgs)));
        root.then(Commands.literal("alias").then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(MarketCommand::aliasHeld)));
        root.then(Commands.literal("balance").executes(MarketCommand::balance));
        // 中文参数统一走 greedyString：Brigadier 的 word()/string() 只认 ASCII，中文会报"尾随数据"。
        root.then(Commands.literal("pay").then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(MarketCommand::payArgs)));
        root.then(Commands.literal("work").executes(MarketCommand::work));
        root.then(Commands.literal("daily").executes(MarketCommand::daily));
        root.then(Commands.literal("buy").then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(context -> buyArgs(context, true))));
        root.then(Commands.literal("sell").then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(context -> buyArgs(context, false))));
        root.then(Commands.literal("price").then(Commands.argument("item", StringArgumentType.greedyString())
                .executes(MarketCommand::priceArgs)));
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
                .then(Commands.literal("create").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(MarketCommand::companyCreateArgs)))
                .then(Commands.literal("deposit").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> companyMoneyArgs(context, true))))
                .then(Commands.literal("withdraw").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> companyMoneyArgs(context, false))))
                .then(Commands.literal("hire").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> companyHireArgs(context, true))))
                .then(Commands.literal("fire").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> companyHireArgs(context, false))))
                .then(Commands.literal("levelup").then(Commands.argument("company", StringArgumentType.greedyString())
                        .executes(context -> companyLevelUp(context, StringArgumentType.getString(context, "company")))))
                .then(Commands.literal("dividend").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(MarketCommand::companyDividendArgs)))
                .then(Commands.literal("sector").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(MarketCommand::companySectorArgs)))
                .then(Commands.literal("close").then(Commands.argument("company", StringArgumentType.greedyString())
                        .executes(context -> companyClose(context, StringArgumentType.getString(context, "company")))))
                .then(Commands.literal("raise").then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(MarketCommand::companyRaiseArgs)))
                .then(Commands.literal("info")
                        .executes(context -> companyInfo(context, null))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(context -> companyInfo(context, StringArgumentType.getString(context, "company"))))));
        root.then(Commands.literal("ipo")
                .then(Commands.literal("status").executes(context -> ipoStatus(context, null))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
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

    // ── 中文参数解析（greedyString 拆参）──

    /** 取 greedy 的整段文本。 */
    private static String text(CommandContext<CommandSourceStack> context, String name) {
        String value = StringArgumentType.getString(context, name);
        return value == null ? "" : value.trim();
    }

    /** 拆出"最后一段"和"前面剩余"（例如 "铁锭 3" → ["铁锭","3"]）。 */
    private static String[] splitLast(String text) {
        String trimmed = text.trim();
        int index = trimmed.lastIndexOf(' ');
        if (index <= 0 || index == trimmed.length() - 1) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, index).trim(), trimmed.substring(index + 1).trim()};
    }

    private static int parsePositiveInt(String value, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 1 && parsed <= max ? parsed : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static int payArgs(CommandContext<CommandSourceStack> context) {
        String[] parts = splitLast(text(context, "args"));
        if (parts[1].isEmpty()) {
            Messages.sendFail(context.getSource(), "用法：/ex pay <玩家> <金额>，例：/ex pay Steve 12.5");
            return 0;
        }
        return pay(context, parts[0], parts[1]);
    }

    private static int buyArgs(CommandContext<CommandSourceStack> context, boolean buying) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        String raw = text(context, "args");
        String[] parts = splitLast(raw);
        String itemText;
        int count;
        int parsed = parts[1].isEmpty() ? -1 : parsePositiveInt(parts[1], 576);
        if (parsed > 0) {
            itemText = parts[0];
            count = parsed;
        } else {
            itemText = raw;
            count = 1;
        }
        // "手上的/这个"：直接用玩家手里的物品
        if (isHandKeyword(itemText)) {
            String handId = handItemId(source.getPlayer());
            if (handId == null) {
                Messages.sendFail(source, "你主手没拿物品，先拿一个再试");
                return 0;
            }
            MarketStore.CatalogItem handItem = ctx().market.item(handId);
            if (handItem != null) {
                performBuySell(source, account, handItem, count, buying);
            } else {
                ensureCatalogItem(source, handId, created -> performBuySell(source, account, created, count, buying));
            }
            return Command.SINGLE_SUCCESS;
        }
        MarketStore.CatalogItem item = ctx().market.resolve(itemText);
        if (item != null) {
            performBuySell(source, account, item, count, buying);
            return Command.SINGLE_SUCCESS;
        }
        // 目录里没有：先看是不是已注册的物品（含模组物品），是就让 AI 定价后上市再成交
        ensureCatalogItem(source, itemText, created -> performBuySell(source, account, created, count, buying));
        return Command.SINGLE_SUCCESS;
    }

    /** AI 推断中文名对应的物品 id：推断 → 认商品 → 估价上市 → 执行动作。 */
    private static void aiResolveItemAndCreate(CommandSourceStack source, String zhName,
                                               java.util.function.Consumer<MarketStore.CatalogItem> action) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        Messages.sendInfo(source, "市场先生正在查「" + zhName + "」对应的物品 id…");
        JsonObject idContext = new JsonObject();
        JsonArray namespaces = new JsonArray();
        int count = 0;
        for (String namespace : ItemResolver.namespaces()) {
            if (count++ >= 80) {
                break;
            }
            namespaces.add(namespace);
        }
        idContext.add("namespaces", namespaces);
        idContext.addProperty("catalogItems", ctx.market.items().size());
        ctx.aiSubmitter.submit("itemid", () -> ctx.ai.resolveItemId(zhName, idContext), guess -> {
            AimcContext current = ctx();
            if (current == null) {
                return;
            }
            String picked = pickItemId(guess);
            if (picked == null) {
                Messages.sendFail(source, "AI 也推断不出「" + zhName + "」对应的物品。");
                Messages.sendInfo(source, "这通常说明这个物品只存在于客户端（服务端没装对应模组），服务端无法交易它。"
                        + "如果它确实在服务器里，把物品拿在主手用 /ex 买手上的 1，或 /ex id 看 id。");
                java.util.List<ItemResolver.Candidate> candidates = candidateList(guess);
                if (!candidates.isEmpty()) {
                    StringBuilder line = new StringBuilder("AI 猜测的候选：");
                    int shown = 0;
                    for (ItemResolver.Candidate candidate : candidates) {
                        if (shown++ >= 4) {
                            break;
                        }
                        line.append(candidate.id);
                        if (candidate.nameEn != null && !candidate.nameEn.isBlank()) {
                            line.append("（").append(candidate.nameEn).append("）");
                        }
                        line.append("；");
                    }
                    line.append("可用 /ex buy <id> 1 试买");
                    Messages.sendInfo(source, line.toString());
                }
                return;
            }
            ItemResolver.Found found = ItemResolver.byId(picked);
            if (found == null) {
                Messages.sendFail(source, "AI 猜的物品 id 不存在：" + picked);
                return;
            }
            Messages.sendInfo(source, "认出是「" + found.displayName() + "」（" + found.id + "），正在定价…");
            JsonObject priceContext = buildPriceContext(found);
            String finalId = found.id;
            current.aiSubmitter.submit("price",
                    () -> {
                        long cents = current.ai.estimatePriceCents(finalId, found.displayName(), priceContext);
                        JsonObject wrapper = new JsonObject();
                        wrapper.addProperty("priceCents", cents);
                        return wrapper;
                    },
                    data -> {
                        long cents = data != null && data.has("priceCents") ? data.get("priceCents").getAsLong() : -1L;
                        if (cents <= 0L) {
                            cents = heuristicPrice(found);
                        }
                        AimcContext latest = ctx();
                        if (latest == null) {
                            return;
                        }
                        MarketStore.CatalogItem item = latest.market.hasItem(finalId)
                                ? latest.market.item(finalId)
                                : latest.market.addItem(finalId, zhName,
                                new java.util.ArrayList<>(java.util.Collections.singletonList(
                                        found.nameEn == null ? "" : found.nameEn)),
                                cents, 0.05, 12_000L);
                        latest.market.addAlias(finalId, zhName);
                        action.accept(item);
                    });
        });
    }

    /** 从 AI 推断结果里挑一个能验证存在的 id；太模糊就返回 null。 */
    private static String pickItemId(JsonObject guess) {
        if (guess == null) {
            return null;
        }
        java.util.List<String> keywords = new java.util.ArrayList<>();
        if (guess.has("keywords") && guess.get("keywords").isJsonArray()) {
            for (var element : guess.getAsJsonArray("keywords")) {
                try {
                    keywords.add(element.getAsString().toLowerCase(java.util.Locale.ROOT));
                } catch (RuntimeException ignored) {
                    // 跳过坏数据
                }
            }
        }
        // 1) AI 直接给了 candidates：只保留真实注册的，并按关键词相似度选最像的
        if (guess.has("candidates") && guess.get("candidates").isJsonArray()) {
            java.util.List<String> existing = new java.util.ArrayList<>();
            for (var element : guess.getAsJsonArray("candidates")) {
                try {
                    String id = element.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (ItemResolver.byId(id) != null) {
                        existing.add(id);
                    }
                } catch (RuntimeException ignored) {
                    // 单项格式不对跳过
                }
            }
            if (existing.size() == 1) {
                return existing.get(0);
            }
            if (!existing.isEmpty()) {
                String best = existing.get(0);
                int bestScore = -1;
                for (String id : existing) {
                    int score = keywordScore(id, keywords);
                    if (score > bestScore) {
                        bestScore = score;
                        best = id;
                    }
                }
                return best;
            }
        }
        // 2) 用 keywords 去注册表里匹配
        java.util.List<ItemResolver.Candidate> candidates = candidateList(guess);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0).id;
        }
        int top = candidates.get(0).score;
        int second = candidates.get(1).score;
        if (top >= 4 && (top >= second * 2 || top >= 8)) {
            return candidates.get(0).id;
        }
        return null;
    }

    /** 物品 id / 英文名 与关键词的相似度打分。 */
    private static int keywordScore(String id, java.util.List<String> keywords) {
        String path = id.substring(id.indexOf(':') + 1);
        String en = ItemResolver.englishName(id).toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (keyword.length() < 3) {
                continue;
            }
            if (path.contains(keyword)) {
                score += keyword.length() >= 6 ? 3 : 2;
            }
            if (en.contains(keyword)) {
                score += 2;
            }
        }
        return score;
    }

    private static java.util.List<ItemResolver.Candidate> candidateList(JsonObject guess) {
        if (guess == null || !guess.has("keywords") || !guess.get("keywords").isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (var element : guess.getAsJsonArray("keywords")) {
            try {
                tokens.add(element.getAsString());
            } catch (RuntimeException ignored) {
                // 跳过坏数据
            }
        }
        return ItemResolver.searchByTokens(tokens);
    }

    /** 记一条买入（退货用）。 */
    private static void recordPurchase(Account account, MarketStore.CatalogItem item, int count) {
        AimcContext ctx = ctx();
        if (ctx == null || account == null || item == null || count <= 0) {
            return;
        }
        try {
            ctx.purchases.record(account.uuid, item.id, count,
                    TradeService.effectiveAskCents(ctx, item.id), ctx.nowMs());
        } catch (RuntimeException error) {
            com.mogutofu.aimcmarket.util.Log.warn("记录买入失败（这单可能无法退货）", error);
        }
    }

    /** 收购：按当前市场价收玩家背包里的物品（AI 刚定价的物品也算）。 */
    private static void acquireFromPlayer(CommandSourceStack source, Account account,
                                         MarketStore.CatalogItem item, int requested) {
        if (!ready(source) || item == null) {
            return;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "你已经下线了，收购取消");
            return;
        }
        int available = countItem(player, item.id);
        int count = requested > 0 ? Math.min(requested, available) : available;
        if (count <= 0) {
            Messages.sendFail(source, "背包里没有 " + item.nameZh);
            return;
        }
        long unit = ctx().market.price(item.id);
        if (unit <= 0L) {
            unit = item.basePriceCents;
        }
        ActionResult result = TradeService.sellWithPrice(ctx(), account, item.id, count, unit,
                "收购", "trade.buyback");
        if (!result.ok) {
            send(source, result);
            return;
        }
        removeItem(player, item.id, count);
        send(source, result);
        tickGovernment(source);
    }

    /** 退货：7 天内买的物品按原买入价退款（只退背包里还在的部分）。 */
    private static void returnItem(CommandSourceStack source, Account account,
                                   MarketStore.CatalogItem item, int requested) {
        if (!ready(source) || item == null) {
            return;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "你已经下线了，退货取消");
            return;
        }
        AimcContext ctx = ctx();
        long now = ctx.nowMs();
        int available = countItem(player, item.id);
        int returnable = ctx.purchases.returnable(account.uuid, item.id, now);
        int limit = Math.min(available, returnable);
        int count = requested > 0 ? Math.min(requested, limit) : limit;
        if (count <= 0) {
            if (returnable <= 0) {
                Messages.sendFail(source, "没有可退货的「" + item.nameZh + "」记录（只支持 7 天内在本市场买的）。");
                Messages.sendInfo(source, "想按市价卖可以走收购：/ex 把背包里的 " + item.nameZh + " 收购掉");
            } else {
                Messages.sendFail(source, "背包里只剩 " + available + " 个可退 " + Math.min(returnable, available) + " 个。");
            }
            return;
        }
        long refund = ctx.purchases.refund(account.uuid, item.id, count, now);
        if (refund <= 0L) {
            Messages.sendFail(source, "退货记录已失效，稍后再试");
            return;
        }
        try {
            ctx.ledger.issue(account, refund, "trade.return", account.name,
                    "退货 " + item.nameZh + " ×" + count + "，退款 " + com.mogutofu.aimcmarket.util.Cents.format(refund) + " 币");
        } catch (RuntimeException error) {
            Messages.sendFail(source, "退货记账失败：" + error.getMessage());
            return;
        }
        removeItem(player, item.id, count);
        player.sendSystemMessage(Messages.ok("退货 " + item.nameZh + " ×" + count + "，退款 "
                + Messages.money(refund) + " 币，余额 " + Messages.money(account.balanceCents()) + " 币"));
        tickGovernment(source);
    }

    /** /ex return <物品> [数量]：显式退货（数量省略=背包里可退的全退）。 */
    private static int returnArgs(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        Account account = player(source);
        if (account == null) {
            return 0;
        }
        String raw = text(context, "args").trim();
        if (raw.isEmpty()) {
            Messages.sendFail(source, "用法：/ex return <物品> [数量]，例：/ex return 铁锭 3");
            return 0;
        }
        int count = 0;
        String itemText = raw;
        String[] parts = raw.split("\s+");
        if (parts.length >= 2) {
            String last = parts[parts.length - 1];
            try {
                count = Integer.parseInt(last);
                itemText = raw.substring(0, raw.length() - last.length()).trim();
            } catch (NumberFormatException ignored) {
                count = 0;
            }
        }
        MarketStore.CatalogItem item = ctx().market.resolve(itemText);
        if (item == null) {
            Messages.sendFail(source, "目录里没有「" + itemText + "」，没买过的东西不能退。");
            return 0;
        }
        returnItem(source, account, item, count);
        return Command.SINGLE_SUCCESS;
    }

    /** 真正执行买/卖（目录里有没有都走这里）。 */
    private static void performBuySell(CommandSourceStack source, Account account, MarketStore.CatalogItem item,
                                       int count, boolean buying) {
        if (account == null || item == null) {
            return;
        }
        ServerPlayer mcPlayer = source.getPlayer();
        if (mcPlayer == null) {
            Messages.sendFail(source, "你已经下线了，交易取消");
            return;
        }
        if (buying) {
            ActionResult result = TradeService.buy(ctx(), account, item.id, count);
            if (!result.ok) {
                send(source, result);
                return;
            }
            recordPurchase(account, item, count);
            int left = giveItem(mcPlayer, item.id, count);
            if (left > 0) {
                Messages.sendInfo(source, "背包放不下 " + left + " 个，已掉在脚下");
            }
            send(source, result);
            tickGovernment(source);
            return;
        }
        int have = countItem(mcPlayer, item.id);
        if (have < count) {
            Messages.sendFail(source, "背包里只有 " + have + " 个 " + item.nameZh);
            return;
        }
        ActionResult result = TradeService.sell(ctx(), account, item.id, count);
        if (!result.ok) {
            send(source, result);
            return;
        }
        removeItem(mcPlayer, item.id, count);
        send(source, result);
        tickGovernment(source);
    }

    private static int priceArgs(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        String itemText = text(context, "item");
        if (isHandKeyword(itemText)) {
            String handId = handItemId(source.getPlayer());
            if (handId == null) {
                Messages.sendFail(source, "你主手没拿物品，先拿一个再试");
                return 0;
            }
            MarketStore.CatalogItem handItem = ctx().market.item(handId);
            if (handItem != null) {
                performPrice(source, handItem);
            } else {
                ensureCatalogItem(source, handId, created -> performPrice(source, created));
            }
            return Command.SINGLE_SUCCESS;
        }
        MarketStore.CatalogItem item = ctx().market.resolve(itemText);
        if (item != null) {
            performPrice(source, item);
            return Command.SINGLE_SUCCESS;
        }
        ensureCatalogItem(source, itemText, created -> performPrice(source, created));
        return Command.SINGLE_SUCCESS;
    }

    private static void performPrice(CommandSourceStack source, MarketStore.CatalogItem item) {
        long price = ctx().market.price(item.id);
        Messages.send(source, item.nameZh + "（" + item.id + "）现价 " + Messages.money(price)
                + "｜买价 " + Messages.money(TradeService.effectiveAskCents(ctx(), item.id))
                + "｜回收价 " + Messages.money(TradeService.effectiveBidCents(ctx(), item.id)));
        Messages.sendInfo(source, "基准价 " + Messages.money(item.basePriceCents)
                + "｜波动 " + Messages.percent(item.volatility) + "｜流动性 " + Messages.money(item.liquidity));
    }

    /**
     * 目录里没有的已注册物品：AI 先估一个合理价，写进 catalog.json（之后跟着价格引擎波动），
     * 再执行玩家原本的动作（买/卖/查价）。AI 不可用就用本地启发式估价。
     */
    private static void ensureCatalogItem(CommandSourceStack source, String itemText,
                                          java.util.function.Consumer<MarketStore.CatalogItem> action) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        ItemResolver.Found found = ItemResolver.resolve(itemText);
        if (found == null) {
            // 服务端认不出这个名字：让 AI 推断它对应哪个已注册物品 id（客户端汉化名也能救）
            if (ctx.settings.aiEnabled && ctx.ai != null && ctx.aiSubmitter != null) {
                aiResolveItemAndCreate(source, itemText, action);
                return;
            }
            Messages.sendFail(source, "市场里找不到这个物品：" + itemText);
            Messages.sendInfo(source, "如果是模组物品，把物品拿在主手说 /ex 买手上的 1；"
                    + "或用 /ex id 查物品 id，再 /ex buy <id> 1。");
            return;
        }
        Messages.sendInfo(source, "目录里没有「" + found.displayName() + "」，市场先生正在给它定价…");
        if (ctx.settings.aiEnabled && ctx.ai != null && ctx.aiSubmitter != null) {
            JsonObject priceContext = buildPriceContext(found);
            ctx.aiSubmitter.submit("price", () -> {
                long cents = ctx.ai.estimatePriceCents(found.id, found.displayName(), priceContext);
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("priceCents", cents);
                return wrapper;
            }, data -> {
                long cents = data != null && data.has("priceCents") ? data.get("priceCents").getAsLong() : -1L;
                if (cents <= 0L) {
                    cents = heuristicPrice(found);
                }
                AimcContext current = ctx();
                if (current == null) {
                    return;
                }
                MarketStore.CatalogItem item = current.market.hasItem(found.id)
                        ? current.market.item(found.id)
                        : current.market.addItem(found.id, found.displayName(), new java.util.ArrayList<>(
                                java.util.Collections.singletonList(found.nameEn == null ? "" : found.nameEn)),
                        cents, 0.05, 12_000L);
                action.accept(item);
            });
            return;
        }
        long cents = heuristicPrice(found);
        MarketStore.CatalogItem item = ctx.market.hasItem(found.id)
                ? ctx.market.item(found.id)
                : ctx.market.addItem(found.id, found.displayName(), new java.util.ArrayList<>(
                        java.util.Collections.singletonList(found.nameEn == null ? "" : found.nameEn)),
                cents, 0.05, 12_000L);
        action.accept(item);
    }

    /** AI 不可用时的本地估价：按稀有度/堆叠数估一个量级。 */
    private static long heuristicPrice(ItemResolver.Found found) {
        int stack = Math.max(1, ItemResolver.maxStackSize(found.id));
        int rarity = Math.max(0, ItemResolver.rarity(found.id));
        double stackFactor = Math.max(1.0, 64.0 / stack);
        long cents = Math.round(100.0 * stackFactor * (1.0 + rarity * 1.2));
        return Cents.clamp(cents, 100L, 500_000L);
    }

    /** 给 AI 估价的上下文：物品本身 + 几个参考价，避免离谱定价。 */
    private static JsonObject buildPriceContext(ItemResolver.Found found) {
        JsonObject context = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("id", found.id);
        item.addProperty("name", found.displayName());
        item.addProperty("maxStackSize", ItemResolver.maxStackSize(found.id));
        item.addProperty("rarity", ItemResolver.rarity(found.id));
        context.add("item", item);
        JsonArray reference = new JsonArray();
        String[] ids = {"minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond",
                "minecraft:oak_log", "minecraft:chest"};
        for (String id : ids) {
            MarketStore.CatalogItem catalogItem = ctx().market.item(id);
            if (catalogItem == null) {
                continue;
            }
            JsonObject one = new JsonObject();
            one.addProperty("id", id);
            one.addProperty("name", catalogItem.nameZh);
            one.addProperty("priceCents", ctx().market.price(id));
            reference.add(one);
        }
        context.add("reference", reference);
        return context;
    }

    private static int companyCreateArgs(CommandContext<CommandSourceStack> context) {
        String[] parts = splitLast(text(context, "args"));
        String sectorCode = sectorCode(parts[1]);
        if (sectorCode == null || parts[0].isEmpty()) {
            Messages.sendFail(context.getSource(), "用法：/ex company create <名字> <采矿|农业|制造|贸易|服务>");
            return 0;
        }
        return companyCreate(context, parts[0], sectorCode);
    }

    private static int companyMoneyArgs(CommandContext<CommandSourceStack> context, boolean deposit) {
        String[] parts = splitLast(text(context, "args"));
        if (parts[1].isEmpty()) {
            Messages.sendFail(context.getSource(), "用法：/ex company " + (deposit ? "deposit" : "withdraw") + " <公司> <金额>");
            return 0;
        }
        return companyMoney(context, parts[0], parts[1], deposit);
    }

    private static int companyHireArgs(CommandContext<CommandSourceStack> context, boolean hire) {
        String[] parts = splitLast(text(context, "args"));
        int count = parsePositiveInt(parts[1], 100);
        if (count <= 0) {
            Messages.sendFail(context.getSource(), "用法：/ex company " + (hire ? "hire" : "fire") + " <公司> <人数>");
            return 0;
        }
        return hire ? companyHire(context, parts[0], count) : companyFire(context, parts[0], count);
    }

    private static int companyDividendArgs(CommandContext<CommandSourceStack> context) {
        String[] parts = splitLast(text(context, "args"));
        if (parts[1].isEmpty()) {
            Messages.sendFail(context.getSource(), "用法：/ex company dividend <公司> <0-80>");
            return 0;
        }
        return companyDividend(context, parts[0], parts[1]);
    }

    private static int companySectorArgs(CommandContext<CommandSourceStack> context) {
        String[] parts = splitLast(text(context, "args"));
        if (sectorCode(parts[1]) == null) {
            Messages.sendFail(context.getSource(), "用法：/ex company sector <公司> <采矿|农业|制造|贸易|服务>");
            return 0;
        }
        return companySector(context, parts[0], parts[1]);
    }

    private static int companyRaiseArgs(CommandContext<CommandSourceStack> context) {
        String[] parts = splitLast(text(context, "args"));
        if (parts[1].isEmpty()) {
            Messages.sendFail(context.getSource(), "用法：/ex company raise <公司> <金额>");
            return 0;
        }
        return companyRaise(context, parts[0], parts[1]);
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
        Messages.sendInfo(source, "/ex gov status｜/ex return <物品> [数量]（7 天内买的按原价退）");
        Messages.sendInfo(source, "/ex 直接说人话：买 3 个铁锭｜把背包里的铁锭收购掉｜退货 铁锭 2");
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
            Messages.sendInfo(source, "说点具体的，例如：/ex 我要买 3 个铁锭，或者 /ex BLK 股票怎么样");
            return 0;
        }
        boolean aiReady = ctx.settings.aiEnabled && ctx.ai != null && ctx.aiSubmitter != null;
        if (!aiReady) {
            // AI 关闭时只用本地解析的明确意图；不做关键词猜测，避免猜错物品
            JsonObject local = LocalParser.parse(text, ctx.market);
            String localIntent = local == null || !local.has("intent") ? "unknown" : local.get("intent").getAsString();
            if (!"unknown".equals(localIntent) && !forceAi) {
                executeParsed(source, account, local);
                return Command.SINGLE_SUCCESS;
            }
            String answer = localQueryAnswer(account, text);
            if (answer != null) {
                Messages.send(source, answer);
            } else {
                Messages.sendInfo(source, LocalParser.helpHint());
            }
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        if (now - account.lastAiAtMs < ctx.settings.aiPlayerMinIntervalMs) {
            Messages.sendFail(source, "慢一点，AI 每 2 秒只能问一次");
            return 0;
        }
        account.lastAiAtMs = now;
        String playerName = account.name;
        Messages.sendInfo(source, "市场先生正在理解你的请求…");
        JsonObject tradeContext = buildTradeContext(account);
        ctx.aiSubmitter.submit("trade", () -> ctx.ai.parseTradeIntent(playerName, text, tradeContext),
                data -> handleTradeAi(source, account, text, data));
        return Command.SINGLE_SUCCESS;
    }

    /** AI 返回买卖意图后的落地：认物品（必要时自动上市）→ 扣钱/给物品。 */
    private static void handleTradeAi(CommandSourceStack source, Account account, String text, JsonObject data) {
        AimcContext ctx = ctx();
        if (ctx == null || account == null) {
            return;
        }
        String action = data != null && data.has("action")
                ? data.get("action").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "none";
        boolean buying = "buy".equals(action);
        boolean selling = "sell".equals(action);
        boolean returning = "return".equals(action);
        if (!buying && !selling && !returning) {
            // 不是买卖：当作问题交给只读问答
            askChat(account.uuid, text);
            return;
        }
        int quantity = 1;
        if (data.has("quantity")) {
            try {
                // 0 表示"背包里全部"（收购/退货常用）
                quantity = Math.max(0, Math.min(576, data.get("quantity").getAsInt()));
            } catch (RuntimeException ignored) {
                quantity = 1;
            }
        }
        String itemName = data.has("itemName") ? data.get("itemName").getAsString().trim() : "";
        // 1) AI 给的候选 id（会验证是否真实注册）
        String picked = pickItemId(data);
        // 2) 名字能在目录里认出来
        MarketStore.CatalogItem item = picked == null ? null : (ctx.market.hasItem(picked) ? ctx.market.item(picked) : null);
        if (item == null && !itemName.isBlank()) {
            item = ctx.market.resolve(itemName);
        }
        // 3) 目录没有这个物品：用 AI 给的价格自动上市（找不到价就用本地估价）
        if (item == null && picked != null) {
            ItemResolver.Found found = ItemResolver.byId(picked);
            if (found != null) {
                long cents = data.has("priceCents") ? data.get("priceCents").getAsLong() : -1L;
                cents = cents > 0L ? Cents.clamp(cents, 100L, 2_000_000L) : heuristicPrice(found);
                item = ctx.market.addItem(picked,
                        itemName.isBlank() ? found.displayName() : itemName,
                        new java.util.ArrayList<>(java.util.Collections.singletonList(
                                found.nameEn == null ? "" : found.nameEn)),
                        cents, 0.05, 12_000L);
            }
        }
        if (item == null) {
            Messages.sendFail(source, "市场先生没认出「" + (itemName.isBlank() ? text : itemName) + "」对应的物品。");
            Messages.sendInfo(source, "换个说法再来一次，或用 /ex id 查看物品 id 后 /ex buy <id> 数量。");
            return;
        }
        if (!itemName.isBlank()) {
            ctx.market.addAlias(item.id, itemName);
        }
        if (buying) {
            performBuySell(source, account, item, quantity, true);
        } else if (returning) {
            returnItem(source, account, item, quantity);
        } else {
            acquireFromPlayer(source, account, item, quantity);
        }
    }

    /** 给买卖意图 AI 的上下文：模组命名空间 + 目录样例 + 玩家余额。 */
    private static JsonObject buildTradeContext(Account account) {
        AimcContext ctx = ctx();
        JsonObject context = new JsonObject();
        context.addProperty("balanceCents", account.balanceCents());
        JsonArray namespaces = new JsonArray();
        int nsCount = 0;
        for (String namespace : ItemResolver.namespaces()) {
            if (nsCount++ >= 80) {
                break;
            }
            namespaces.add(namespace);
        }
        context.add("namespaces", namespaces);
        JsonArray catalog = new JsonArray();
        int count = 0;
        for (MarketStore.CatalogItem item : ctx.market.items()) {
            if (count++ >= 120) {
                break;
            }
            JsonObject one = new JsonObject();
            one.addProperty("id", item.id);
            one.addProperty("name", item.nameZh);
            one.addProperty("priceCents", ctx.market.price(item.id));
            catalog.add(one);
        }
        context.add("catalog", catalog);
        return context;
    }

    /** 主手/手上的/这个 之类的关键词：用玩家手上拿的物品。 */
    private static boolean isHandKeyword(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return t.equals("hand") || t.equals("手上的") || t.equals("手上") || t.equals("手里")
                || t.equals("手中的") || t.equals("这个") || t.equals("当前") || t.equals("主手")
                || t.equals("这个物品") || t.equals("手里的");
    }

    /** 玩家主手物品的注册 id（主手空则看副手），没有返回 null。 */
    private static String handItemId(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            stack = player.getOffhandItem();
        }
        if (stack.isEmpty()) {
            return null;
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 主手物品的中文/英文名（显示用）。 */
    private static String handItemName(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            stack = player.getOffhandItem();
        }
        return stack.isEmpty() ? null : stack.getHoverName().getString();
    }

    /** /ex alias <名字>：把主手物品和玩家习惯叫的名字绑定，之后 /ex 买 <名字> N 就能用。 */
    private static int aliasHeld(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "这条命令只能游戏内用");
            return 0;
        }
        String name = text(context, "name");
        if (name.isBlank()) {
            Messages.sendFail(source, "用法：主手拿着物品，输入 /ex alias <你习惯的叫法>");
            return 0;
        }
        String id = handItemId(player);
        if (id == null) {
            Messages.sendFail(source, "把要绑定的物品拿在主手，再输入 /ex alias " + name);
            return 0;
        }
        ensureCatalogItem(source, id, item -> {
            ctx().market.addAlias(item.id, name);
            Messages.sendOk(source, "记住了：「" + name + "」= " + item.nameZh + "（" + item.id
                    + "）。以后直接 /ex 买 " + name + " 数量");
        });
        return Command.SINGLE_SUCCESS;
    }

    /** /ex id：看手上物品的 id，方便用 /ex buy <id> N 下单。 */
    private static int heldItemInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!ready(source)) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.sendFail(source, "这条命令只能游戏内用");
            return 0;
        }
        String id = handItemId(player);
        if (id == null) {
            Messages.sendFail(source, "你主手没有拿物品");
            return 0;
        }
        String name = handItemName(player);
        MarketStore.CatalogItem item = ctx().market.item(id);
        if (item != null) {
            Messages.send(source, "手上：" + item.nameZh + "（" + id + "）现价 " + Messages.money(ctx().market.price(id)));
        } else {
            Messages.send(source, "手上：" + name + "（" + id + "）还没上市；说 /ex 买手上的 <数量> 会自动估价上市。");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 像"问题"而不是"命令"的句子：直接走问答，省一次 JSON 解析，也更自然。 */
    private static boolean looksLikeQuery(String text) {
        String t = text == null ? "" : text;
        String[] keywords = {"查", "看", "怎么样", "怎样", "如何", "多少", "什么", "哪", "情况", "行情", "介绍",
                "推荐", "分析", "新闻", "政策", "利率", "余额", "值钱", "涨", "跌", "能不能", "可以买", "好不好"};
        for (String keyword : keywords) {
            if (t.contains(keyword)) {
                return true;
            }
        }
        // 以 ? / ？ / 吗 / 呢 结尾的也算问句
        String trimmed = t.trim();
        return trimmed.endsWith("?") || trimmed.endsWith("？") || trimmed.endsWith("吗") || trimmed.endsWith("呢");
    }

    /** 异步问一次 AI（只读问答），失败用本地规则兜底。 */
    private static void askChat(String uuid, String text) {
        AimcContext ctx = ctx();
        if (ctx == null || ctx.ai == null || ctx.aiSubmitter == null) {
            return;
        }
        Account account = ctx.accounts.get(uuid);
        if (account == null) {
            return;
        }
        JsonObject chatContext = buildChatContext(account);
        com.mogutofu.aimcmarket.util.Log.info("[市场问答] 问题：" + text);
        ctx.aiSubmitter.submit("chat",
                () -> {
                    // AiSubmitter 是 JSON 通道，这里把自然语言回复包一层，避免改动公共接口
                    String reply = ctx.ai.chat(account.name, text, chatContext);
                    JsonObject wrapper = new JsonObject();
                    if (reply != null) {
                        wrapper.addProperty("reply", reply);
                    }
                    return wrapper;
                },
                data -> {
                    String rawReply = data == null ? null : data.has("reply") ? data.get("reply").getAsString() : null;
                    AimcContext current = ctx();
                    if (current == null) {
                        return;
                    }
                    Account fresh = current.accounts.get(uuid);
                    if (fresh == null) {
                        return;
                    }
                    String answer = rawReply != null && !rawReply.isBlank()
                            ? rawReply.trim() : localQueryAnswer(fresh, text);
                    if (answer == null || answer.isBlank()) {
                        answer = LocalParser.helpHint();
                    }
                    com.mogutofu.aimcmarket.util.Log.info("[市场问答] 回复：" + answer);
                    var server = com.mogutofu.aimcmarket.AimcmarketMod.server();
                    if (server != null) {
                        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(uuid));
                        if (player != null) {
                            player.sendSystemMessage(Messages.market(answer));
                        }
                    }
                });
    }

    /** 给 AI 的只读市场上下文（不包含任何可被 AI 拿来改数据的字段）。 */
    private static JsonObject buildChatContext(Account account) {
        AimcContext ctx = ctx();
        JsonObject context = new JsonObject();
        context.addProperty("indexPoints", ctx.stocks.indexCentiPoints() / 100.0);
        context.addProperty("indexChange7d", Math.round(ctx.stocks.indexChange7d(ctx.tradingDay()) * 10000.0) / 10000.0);
        JsonArray stocks = new JsonArray();
        for (StockStore.Stock stock : ctx.stocks.all()) {
            JsonObject one = new JsonObject();
            one.addProperty("code", stock.code);
            one.addProperty("name", stock.name);
            one.addProperty("sector", stock.sector);
            one.addProperty("priceCents", stock.priceCents);
            one.addProperty("change", Math.round((stock.priceCents / (double) stock.initialPriceCents - 1.0) * 10000.0) / 10000.0);
            one.addProperty("outlook", stock.lastOutlook);
            stocks.add(one);
        }
        context.add("stocks", stocks);
        JsonArray funds = new JsonArray();
        for (FundStore.Fund fund : ctx.funds.all()) {
            JsonObject one = new JsonObject();
            one.addProperty("code", fund.code);
            one.addProperty("name", fund.name);
            one.addProperty("nav", ctx.funds.formatNav(fund.code));
            funds.add(one);
        }
        context.add("funds", funds);
        JsonObject player = new JsonObject();
        player.addProperty("name", account.name);
        player.addProperty("balanceCents", account.balanceCents());
        player.addProperty("depositCents", account.bank.totalDepositsCents());
        player.addProperty("loanCents", account.bank.totalLoanCents());
        player.addProperty("credit", Math.round(account.creditScore));
        JsonArray holdings = new JsonArray();
        for (var entry : account.stocks.entrySet()) {
            JsonObject one = new JsonObject();
            one.addProperty("code", entry.getKey());
            one.addProperty("shares", entry.getValue().shares);
            one.addProperty("priceCents", ctx.stocks.priceOf(entry.getKey()));
            holdings.add(one);
        }
        player.add("stockHoldings", holdings);
        context.add("player", player);
        JsonObject gov = new JsonObject();
        gov.addProperty("policyRate", ctx.government.policyRate);
        gov.addProperty("tradeTax", ctx.government.effectiveTradeTax());
        gov.addProperty("companyTax", ctx.government.effectiveCompanyTax());
        gov.addProperty("budgetCents", ctx.government.budgetCents());
        gov.addProperty("stress", ctx.government.indicators.stress);
        context.add("government", gov);
        JsonArray news = new JsonArray();
        for (var item : ctx.news.recent(3)) {
            news.add(item.headline);
        }
        context.add("recentNews", news);
        context.addProperty("help", "常用：/ex stock list 看股票、/ex stock buy 代码 股数 买入、/ex board 看物品行情、"
                + "/ex price 物品 查价、/ex balance 看余额、/ex news 看新闻、/ex gov status 看政策");
        return context;
    }

    /** AI 不可用时的本地问答兜底：按关键词给一句人话回答。 */
    private static String localQueryAnswer(Account account, String text) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            return null;
        }
        String t = text == null ? "" : text;
        if (t.contains("余额") || t.contains("钱") || t.contains("多少币")) {
            return "你钱包 " + Messages.money(account.balanceCents())
                    + "，存款 " + Messages.money(account.bank.totalDepositsCents())
                    + "，贷款 " + Messages.money(account.bank.totalLoanCents()) + "。";
        }
        if (t.contains("股票") || t.contains("大盘") || t.contains("指数") || t.contains("股")) {
            StringBuilder sb = new StringBuilder("大盘 " + ctx.stocks.formatIndex()
                    + "（7 日 " + Messages.changeText(ctx.stocks.indexChange7d(ctx.tradingDay())) + "）。");
            sb.append("股票：");
            int count = 0;
            for (StockStore.Stock stock : ctx.stocks.all()) {
                if (count++ >= 5) {
                    break;
                }
                double change = stock.initialPriceCents <= 0 ? 0
                        : (double) stock.priceCents / stock.initialPriceCents - 1.0;
                sb.append(stock.code).append(' ').append(stock.name).append(' ')
                        .append(Messages.money(stock.priceCents)).append('（')
                        .append(Messages.changeText(change)).append("） ");
            }
            sb.append("；/ex stock list 看全部，/ex stock info 代码 看详情。");
            return sb.toString();
        }
        if (t.contains("基金")) {
            StringBuilder sb = new StringBuilder("基金净值：");
            for (FundStore.Fund fund : ctx.funds.all()) {
                sb.append(fund.code).append(' ').append(fund.name).append(' ')
                        .append(ctx.funds.formatNav(fund.code)).append("；");
            }
            sb.append(" /ex fund buy FND01 100 可申购。");
            return sb.toString();
        }
        if (t.contains("银行") || t.contains("利率") || t.contains("贷款") || t.contains("存款")) {
            return "政策利率 " + Messages.percent(ctx.government.policyRate)
                    + "，活期 " + Messages.percent(ctx.bank.currentRate(ctx.government.policyRate, ctx.settings))
                    + "，贷款 " + Messages.percent(ctx.bank.loanRate(ctx.government.policyRate, ctx.settings))
                    + "；/ex bank info 看自己的存贷款，/ex bank loan take 借款。";
        }
        if (t.contains("政策") || t.contains("政府") || t.contains("央行")) {
            return "政策利率 " + Messages.percent(ctx.government.policyRate)
                    + "，交易税 " + Messages.percent(ctx.government.effectiveTradeTax())
                    + "，政府预算 " + Messages.money(ctx.government.budgetCents())
                    + "，压力分 " + ctx.government.indicators.stress + "；/ex gov status 看完整指标。";
        }
        if (t.contains("新闻") || t.contains("消息")) {
            var latest = ctx.news.latest();
            return latest == null ? "最近没有新闻。" : "最近新闻：" + latest.headline;
        }
        if (t.contains("怎么") || t.contains("帮助") || t.contains("玩法")) {
            return "你可以直接说：买 3 个铁锭 / 买 10 股 BLK / 存 100 币，或者用 /ex help 看全部命令。";
        }
        return null;
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
            com.mogutofu.aimcmarket.util.Log.info("[本地解析结果] ok=" + message.ok
                    + " intent=" + parsed + " → " + message.message);
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
            com.mogutofu.aimcmarket.util.Log.info("[AI 解析结果] ok=" + message.ok
                    + " intent=" + parsed + " → " + message.message);
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
                    ServerPlayer buyer = com.mogutofu.aimcmarket.AimcmarketMod.server() == null ? null
                            : com.mogutofu.aimcmarket.AimcmarketMod.server().getPlayerList()
                            .getPlayer(UUID.fromString(account.uuid));
                    for (var element : items) {
                        JsonObject one = element.getAsJsonObject();
                        String id = one.get("id").getAsString();
                        int count = one.has("count") ? one.get("count").getAsInt() : 1;
                        ActionResult result = TradeService.buy(ctx, account, id, count);
                        if (result.ok) {
                            recordPurchase(account, ctx.market.item(id), count);
                        }
                        if (result.ok && buyer != null) {
                            int left = giveItem(buyer, id, count);
                            if (left > 0) {
                                buyer.sendSystemMessage(Messages.info("背包放不下 " + left + " 个，已掉在脚下"));
                            }
                        }
                        sink.accept(result);
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
