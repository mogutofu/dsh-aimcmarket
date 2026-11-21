package com.mogutofu.aimcmarket.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.data.NewsStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 新闻引擎：AI 新闻的 context 构造 / 落地校验、本地模板新闻、政策/财报/IPO/破产快讯。
 *
 * <p>新闻本身只是文本 + 影响列表，真正改价格的是把 effects 通过
 * {@link MarketStore#applyEvent(String, double, int, String)} 挂到市场事件上，
 * 由 {@code PriceEngine} 读取。AI 只能给文案和"影响谁/多大幅度/持续几个 tick"的建议，
 * 这里会重新夹取边界（乘数 0.85-1.20、持续 3-12 个 market tick），坏的直接丢弃。</p>
 *
 * <p>不 import 任何 Minecraft 类，不起线程。</p>
 */
public final class NewsEngine {
    /** 自动新闻间隔：15 现实分钟。 */
    public static final long NEWS_INTERVAL_MS = 15L * 60_000L;
    /** 一条新闻最多影响 3 个目标。 */
    public static final int MAX_EFFECTS = 3;
    /** 事件乘数夹取下限。 */
    public static final double MIN_MULTIPLIER = 0.85;
    /** 事件乘数夹取上限。 */
    public static final double MAX_MULTIPLIER = 1.20;
    /** 事件持续最少 market tick。 */
    public static final int MIN_TICKS = 3;
    /** 事件持续最多 market tick。 */
    public static final int MAX_TICKS = 12;

    private static final int MAX_HEADLINE_LENGTH = 120;
    private static final int MAX_BODY_LENGTH = 1_000;
    private static final int MAX_MOVER_ROWS = 5;

    private NewsEngine() {
    }

    // ── AI 新闻 context ─────────────────────────────────────────────────

    /**
     * 给 {@code task=news} 的 context：大盘指数与 7 日涨跌、涨幅/跌幅前 5 的商品、
     * 行业景气、最近政策、最近 3 条新闻。
     */
    public static JsonObject buildNewsContext(AimcContext ctx) {
        JsonObject context = new JsonObject();
        if (ctx == null) {
            return context;
        }
        long day = ctx.tradingDay();

        JsonObject index = new JsonObject();
        index.addProperty("points", ctx.stocks.indexCentiPoints() / 100.0);
        index.addProperty("change7d", round4(ctx.stocks.indexChange7d(day)));
        context.add("index", index);

        List<MarketStore.CatalogItem> items = new ArrayList<>(ctx.market.enabledItems());
        JsonObject moversJson = new JsonObject();
        moversJson.add("gainers", movers(ctx, items, true, MAX_MOVER_ROWS));
        moversJson.add("losers", movers(ctx, items, false, MAX_MOVER_ROWS));
        context.add("movers", moversJson);

        JsonObject sectors = new JsonObject();
        for (Map.Entry<String, Double> entry : ctx.stocks.allSectorClimate().entrySet()) {
            sectors.addProperty(entry.getKey(), round4(entry.getValue()));
        }
        context.add("sectors", sectors);

        JsonArray recentPolicies = new JsonArray();
        for (com.mogutofu.aimcmarket.data.GovernmentStore.Policy policy : ctx.government.recentPolicies(3)) {
            JsonObject one = new JsonObject();
            one.addProperty("action", policy.action);
            one.addProperty("at", policy.atMs);
            one.addProperty("day", policy.day);
            one.addProperty("expiresDay", policy.expiresDay);
            one.add("params", policy.params == null ? new JsonObject() : policy.params);
            recentPolicies.add(one);
        }
        context.add("recentPolicies", recentPolicies);

        JsonArray recentNews = new JsonArray();
        for (NewsStore.NewsItem item : ctx.news.recent(3)) {
            JsonObject one = new JsonObject();
            one.addProperty("headline", item.headline);
            one.addProperty("category", item.category);
            one.addProperty("at", item.atMs);
            one.addProperty("day", item.day);
            recentNews.add(one);
        }
        context.add("recentNews", recentNews);
        return context;
    }

    private static JsonArray movers(AimcContext ctx, List<MarketStore.CatalogItem> items,
                                    boolean gainers, int limit) {
        List<MarketStore.CatalogItem> sorted = new ArrayList<>(items);
        sorted.sort((left, right) -> Double.compare(changeVsBase(ctx, right), changeVsBase(ctx, left)));
        if (!gainers) {
            Collections.reverse(sorted);
        }
        JsonArray array = new JsonArray();
        for (int i = 0; i < sorted.size() && i < limit; i++) {
            MarketStore.CatalogItem item = sorted.get(i);
            JsonObject one = new JsonObject();
            one.addProperty("id", item.id);
            one.addProperty("name", item.nameZh);
            one.addProperty("priceCents", ctx.market.price(item.id));
            one.addProperty("changeVsBase", round4(changeVsBase(ctx, item)));
            array.add(one);
        }
        return array;
    }

    private static double changeVsBase(AimcContext ctx, MarketStore.CatalogItem item) {
        if (item == null || item.basePriceCents <= 0L) {
            return 0.0;
        }
        long price = ctx.market.price(item.id);
        return (double) price / (double) item.basePriceCents - 1.0;
    }

    // ── AI 新闻落地 ─────────────────────────────────────────────────────

    /**
     * 校验并落地一条 AI 新闻：headline/body 必须是非空字符串；effects 里
     * target 必须是 catalog 商品 id 或股票代码，multiplier 夹 [0.85, 1.20]，
     * ticks 夹 [3, 12]，非法目标跳过，最多 3 条生效。
     *
     * @return 标题；坏数据返回空串
     */
    public static String applyAiNews(AimcContext ctx, JsonObject data) {
        if (ctx == null || data == null) {
            return "";
        }
        String headline = JsonFile.str(data, "headline", "").trim();
        if (headline.isEmpty() || headline.length() > MAX_HEADLINE_LENGTH) {
            return "";
        }
        String body = JsonFile.str(data, "body", "").trim();
        if (body.isEmpty() || body.length() > MAX_BODY_LENGTH) {
            return "";
        }

        JsonArray effects = new JsonArray();
        for (JsonElement element : JsonFile.array(data, "effects")) {
            if (effects.size() >= MAX_EFFECTS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject raw = element.getAsJsonObject();
            String target = normalizeTarget(ctx, JsonFile.str(raw, "target", "").trim());
            if (target == null) {
                continue;
            }
            double multiplier = Cents.clampDouble(
                    JsonFile.dbl(raw, "multiplier", 1.0), MIN_MULTIPLIER, MAX_MULTIPLIER);
            int ticks = Cents.clampInt(JsonFile.intVal(raw, "ticks", 6), MIN_TICKS, MAX_TICKS);
            ctx.market.applyEvent(target, multiplier, ticks, "news");

            JsonObject applied = new JsonObject();
            applied.addProperty("target", target);
            applied.addProperty("multiplier", round3(multiplier));
            applied.addProperty("ticks", ticks);
            effects.add(applied);
        }

        ctx.news.add(headline, body, "market", ctx.nowMs(), ctx.tradingDay(), effects);
        Log.info("AI 新闻落地：" + headline + "（影响 " + effects.size() + " 个目标）");
        return headline;
    }

    /** catalog 商品 id 或股票代码 → 规范化写法；都不是返回 null。 */
    private static String normalizeTarget(AimcContext ctx, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        MarketStore.CatalogItem item = ctx.market.item(target);
        if (item != null) {
            return item.id;
        }
        StockStore.Stock stock = ctx.stocks.stock(target);
        if (stock != null) {
            return stock.code;
        }
        return null;
    }

    // ── 本地模板新闻 ────────────────────────────────────────────────────

    /**
     * AI 不可用时从内置模板池随机生成一条新闻（≥8 个模板），随机挑 1-3 个商品/股票作为
     * 影响目标，挂市场事件、写 news 并标记生成时间，返回标题。
     */
    public static String generateLocalNews(AimcContext ctx) {
        if (ctx == null) {
            return "";
        }
        List<LocalTemplate> templates = templates();
        LocalTemplate template = templates.get(ctx.random.nextInt(templates.size()));
        List<String> targets = pickTargets(ctx, template);
        long now = ctx.nowMs();
        long day = ctx.tradingDay();
        if (targets.isEmpty()) {
            // 目录/股票都为空（例如测试环境没加载 catalog）：退化成一条纯文本新闻
            String headline = "市场交投清淡，观望情绪升温";
            String body = "今日商品与股票市场成交平淡，资金观望情绪较浓，等待新的政策与消息指引。";
            ctx.news.add(headline, body, "market", now, day, new JsonArray());
            ctx.news.markGenerated(now);
            return headline;
        }

        List<String> displays = new ArrayList<>();
        JsonArray effects = new JsonArray();
        for (String target : targets) {
            displays.add(displayName(ctx, target));
            double multiplier = round3(template.minMultiplier
                    + ctx.random.nextDouble() * (template.maxMultiplier - template.minMultiplier));
            int ticks = MIN_TICKS + ctx.random.nextInt(6); // 3-8 个 tick，更接近现实
            ctx.market.applyEvent(target, multiplier, ticks, "news");

            JsonObject one = new JsonObject();
            one.addProperty("target", target);
            one.addProperty("multiplier", multiplier);
            one.addProperty("ticks", ticks);
            effects.add(one);
        }

        String first = displays.get(0);
        String all = String.join("、", displays);
        String headline = String.format(Locale.ROOT, template.headline, first);
        String body = String.format(Locale.ROOT, template.body, all);
        ctx.news.add(headline, body, template.category, now, day, effects);
        ctx.news.markGenerated(now);
        Log.info("本地新闻生成：" + headline);
        return headline;
    }

    private static List<String> pickTargets(AimcContext ctx, LocalTemplate template) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String id : template.itemIds) {
            MarketStore.CatalogItem item = ctx.market.item(id);
            if (item != null && item.enabled) {
                candidates.add(item.id);
            }
        }
        for (String code : template.stockCodes) {
            StockStore.Stock stock = ctx.stocks.stock(code);
            if (stock != null) {
                candidates.add(stock.code);
            }
        }
        int count = 1 + ctx.random.nextInt(MAX_EFFECTS); // 1-3 个目标
        if (candidates.isEmpty()) {
            // 模板没指定或标的不存在：退化成任意启用的商品
            List<String> pool = new ArrayList<>();
            for (MarketStore.CatalogItem item : ctx.market.enabledItems()) {
                pool.add(item.id);
            }
            if (pool.isEmpty()) {
                return new ArrayList<>();
            }
            Collections.shuffle(pool, ctx.random);
            return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
        }
        List<String> pool = new ArrayList<>(candidates);
        Collections.shuffle(pool, ctx.random);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    private static String displayName(AimcContext ctx, String target) {
        MarketStore.CatalogItem item = ctx.market.item(target);
        if (item != null) {
            return item.nameZh == null || item.nameZh.isBlank() ? item.id : item.nameZh;
        }
        StockStore.Stock stock = ctx.stocks.stock(target);
        if (stock != null) {
            return stock.name == null || stock.name.isBlank() ? stock.code : stock.name;
        }
        return target;
    }

    private static final class LocalTemplate {
        final String category;
        final double minMultiplier;
        final double maxMultiplier;
        final String[] itemIds;
        final String[] stockCodes;
        /** 含一个 %s（第一个目标的名字）。 */
        final String headline;
        /** 含一个 %s（全部目标名字，用、连接）。 */
        final String body;

        LocalTemplate(String category, double minMultiplier, double maxMultiplier,
                      String[] itemIds, String[] stockCodes, String headline, String body) {
            this.category = category;
            this.minMultiplier = minMultiplier;
            this.maxMultiplier = maxMultiplier;
            this.itemIds = itemIds;
            this.stockCodes = stockCodes;
            this.headline = headline;
            this.body = body;
        }
    }

    private static List<LocalTemplate> templates() {
        return TEMPLATES;
    }

    private static final List<LocalTemplate> TEMPLATES = List.of(
            // 1. 矿难：供应收缩，原料涨价
            new LocalTemplate("market", 1.05, 1.15,
                    new String[]{"minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold",
                            "minecraft:coal", "minecraft:iron_ingot"},
                    new String[]{},
                    "矿井事故：%s 供应告急，价格快速上行",
                    "一座深层矿井发生塌方，运输通道受阻，市场担心短期供应缺口，%s 价格短线走高。"),
            // 2. 丰收：供应过剩，农产品跌价
            new LocalTemplate("market", 0.90, 0.98,
                    new String[]{"minecraft:wheat", "minecraft:carrot", "minecraft:potato",
                            "minecraft:apple", "minecraft:bread", "minecraft:melon", "minecraft:pumpkin"},
                    new String[]{},
                    "大丰收：%s 供应过剩，价格承压",
                    "本季作物普遍丰收，市场上%s到货量明显增加，收购价走低。"),
            // 3. 大单扫货：任意商品
            new LocalTemplate("market", 1.06, 1.14,
                    new String[]{},
                    new String[]{},
                    "神秘大单扫货：%s 成交放量",
                    "盘面出现连续大额买单，%s 流动性被迅速抽走，成交价被推高。"),
            // 4. 政策解读：避险资产受追捧
            new LocalTemplate("market", 1.02, 1.08,
                    new String[]{"minecraft:gold_ingot", "minecraft:gold_block",
                            "minecraft:emerald", "minecraft:emerald_block"},
                    new String[]{"EMR", "BLK"},
                    "政策利好解读：%s 成为避险与配置焦点",
                    "央行最新表态被市场解读为宽松信号，资金流向%s等资产，价格温和上涨。"),
            // 5. 科技突破：红石/钻石需求激增
            new LocalTemplate("market", 1.05, 1.18,
                    new String[]{"minecraft:redstone", "minecraft:redstone_block",
                            "minecraft:amethyst_shard", "minecraft:diamond", "minecraft:lapis_lazuli"},
                    new String[]{"RED"},
                    "红石科技突破：%s 需求激增",
                    "红石科技宣布新一代电路方案落地，对%s等材料的需求预期升温，价格跳涨。"),
            // 6. 物流中断：末地航线受阻
            new LocalTemplate("market", 1.05, 1.12,
                    new String[]{"minecraft:ender_pearl", "minecraft:end_stone",
                            "minecraft:shulker_shell", "minecraft:obsidian"},
                    new String[]{"END"},
                    "末地航道受阻：%s 缺货",
                    "末地物流航线临时中断，%s 补给延后，现货市场快速抬价。"),
            // 7. 降准传闻：金融资产走强
            new LocalTemplate("market", 1.02, 1.08,
                    new String[]{"minecraft:emerald", "minecraft:gold_ingot"},
                    new String[]{"EMR", "BLK"},
                    "降准传闻升温：%s 领涨",
                    "市场传闻央行将下调存款准备金，%s等金融资产获得资金青睐。"),
            // 8. 灾后重建：建材需求旺盛
            new LocalTemplate("market", 1.05, 1.12,
                    new String[]{"minecraft:stone", "minecraft:cobblestone", "minecraft:oak_planks",
                            "minecraft:glass", "minecraft:brick", "minecraft:dirt"},
                    new String[]{},
                    "村庄灾后重建：%s 订单激增",
                    "多地村落启动灾后重建，建材采购量上升，%s需求旺盛。"),
            // 9. 出口管制：关键材料趋紧
            new LocalTemplate("market", 1.08, 1.15,
                    new String[]{"minecraft:netherite_scrap", "minecraft:netherite_ingot",
                            "minecraft:diamond", "minecraft:blaze_rod", "minecraft:ghast_tear"},
                    new String[]{},
                    "出口管制生效：%s 供应趋紧",
                    "监管层收紧关键材料出口，%s现货供应趋紧，报价上调。"),
            // 10. 节日消费旺季：食品走俏
            new LocalTemplate("market", 1.03, 1.09,
                    new String[]{"minecraft:bread", "minecraft:cake", "minecraft:cooked_beef",
                            "minecraft:cooked_porkchop", "minecraft:apple", "minecraft:golden_apple"},
                    new String[]{},
                    "节日消费旺季：%s 走俏",
                    "节日临近，玩家消费需求升温，%s销量与价格同步上涨。"),
            // 11. 机构调研：上市公司获资金关注
            new LocalTemplate("market", 1.03, 1.10,
                    new String[]{},
                    new String[]{"RED", "NTL", "END", "BLK"},
                    "机构密集调研：%s 获资金关注",
                    "%s近期获多家机构调研，市场预期改善，股价走强。"),
            // 12. 获利盘抛售：大宗商品回调
            new LocalTemplate("market", 0.88, 0.95,
                    new String[]{"minecraft:diamond_block", "minecraft:gold_block",
                            "minecraft:emerald_block", "minecraft:netherite_ingot"},
                    new String[]{},
                    "获利盘集中抛售：%s 大幅回调",
                    "前期涨幅过大，资金集中止盈，%s出现明显回调。")
    );

    // ── 直接发布 ────────────────────────────────────────────────────────

    /** 直接写一条新闻（财报/IPO/破产/政策快讯用），不挂价格事件。 */
    public static void publish(AimcContext ctx, String category, String headline, String body) {
        if (ctx == null) {
            return;
        }
        String title = headline == null ? "" : headline.trim();
        if (title.isEmpty()) {
            return;
        }
        if (title.length() > MAX_HEADLINE_LENGTH) {
            title = title.substring(0, MAX_HEADLINE_LENGTH);
        }
        String text = body == null ? "" : body.trim();
        if (text.length() > MAX_BODY_LENGTH) {
            text = text.substring(0, MAX_BODY_LENGTH);
        }
        String cat = category == null || category.isBlank() ? "market" : category.trim();
        ctx.news.add(title, text, cat, ctx.nowMs(), ctx.tradingDay(), new JsonArray());
    }

    // ── 调度判断 ────────────────────────────────────────────────────────

    /** 距上次自动生成新闻是否已经 ≥ 15 现实分钟。 */
    public static boolean dueForNews(AimcContext ctx, long nowMs) {
        if (ctx == null) {
            return false;
        }
        long last = ctx.news.lastGeneratedAtMs();
        return last <= 0L || nowMs - last >= NEWS_INTERVAL_MS;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static double round3(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
