package com.mogutofu.aimcmarket.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.util.JsonFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 返回值的「修复 + schema 校验」。
 *
 * <p>这一层的所有判断都<b>不依赖模型</b>：模型说什么都不算数，字段类型、枚举、范围不合规就返回
 * {@code null}，调用方据此降级（本地解析/本地规则/内置模板）。它同时负责把通过校验的数据整理成
 * 统一形状（缺省键补空、字符串去换行、数字统一 long/int），这样命令层和引擎层拿到的都是干净数据。</p>
 *
 * <p>注意：这里不碰 Minecraft，也不碰账本，只做纯 JSON 处理。</p>
 */
public final class AiJson {
    /** 单笔订单单个物品的数量上限（和 TradeService 的校验保持一致）。 */
    public static final int MAX_ITEM_COUNT = 576;
    /** 一次解析最多接受几个物品条目（防模型胡写一大串）。 */
    private static final int MAX_ITEM_ENTRIES = 16;
    /** 新闻最多接受几条价格影响（设计上 1-3 条）。 */
    private static final int MAX_NEWS_EFFECTS = 12;

    private static final Pattern ITEM_ID = Pattern.compile("minecraft:[a-z0-9_]{1,64}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9]{0,11}");
    private static final Set<String> OUTLOOKS = Set.of("up", "flat", "down");
    private static final Set<Integer> RATE_BPS = Set.of(25, 50, 75, 100);
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(GovernmentStore.ACTIONS));
    /** 公司行业代码（和 CompanyStore.SECTORS 一致）。 */
    private static final Set<String> SECTOR_CODES = Set.of("mining", "farming", "manufacturing", "trading", "service");
    /** 期权类型只认 call / put。 */
    private static final Set<String> OPTION_TYPES = Set.of("call", "put");

    /**
     * {@code parse} 任务允许的 intent（与 dsh-mc-server-ops 0.7.0 的 /mc-exchange 契约一致）。
     *
     * <p>少一个会让合法解析被拒，多一个会让命令层收到不认识的意图，所以这张表必须和插件同步。</p>
     */
    public static final Set<String> INTENTS = Set.of(
            "buy", "sell", "price", "balance", "work", "daily", "pay",
            "stock_buy", "stock_sell", "stock_info",
            "fund_buy", "fund_sell", "fund_info",
            "deposit", "withdraw", "bank_loan",
            "company_create", "ipo_apply",
            "futures_long", "futures_short",
            "options_buy", "options_sell",
            "gov_status", "help", "unknown");

    private AiJson() {
    }

    // ── JSON 修复 ──

    /**
     * 从一段可能夹着解释、markdown 代码块的模型输出里抠出第一个 JSON 对象。
     *
     * <p>先复用 {@link JsonFile#parseLooseObject(String)}（它会剥 ```json 包裹并做花括号配平），
     * 拿不到再自己兜两层：去掉尾逗号重试、剥掉围栏后直接 Gson 解析。全程不抛异常。</p>
     *
     * @return JSON 对象；抠不出来返回 {@code null}
     */
    public static JsonObject parseLooseObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            JsonObject parsed = JsonFile.parseLooseObject(text);
            if (parsed != null) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // 交给下面的兜底
        }
        // 兜底 1：模型常见的尾逗号 {"a":1,} / [1,2,]
        try {
            String repaired = text.replaceAll(",\\s*([}\\]])", "$1");
            if (!repaired.equals(text)) {
                JsonObject parsed = JsonFile.parseLooseObject(repaired);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (RuntimeException ignored) {
            // 交给下面的兜底
        }
        // 兜底 2：手工剥围栏后整段解析（能处理 JSON 前后完全没别的东西的情况）
        try {
            String fenced = stripCodeFence(text).trim();
            JsonElement parsed = JsonParser.parseString(fenced);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // 放弃
        }
        return null;
    }

    private static String stripCodeFence(String text) {
        int fence = text.indexOf("```");
        if (fence < 0) {
            return text;
        }
        int start = fence + 3;
        while (start < text.length() && Character.isLetter(text.charAt(start))) {
            // 跳过 ```json / ```JSON 这类语言标记
            start++;
        }
        int closing = text.indexOf("```", start);
        return closing > start ? text.substring(start, closing) : text.substring(start);
    }

    // ── parse 任务 ──

    /**
     * 校验并整理 {@code parse} 任务的数据。
     *
     * <p>不合规返回 {@code null}。注意 {@code market} 只用来确认「物品存在且 enabled」，
     * 传 {@code null} 时跳过目录校验（单测用）。</p>
     */
    public static JsonObject validateParse(JsonObject data, MarketStore market) {
        if (data == null) {
            return null;
        }
        String intentText = string(data, "intent");
        if (intentText == null) {
            return null;
        }
        String intent = intentText.trim().toLowerCase(Locale.ROOT);
        if (!INTENTS.contains(intent)) {
            return null;
        }

        JsonArray items = new JsonArray();
        if (present(data, "items")) {
            JsonElement itemsElement = data.get("items");
            if (itemsElement == null || !itemsElement.isJsonArray()) {
                return null;
            }
            JsonArray input = itemsElement.getAsJsonArray();
            if (input.size() > MAX_ITEM_ENTRIES) {
                return null;
            }
            for (JsonElement entry : input) {
                if (entry == null || !entry.isJsonObject()) {
                    return null;
                }
                JsonObject item = entry.getAsJsonObject();
                String idText = string(item, "id");
                if (idText == null) {
                    return null;
                }
                String id = idText.trim().toLowerCase(Locale.ROOT);
                if (!ITEM_ID.matcher(id).matches()) {
                    return null;
                }
                if (market != null) {
                    MarketStore.CatalogItem catalogItem = market.item(id);
                    if (catalogItem == null || !catalogItem.enabled) {
                        return null;
                    }
                }
                Long count = integral(item.get("count"));
                if (count == null) {
                    if (present(item, "count")) {
                        return null;
                    }
                    count = 1L; // 没写数量按 1 个算，和 /ex buy <物品> 的默认一致
                }
                if (count < 1L || count > MAX_ITEM_COUNT) {
                    return null;
                }
                JsonObject outItem = new JsonObject();
                outItem.addProperty("id", id);
                outItem.addProperty("count", count);
                items.add(outItem);
            }
        }

        String symbolText = string(data, "symbol");
        if (symbolText == null) {
            return null;
        }
        String symbol = symbolText.trim().toUpperCase(Locale.ROOT);
        if (!symbol.isEmpty() && !SYMBOL.matcher(symbol).matches()) {
            return null;
        }

        Long shares = integral(data.get("shares"));
        if (shares == null) {
            if (present(data, "shares")) {
                return null;
            }
            shares = 0L;
        }
        if (shares < 0L) {
            return null;
        }

        Long amountCents = integral(data.get("amountCents"));
        if (amountCents == null) {
            if (present(data, "amountCents")) {
                return null;
            }
            amountCents = 0L;
        }
        if (amountCents < 0L) {
            return null;
        }

        // 各 intent 的最小必要字段（和插件 /mc-exchange 的交叉约束一致）
        switch (intent) {
            case "buy":
            case "sell":
                if (items.size() == 0) {
                    return null;
                }
                break;
            case "stock_buy":
            case "stock_sell":
                if (symbol.isEmpty() || shares < 1L) {
                    return null;
                }
                break;
            case "stock_info":
            case "fund_sell":
            case "fund_info":
            case "futures_long":
            case "futures_short":
            case "options_buy":
            case "options_sell":
                if (symbol.isEmpty()) {
                    return null;
                }
                break;
            case "fund_buy":
                if (symbol.isEmpty() || amountCents < 1L) {
                    return null;
                }
                break;
            case "pay":
            case "deposit":
            case "withdraw":
                if (amountCents < 1L) {
                    return null;
                }
                break;
            default:
                break;
        }

        String replyText = string(data, "reply");
        if (replyText == null) {
            replyText = "";
        }
        JsonObject out = new JsonObject();
        out.addProperty("intent", intent);
        out.add("items", items);
        out.addProperty("symbol", symbol);
        out.addProperty("shares", shares);
        out.addProperty("amountCents", amountCents);
        out.addProperty("reply", truncate(cleanLine(replyText), 200));

        // 命令层（MarketCommand.execute）在通用 6 个键之外还会读这些扩展字段：
        // pay.player、company_create.companyName/sector、fund_sell.unitsMilli、
        // futures_*.lots/expiryDays、options_*.optionType/strikeCents/expiryDays/contracts。
        // 这里校验后保留；没给就让命令层用自己的默认值。第二次校验必须仍然通过（幂等）。
        String player = string(data, "player");
        if (player == null) {
            return null;
        }
        player = player.trim();
        if (!player.isEmpty()) {
            if (player.length() > 16) {
                return null;
            }
            out.addProperty("player", player);
        }
        if ("company_create".equals(intent)) {
            // 开公司要花 5000 币，没名字绝对不能落单
            String companyName = string(data, "companyName");
            if (companyName == null) {
                return null;
            }
            companyName = companyName.trim();
            if (companyName.length() < 2 || companyName.length() > 16) {
                return null;
            }
            out.addProperty("companyName", companyName);
            String sectorRaw = string(data, "sector");
            if (sectorRaw == null) {
                return null;
            }
            String sector = sectorCode(sectorRaw);
            if (sector != null) {
                out.addProperty("sector", sector);
            }
        }
        if ("fund_sell".equals(intent)) {
            Long units = integral(data.get("unitsMilli"));
            if (units == null) {
                if (present(data, "unitsMilli")) {
                    return null;
                }
                // 契约里 fund_sell 的 shares 是「份」，命令层读 unitsMilli（1 份 = 1000）
                if (shares > 0L && shares <= 9_000_000_000_000L) {
                    units = shares * 1000L;
                }
            } else if (units < 0L) {
                return null;
            }
            if (units != null && units > 0L) {
                out.addProperty("unitsMilli", units);
            }
        }
        if ("futures_long".equals(intent) || "futures_short".equals(intent)) {
            Long lots = integral(data.get("lots"));
            if (lots == null) {
                if (present(data, "lots")) {
                    return null;
                }
                if (shares > 0L) {
                    lots = shares; // 契约里 futures 的 shares 就是手数，命令层读 lots
                }
            } else if (lots < 1L) {
                return null;
            }
            if (lots != null) {
                out.addProperty("lots", lots);
            }
            Long expiry = integral(data.get("expiryDays"));
            if (expiry == null) {
                if (present(data, "expiryDays")) {
                    return null;
                }
            } else {
                if (expiry < 1L || expiry > 365L) {
                    return null;
                }
                out.addProperty("expiryDays", expiry.intValue());
            }
        }
        if ("options_buy".equals(intent) || "options_sell".equals(intent)) {
            String typeRaw = string(data, "optionType");
            if (typeRaw == null) {
                return null;
            }
            if (!typeRaw.trim().isEmpty()) {
                String typeCode = optionType(typeRaw);
                if (typeCode == null) {
                    return null;
                }
                out.addProperty("optionType", typeCode);
            }
            Long strike = integral(data.get("strikeCents"));
            if (strike == null) {
                if (present(data, "strikeCents")) {
                    return null;
                }
            } else {
                if (strike < 0L) {
                    return null;
                }
                out.addProperty("strikeCents", strike);
            }
            Long expiry = integral(data.get("expiryDays"));
            if (expiry == null) {
                if (present(data, "expiryDays")) {
                    return null;
                }
            } else {
                if (expiry < 1L || expiry > 365L) {
                    return null;
                }
                out.addProperty("expiryDays", expiry.intValue());
            }
            Long contracts = integral(data.get("contracts"));
            if (contracts == null) {
                if (present(data, "contracts")) {
                    return null;
                }
            } else {
                if (contracts < 0L) {
                    return null;
                }
                out.addProperty("contracts", contracts);
            }
        }
        return out;
    }

    // ── news 任务 ──

    /** 校验并整理新闻数据；不合规返回 {@code null}。 */
    public static JsonObject validateNews(JsonObject data) {
        if (data == null) {
            return null;
        }
        String headline = string(data, "headline");
        if (headline == null || cleanLine(headline).isEmpty()) {
            return null;
        }
        String body = string(data, "body");
        if (body == null || cleanLine(body).isEmpty()) {
            return null;
        }
        JsonArray effects = new JsonArray();
        if (present(data, "effects")) {
            JsonElement effectsElement = data.get("effects");
            if (effectsElement == null || !effectsElement.isJsonArray()) {
                return null;
            }
            JsonArray input = effectsElement.getAsJsonArray();
            if (input.size() > MAX_NEWS_EFFECTS) {
                return null;
            }
            for (JsonElement entry : input) {
                if (entry == null || !entry.isJsonObject()) {
                    return null;
                }
                JsonObject effect = entry.getAsJsonObject();
                String target = string(effect, "target");
                if (target == null || target.trim().isEmpty()) {
                    return null;
                }
                Double multiplier = decimal(effect.get("multiplier"));
                if (multiplier == null || multiplier < 0.85 || multiplier > 1.20) {
                    return null;
                }
                Long ticks = integral(effect.get("ticks"));
                if (ticks == null || ticks < 3L || ticks > 12L) {
                    return null;
                }
                JsonObject outEffect = new JsonObject();
                outEffect.addProperty("target", truncate(cleanLine(target), 80));
                outEffect.addProperty("multiplier", multiplier);
                outEffect.addProperty("ticks", ticks.intValue());
                effects.add(outEffect);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("headline", truncate(cleanLine(headline), 120));
        out.addProperty("body", truncate(cleanLine(body), 1000));
        out.add("effects", effects);
        return out;
    }

    // ── report 任务 ──

    /** 校验并整理财报数据；不合规返回 {@code null}。 */
    public static JsonObject validateReport(JsonObject data) {
        if (data == null) {
            return null;
        }
        Long revenue = integral(data.get("revenueCents"));
        if (revenue == null || !present(data, "revenueCents") || revenue < 0L) {
            return null;
        }
        Long profit = integral(data.get("profitCents"));
        if (profit == null || !present(data, "profitCents") || profit < 0L) {
            return null;
        }
        String outlookText = string(data, "outlook");
        if (outlookText == null) {
            return null;
        }
        String outlook = outlookText.trim().toLowerCase(Locale.ROOT);
        if (!OUTLOOKS.contains(outlook)) {
            return null;
        }
        String headline = string(data, "headline");
        if (headline == null || cleanLine(headline).isEmpty()) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("revenueCents", revenue);
        out.addProperty("profitCents", profit);
        out.addProperty("outlook", outlook);
        out.addProperty("headline", truncate(cleanLine(headline), 120));
        return out;
    }

    // ── prospectus 任务 ──

    /** 校验并整理招股书数据；title 为空或字段类型不对返回 {@code null}。 */
    public static JsonObject validateProspectus(JsonObject data) {
        if (data == null) {
            return null;
        }
        String title = string(data, "title");
        String summary = string(data, "summary");
        String risk = string(data, "risk");
        String useOfFunds = string(data, "useOfFunds");
        if (title == null || summary == null || risk == null || useOfFunds == null) {
            return null;
        }
        String cleanTitle = cleanLine(title);
        if (cleanTitle.isEmpty()) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("title", truncate(cleanTitle, 80));
        out.addProperty("summary", truncate(cleanLine(summary), 800));
        out.addProperty("risk", truncate(cleanLine(risk), 800));
        out.addProperty("useOfFunds", truncate(cleanLine(useOfFunds), 800));
        return out;
    }

    // ── government 任务 ──

    /**
     * 校验并整理政府政策建议。
     *
     * <p>除了 action 枚举，每种政策还必须带上设计里要求的参数，并且落在边界内；不合规返回
     * {@code null}，GovernmentEngine 会改用本地规则表。{@code none} 的 params 必须是空对象。</p>
     */
    public static JsonObject validateGovernment(JsonObject data) {
        if (data == null) {
            return null;
        }
        String actionText = string(data, "action");
        if (actionText == null) {
            return null;
        }
        String action = actionText.trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return null;
        }
        JsonObject input;
        if (present(data, "params")) {
            JsonElement paramsElement = data.get("params");
            if (paramsElement == null || !paramsElement.isJsonObject()) {
                return null;
            }
            input = paramsElement.getAsJsonObject();
        } else {
            input = new JsonObject();
        }
        JsonObject params = new JsonObject();
        switch (action) {
            case "none":
                if (!input.keySet().isEmpty()) {
                    return null;
                }
                break;
            case "rate_cut":
            case "rate_hike": {
                Long bp = integral(input.get("bp"));
                if (bp == null || !RATE_BPS.contains(bp.intValue())) {
                    return null;
                }
                params.addProperty("bp", bp.intValue());
                break;
            }
            case "welfare": {
                Long amount = integral(input.get("amountCents"));
                if (amount == null || amount < 1L || amount > 50_000L) {
                    return null;
                }
                params.addProperty("amountCents", amount);
                break;
            }
            case "stimulus": {
                Long amount = integral(input.get("amountCents"));
                if (amount == null || amount < 1L) {
                    return null;
                }
                Long days = integral(input.get("days"));
                if (days == null || days < 1L || days > 30L) {
                    return null;
                }
                JsonArray sectors = stringArray(input.get("sectors"), 1, 10);
                if (sectors == null) {
                    return null;
                }
                params.addProperty("amountCents", amount);
                params.addProperty("days", days.intValue());
                params.add("sectors", sectors);
                break;
            }
            case "austerity": {
                Double ratio = decimal(input.get("ratio"));
                if (ratio == null || ratio < 0.0 || ratio > 0.02) {
                    return null;
                }
                params.addProperty("ratio", ratio);
                break;
            }
            case "subsidy":
            case "tariff": {
                String targetText = string(input, "target");
                if (targetText == null) {
                    return null;
                }
                String target = targetText.trim().toLowerCase(Locale.ROOT);
                if (!ITEM_ID.matcher(target).matches()) {
                    return null;
                }
                Double ratio = decimal(input.get("ratio"));
                if (ratio == null || ratio < 0.0 || ratio > 0.2) {
                    return null;
                }
                Long days = integral(input.get("days"));
                if (days == null || days < 1L || days > 30L) {
                    return null;
                }
                params.addProperty("target", target);
                params.addProperty("ratio", ratio);
                params.addProperty("days", days.intValue());
                break;
            }
            case "liquidity": {
                Double ratio = decimal(input.get("ratio"));
                if (ratio == null || ratio < 0.0 || ratio > 1.0) {
                    return null;
                }
                params.addProperty("ratio", ratio);
                break;
            }
            case "min_wage": {
                Long amount = integral(input.get("amountCents"));
                if (amount == null || amount < 1000L || amount > 5000L) {
                    return null;
                }
                params.addProperty("amountCents", amount);
                break;
            }
            default:
                return null;
        }

        String reasonText = string(data, "reason");
        if (reasonText == null) {
            reasonText = "";
        }
        String announcementText = string(data, "announcement");
        if (announcementText == null) {
            announcementText = "";
        }
        JsonObject out = new JsonObject();
        out.addProperty("action", action);
        out.add("params", params);
        out.addProperty("reason", truncate(cleanLine(reasonText), 120));
        out.addProperty("announcement", truncate(cleanLine(announcementText), 200));
        return out;
    }

    // ── 取值小工具（全部容错，不合规返回 null）──

    /** 字段存在且不是 JSON null。 */
    private static boolean present(JsonObject parent, String key) {
        return parent != null && parent.has(key) && !parent.get(key).isJsonNull();
    }

    /** 取字符串字段：缺省返回 ""，类型不对（对象/数组/数字）返回 null。 */
    private static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return "";
        }
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    /** 取整数：接受整数值的 JSON 数字或数字字符串；小数 / 非数字 / 布尔 / 对象返回 null。 */
    private static Long integral(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return null;
        }
        try {
            double value;
            if (primitive.isNumber()) {
                value = primitive.getAsDouble();
            } else {
                String text = primitive.getAsString().trim();
                if (text.isEmpty() || text.length() > 18) {
                    return null;
                }
                value = Double.parseDouble(text);
            }
            if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
                return null;
            }
            if (value > 9.0e15 || value < -9.0e15) {
                return null;
            }
            return Math.round(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 取小数：接受 JSON 数字或数字字符串；NaN / 无穷 / 非数字返回 null。 */
    private static Double decimal(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return null;
        }
        try {
            double value;
            if (primitive.isNumber()) {
                value = primitive.getAsDouble();
            } else {
                value = Double.parseDouble(primitive.getAsString().trim());
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return value;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 字符串数组：大小和元素类型都合规才返回，否则 null。 */
    private static JsonArray stringArray(JsonElement element, int minSize, int maxSize) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return null;
        }
        JsonArray input = element.getAsJsonArray();
        if (input.size() < minSize || input.size() > maxSize) {
            return null;
        }
        JsonArray out = new JsonArray();
        for (JsonElement entry : input) {
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                return null;
            }
            String text = entry.getAsString().trim();
            if (text.isEmpty()) {
                return null;
            }
            out.add(truncate(text, 40));
        }
        return out;
    }

    /** 行业：接受代码或中文名；不认识返回 null（命令层会用自己的默认行业）。 */
    private static String sectorCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (SECTOR_CODES.contains(value)) {
            return value;
        }
        switch (raw.trim()) {
            case "采矿":
            case "矿业":
            case "挖矿":
            case "矿":
                return "mining";
            case "农业":
            case "农场":
            case "种植":
                return "farming";
            case "制造":
            case "工业":
            case "工厂":
                return "manufacturing";
            case "贸易":
            case "商贸":
                return "trading";
            case "服务":
                return "service";
            default:
                return null;
        }
    }

    /** 期权类型：call / put，也接受看涨 / 看跌；不认识返回 null。 */
    private static String optionType(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (OPTION_TYPES.contains(value)) {
            return value;
        }
        switch (raw.trim()) {
            case "看涨":
            case "看涨期权":
                return "call";
            case "看跌":
            case "看跌期权":
                return "put";
            default:
                return null;
        }
    }

    private static String cleanLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
