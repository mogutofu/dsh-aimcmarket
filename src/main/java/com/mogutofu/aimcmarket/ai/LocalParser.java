package com.mogutofu.aimcmarket.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.util.Cents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 不可用时的本地自然语言解析器。
 *
 * <p>只认简单、明确的句式（"买 3 个铁锭"、"卖两个钻石"、"买 100 股 BLK"、"存两百"……），
 * 认不出来就返回 {@code unknown}，<b>不猜</b>——宁可让玩家看到用法提示，也不能拿错参数去下单。
 * 输出形状和 AI {@code parse} 完全一致，并且最后会再过一遍 {@link AiJson#validateParse} 的 schema 校验。</p>
 *
 * <p>不依赖 Minecraft，也不碰账户/价格，只做文本 → JSON。</p>
 */
public final class LocalParser {
    private static final Pattern ARABIC_NUMBER = Pattern.compile("\\d+(?:\\.\\d{1,2})?");
    private static final Pattern CHINESE_NUMBER = Pattern.compile("[零〇一二三四五六七八九十两百千万半]+");
    private static final Pattern STOCK_CODE =
            Pattern.compile("(?<![A-Za-z])([A-Za-z]{2,5}|P\\d{3})(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern FUND_CODE = Pattern.compile("(?i)(FND\\d{1,2})");
    private static final Pattern FUTURES_SYMBOL =
            Pattern.compile("(?<![A-Za-z])(IRON|GOLD|DIAMOND|EMERALD|WHEAT)(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    /** 玩家名（Java 版只允许 ASCII 字母/数字/下划线）。 */
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{1,15}");
    /** "Steve100" 这种名字和金额粘在一起的写法。 */
    private static final Pattern PLAYER_NAME_AMOUNT = Pattern.compile("^([A-Za-z_]{2,15})(\\d+)$");
    /** 这些英文词是动词/名词，不能当成股票代码。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "BUY", "SELL", "STOCK", "STOCKS", "SHARE", "SHARES", "FUND", "FUNDS",
            "OPT", "OPTION", "OPTIONS", "FUT", "FUTURES", "CALL", "PUT", "LONG", "SHORT",
            "MINE", "MINECRAFT", "THE", "AND", "FOR", "EX", "AI");

    private static final int MAX_ITEM_COUNT = AiJson.MAX_ITEM_COUNT;
    private static final long MAX_AMOUNT_CENTS = 100_000_000_00L; // 1 亿币

    private LocalParser() {
    }

    /** 没听懂时给玩家的一句中文提示。 */
    public static String helpHint() {
        return "没听懂，试试 /ex buy iron_pickaxe 3";
    }

    /**
     * 把一句中文/英文自然语言解析成 AI {@code parse} 同形状的 JSON。
     *
     * @param text   玩家原话（不含 /ex 前缀也没关系，带前缀也能剥掉）
     * @param market 商品目录，用于别名匹配和 enabled 校验；可以为 {@code null}（只做格式校验）
     * @return 永远不为 {@code null}；解析不出来时 intent 是 {@code unknown}，reply 是用法提示
     */
    public static JsonObject parse(String text, MarketStore market) {
        try {
            JsonObject result = parseInner(text, market);
            if (result == null) {
                return unknown(helpHint());
            }
            JsonObject checked = AiJson.validateParse(result, market);
            return checked != null ? checked : unknown(helpHint());
        } catch (RuntimeException error) {
            // 本地解析也绝不能把异常抛给命令层
            return unknown(helpHint());
        }
    }

    private static JsonObject parseInner(String text, MarketStore market) {
        if (text == null) {
            return null;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 120) {
            return unknown("指令太长，试试 /ex buy iron_pickaxe 3");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);

        // 顺序很重要：越具体的动词越靠前（"买基金"不能落到"买"）
        if (containsAny(lower, "开公司", "创建公司", "开个公司", "建公司")) {
            return companyCreate(normalized);
        }
        if (containsAny(lower, "上市", "ipo")) {
            return unknown("上市请用 /ex ipo apply <10-49 的流通比例>");
        }
        if (containsAny(lower, "期权", "option")) {
            return unknown("期权请用 /ex opt quote|buy|sell 结构化命令");
        }
        if (containsAny(lower, "期货", "futures", "做多", "做空", "多单", "空单")) {
            return futures(normalized, lower, market);
        }
        if (FUND_CODE.matcher(normalized).find() || containsAny(lower, "基金", "fund")) {
            return fund(normalized, lower, market);
        }
        if (containsAny(lower, "还贷", "还款", "还借", "repay")) {
            return unknown("还贷请用 /ex bank loan repay <金额>");
        }
        if (containsAny(lower, "存款", "存钱", "存入", "存")) {
            return bank(normalized, true);
        }
        if (containsAny(lower, "取款", "取钱", "取出", "取")) {
            return bank(normalized, false);
        }
        if (containsAny(lower, "贷款", "借款", "借钱")) {
            return loan(normalized);
        }
        if (containsAny(lower, "打工", "work")) {
            return simple("work", "打工结果看 /ex work");
        }
        if (containsAny(lower, "签到", "打卡", "daily")) {
            return simple("daily", "签到结果看 /ex daily");
        }
        if (containsAny(lower, "政府", "政策", "央行", "gov")) {
            return simple("gov_status", "看政府状态用 /ex gov status");
        }
        if (containsAny(lower, "余额", "balance")) {
            return simple("balance", "余额看 /ex balance");
        }
        if (containsAny(lower, "帮助", "help", "怎么用", "用法")) {
            return simple("help", "");
        }
        if (containsAny(lower, "新闻", "news")) {
            return unknown("看新闻用 /ex news");
        }
        if (containsAny(lower, "大盘", "指数", "行情", "board")) {
            return unknown("看大盘用 /ex board");
        }
        if (containsAny(lower, "股", "stock", "share")) {
            return stock(normalized, lower, market);
        }
        if (containsAny(lower, "价格", "多少钱", "什么价", "price")) {
            return price(normalized, market);
        }
        if (containsAny(lower, "转账", "转给", "付给", "pay")) {
            return pay(normalized);
        }
        if (containsAny(lower, "买", "购买", "买点", "buy")) {
            return itemTrade(normalized, lower, market, true);
        }
        if (containsAny(lower, "卖", "出售", "卖掉", "sell")) {
            return itemTrade(normalized, lower, market, false);
        }
        return null;
    }

    // ── 各类意图 ──

    private static JsonObject itemTrade(String normalized, String lower, MarketStore market, boolean buy) {
        boolean hasBuy = containsAny(lower, "买", "购买", "buy");
        boolean hasSell = containsAny(lower, "卖", "出售", "sell");
        if (hasBuy && hasSell) {
            return unknown("买还是卖？试试 /ex buy iron_pickaxe 3");
        }
        boolean isBuy = hasBuy || (!hasSell && buy);
        ItemMatch match = findItem(normalized, market);
        if (match == null) {
            return unknown((isBuy ? "买" : "卖") + "什么？试试 /ex " + (isBuy ? "buy" : "sell") + " iron_pickaxe 3");
        }
        String rest = match.matchedText == null ? normalized : removeIgnoringCase(normalized, match.matchedText);
        List<Double> numbers = findNumbers(rest);
        long count = 1L;
        if (numbers.size() > 1) {
            return unknown("一次说清数量，试试 /ex buy iron_pickaxe 3");
        }
        if (numbers.size() == 1) {
            Double value = numbers.get(0);
            if (!isIntegral(value) || value < 1.0 || value > (double) MAX_ITEM_COUNT) {
                return unknown("数量要在 1-" + MAX_ITEM_COUNT + " 之间的整数");
            }
            count = Math.round(value);
        }
        String name = match.item.nameZh == null || match.item.nameZh.isEmpty() ? match.item.id : match.item.nameZh;
        return result(isBuy ? "buy" : "sell", singleItem(match.item.id, count), "", 0L, 0L,
                (isBuy ? "买入 " : "卖出 ") + name + " ×" + count);
    }

    private static JsonObject price(String normalized, MarketStore market) {
        ItemMatch match = findItem(normalized, market);
        if (match == null) {
            return unknown("查什么价格？试试 /ex price iron_ingot");
        }
        String name = match.item.nameZh == null || match.item.nameZh.isEmpty() ? match.item.id : match.item.nameZh;
        return result("price", singleItem(match.item.id, 1L), "", 0L, 0L, name + " 的现价用 /ex price 查");
    }

    /** 转账：命令层读 player + amountCents，所以这两个字段都要给。 */
    private static JsonObject pay(String normalized) {
        String rest = normalized;
        String lower = rest.toLowerCase(Locale.ROOT);
        for (String keyword : new String[]{"转账给", "转给", "付给", "转账", "pay"}) {
            int index = lower.indexOf(keyword);
            if (index >= 0) {
                rest = rest.substring(index + keyword.length());
                break;
            }
        }
        Matcher nameMatcher = PLAYER_NAME.matcher(rest);
        String target = null;
        if (nameMatcher.find()) {
            target = nameMatcher.group();
            rest = rest.substring(0, nameMatcher.start()) + " " + rest.substring(nameMatcher.end());
        }
        if (target == null || target.isEmpty()) {
            return unknown("转给谁？试试 /ex pay Steve 100");
        }
        List<Double> numbers = findNumbers(rest);
        Double value = singleNumber(numbers);
        if (value == null) {
            // "付给Steve100"：名字和金额粘在一起，拆一下
            Matcher split = PLAYER_NAME_AMOUNT.matcher(target);
            if (split.matches()) {
                target = split.group(1);
                try {
                    value = Double.parseDouble(split.group(2));
                } catch (NumberFormatException ignored) {
                    value = null;
                }
            }
        }
        if (value == null) {
            return unknown("转多少？试试 /ex pay " + target + " 100");
        }
        long cents = toCents(value);
        if (cents < 1L) {
            return unknown("金额不对，试试 /ex pay " + target + " 100");
        }
        JsonObject json = result("pay", new JsonArray(), "", 0L, cents,
                "转给 " + target + " " + Cents.format(cents) + " 币");
        json.addProperty("player", target);
        return json;
    }

    private static JsonObject stock(String normalized, String lower, MarketStore market) {
        boolean sell = containsAny(lower, "卖", "出售", "sell", "抛");
        boolean buy = containsAny(lower, "买", "购买", "buy");
        boolean info = containsAny(lower, "信息", "详情", "怎么样", "查询", "查", "看看", "info");
        String symbol = findStockSymbol(normalized, market);
        if (symbol == null) {
            if (containsAny(lower, "列表", "有哪些", "什么股", "list")) {
                return unknown("看股票列表用 /ex stock list");
            }
            if (info) {
                return unknown("看个股信息用 /ex stock info BLK");
            }
            return unknown("股票代码没听出来，试试 /ex stock buy BLK 10");
        }
        if (buy && sell) {
            return unknown("买还是卖？试试 /ex stock buy BLK 10");
        }
        if (info && !buy && !sell) {
            return result("stock_info", new JsonArray(), symbol, 0L, 0L, "看股票详情用 /ex stock info " + symbol);
        }
        if (!buy && !sell) {
            return result("stock_info", new JsonArray(), symbol, 0L, 0L, "看股票详情用 /ex stock info " + symbol);
        }
        String rest = removeIgnoringCase(normalized, symbol);
        List<Double> numbers = findNumbers(rest);
        Double shareValue = singleNumber(numbers);
        if (shareValue == null) {
            return unknown((sell ? "卖" : "买") + "多少股？试试 /ex stock " + (sell ? "sell" : "buy") + " "
                    + symbol + " 10");
        }
        if (!isIntegral(shareValue) || shareValue < 1.0 || shareValue > 100_000_000.0) {
            return unknown("股数要是不超过 1 亿的整数，试试 /ex stock buy " + symbol + " 10");
        }
        long shares = Math.round(shareValue);
        return result(sell ? "stock_sell" : "stock_buy", new JsonArray(), symbol, shares, 0L,
                (sell ? "卖出 " : "买入 ") + symbol + " " + shares + " 股");
    }

    private static JsonObject fund(String normalized, String lower, MarketStore market) {
        Matcher matcher = FUND_CODE.matcher(normalized);
        String code = null;
        if (matcher.find()) {
            code = matcher.group(1).toUpperCase(Locale.ROOT);
        }
        if (code == null) {
            return unknown("基金代码没听出来，比如 FND01，试试 /ex fund buy FND01 100");
        }
        String rest = FUND_CODE.matcher(normalized).replaceAll(" ");
        boolean sell = containsAny(lower, "赎回", "卖", "sell");
        boolean buy = containsAny(lower, "买", "申购", "buy");
        if (buy && sell) {
            return unknown("买还是卖基金？试试 /ex fund buy FND01 100");
        }
        if (!buy && !sell) {
            return result("fund_info", new JsonArray(), code, 0L, 0L, "看基金详情用 /ex fund info " + code);
        }
        List<Double> numbers = findNumbers(rest);
        Double value = singleNumber(numbers);
        if (value == null) {
            return unknown((sell ? "赎回" : "申购") + "多少？试试 /ex fund " + (sell ? "sell" : "buy") + " " + code
                    + " 100");
        }
        if (sell) {
            if (!isIntegral(value) || value < 1.0 || value > 1_000_000_000.0) {
                return unknown("赎回份额要是整数，试试 /ex fund sell " + code + " 100");
            }
            long shares = Math.round(value);
            JsonObject json = result("fund_sell", new JsonArray(), code, shares, 0L,
                    "赎回 " + code + " " + shares + " 份");
            // 命令层读 unitsMilli（1 份 = 1000）；shares 已限制在 1e9 以内，乘 1000 不会溢出
            json.addProperty("unitsMilli", shares * 1000L);
            return json;
        }
        long cents = toCents(value);
        if (cents < 1L) {
            return unknown("金额不对，试试 /ex fund buy " + code + " 100");
        }
        return result("fund_buy", new JsonArray(), code, 0L, cents, "申购 " + code + " " + Cents.format(cents) + " 币");
    }

    private static JsonObject bank(String normalized, boolean deposit) {
        List<Double> numbers = findNumbers(normalized);
        Double value = singleNumber(numbers);
        if (value == null) {
            return unknown(deposit ? "存多少？试试 /ex bank deposit 100" : "取多少？试试 /ex bank withdraw 100");
        }
        long cents = toCents(value);
        if (cents < 1L) {
            return unknown("金额不对，试试 /ex bank deposit 100");
        }
        return result(deposit ? "deposit" : "withdraw", new JsonArray(), "", 0L, cents,
                (deposit ? "存入 " : "取出 ") + Cents.format(cents) + " 币");
    }

    private static JsonObject loan(String normalized) {
        List<Double> numbers = findNumbers(normalized);
        Double value = singleNumber(numbers);
        if (value == null) {
            return unknown("贷多少？试试 /ex bank loan take 100");
        }
        long cents = toCents(value);
        if (cents < 1L) {
            return unknown("金额不对，试试 /ex bank loan take 100");
        }
        return result("bank_loan", new JsonArray(), "", 0L, cents, "申请贷款 " + Cents.format(cents) + " 币");
    }

    private static JsonObject futures(String normalized, String lower, MarketStore market) {
        boolean longPosition = containsAny(lower, "做多", "多单", "买多", "long");
        boolean shortPosition = containsAny(lower, "做空", "空单", "卖空", "short");
        if (longPosition == shortPosition) {
            return unknown("做多还是做空？试试 /ex fut long IRON 1 7");
        }
        String symbol = futuresSymbol(normalized, market);
        if (symbol == null) {
            return unknown("期货标的没认出来，支持 IRON/GOLD/DIAMOND/EMERALD/WHEAT，试试 /ex fut long IRON 1 7");
        }
        String rest = removeIgnoringCase(normalized, symbol);
        List<Double> numbers = findNumbers(rest);
        Double lotsValue = singleNumber(numbers);
        if (lotsValue == null) {
            return unknown("手数没听清，试试 /ex fut " + (longPosition ? "long" : "short") + " " + symbol + " 1 7");
        }
        if (!isIntegral(lotsValue) || lotsValue < 1.0 || lotsValue > 1000.0) {
            return unknown("手数要在 1-1000 之间的整数");
        }
        long lots = Math.round(lotsValue);
        JsonObject json = result(longPosition ? "futures_long" : "futures_short", new JsonArray(), symbol, lots, 0L,
                (longPosition ? "做多 " : "做空 ") + symbol + " " + lots + " 手");
        // 命令层读 lots；shares 只是契约里的通用字段，两个都填
        json.addProperty("lots", lots);
        return json;
    }

    private static JsonObject companyCreate(String normalized) {
        String[] keywords = {"创建公司", "开公司", "开个公司", "建公司"};
        String name = null;
        for (String keyword : keywords) {
            int index = normalized.indexOf(keyword);
            if (index >= 0) {
                name = normalized.substring(index + keyword.length()).trim();
                break;
            }
        }
        if (name != null) {
            name = name.replaceAll("^(?:叫|名字|名|为|是|：|:)\\s*", "").trim();
            name = name.replaceAll("\\s+", "");
        }
        // 名字放在 reply 里给玩家看；命令层读 companyName/sector，所以这两个字段也要显式带上
        if (name == null || name.length() < 2 || name.length() > 16) {
            return unknown("公司名字 2-16 个字，试试 /ex company create 铜矿工坊");
        }
        JsonObject json = result("company_create", new JsonArray(), "", 0L, 0L, "创建公司「" + name + "」");
        json.addProperty("companyName", name);
        String sector = sectorFromText(normalized);
        if (sector != null) {
            json.addProperty("sector", sector);
        }
        return json;
    }

    /** 从原话里猜行业（命令层不传就默认 mining）；只在出现明确行业词时返回。 */
    private static String sectorFromText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "采矿", "矿业", "挖矿", "mining", "矿")) {
            return "mining";
        }
        if (containsAny(lower, "农业", "农场", "种植", "farming", "farm")) {
            return "farming";
        }
        if (containsAny(lower, "制造", "工业", "工厂", "manufacturing", "factory")) {
            return "manufacturing";
        }
        if (containsAny(lower, "贸易", "商贸", "trading", "trade", "生意")) {
            return "trading";
        }
        if (containsAny(lower, "服务", "service")) {
            return "service";
        }
        return null;
    }

    private static String futuresSymbol(String text, MarketStore market) {
        Matcher matcher = FUTURES_SYMBOL.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        ItemMatch match = findItem(text, market);
        if (match == null) {
            return null;
        }
        switch (match.item.id) {
            case "minecraft:iron_ingot":
                return "IRON";
            case "minecraft:gold_ingot":
                return "GOLD";
            case "minecraft:diamond":
                return "DIAMOND";
            case "minecraft:emerald":
                return "EMERALD";
            case "minecraft:wheat":
                return "WHEAT";
            default:
                return null;
        }
    }

    private static String findStockSymbol(String text, MarketStore market) {
        Matcher matcher = STOCK_CODE.matcher(text);
        List<String> candidates = new ArrayList<>();
        while (matcher.find()) {
            String code = matcher.group(1).toUpperCase(Locale.ROOT);
            if (code.length() < 2 || STOP_WORDS.contains(code) || candidates.contains(code)) {
                continue;
            }
            // catalog 里的英文别名（iron、gold...）不能当股票代码
            if (market != null && market.resolve(code) != null) {
                continue;
            }
            candidates.add(code);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    // ── 商品别名最长匹配 ──

    private static final class ItemMatch {
        final MarketStore.CatalogItem item;
        final String matchedText;

        ItemMatch(MarketStore.CatalogItem item, String matchedText) {
            this.item = item;
            this.matchedText = matchedText;
        }
    }

    /** 在文本里找 catalog 物品，取最长别名匹配；同长度命中两个不同物品算歧义，返回 null。 */
    private static ItemMatch findItem(String text, MarketStore market) {
        if (text == null || market == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        MarketStore.CatalogItem bestItem = null;
        String bestText = null;
        int bestLength = 0;
        boolean ambiguous = false;
        for (MarketStore.CatalogItem item : market.items()) {
            if (!item.enabled) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            if (item.id != null) {
                candidates.add(item.id);
                if (item.id.startsWith("minecraft:")) {
                    candidates.add(item.id.substring("minecraft:".length()));
                }
            }
            if (item.nameZh != null && !item.nameZh.isEmpty()) {
                candidates.add(item.nameZh);
            }
            if (item.aliases != null) {
                candidates.addAll(item.aliases);
            }
            for (String candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                String needle = candidate.toLowerCase(Locale.ROOT).trim();
                if (needle.isEmpty() || !lower.contains(needle)) {
                    continue;
                }
                if (needle.length() > bestLength) {
                    bestItem = item;
                    bestText = candidate;
                    bestLength = needle.length();
                    ambiguous = false;
                } else if (needle.length() == bestLength && item != bestItem) {
                    ambiguous = true;
                }
            }
        }
        if (ambiguous || bestItem == null) {
            return null;
        }
        return new ItemMatch(bestItem, bestText);
    }

    // ── 数字 / 金额 ──

    /** 文本里所有数字（阿拉伯数字和中文数字都算）；中文 "半" 解析成 0.5。 */
    private static List<Double> findNumbers(String text) {
        List<Double> numbers = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return numbers;
        }
        Matcher arabic = ARABIC_NUMBER.matcher(text);
        while (arabic.find()) {
            try {
                numbers.add(Double.parseDouble(arabic.group()));
            } catch (NumberFormatException ignored) {
                // 太长/坏数字跳过
            }
        }
        Matcher chinese = CHINESE_NUMBER.matcher(text);
        while (chinese.find()) {
            Double value = chineseValue(chinese.group());
            if (value != null) {
                numbers.add(value);
            }
        }
        return numbers;
    }

    private static Double singleNumber(List<Double> numbers) {
        return numbers.size() == 1 ? numbers.get(0) : null;
    }

    /** 中文数字：十一、二十、两百、半、一千、一万…… */
    private static Double chineseValue(String run) {
        if (run == null || run.isEmpty()) {
            return null;
        }
        if ("半".equals(run) || "一半".equals(run)) {
            return 0.5;
        }
        boolean any = false;
        boolean half = false;
        long total = 0L;
        long section = 0L;
        long number = 0L;
        for (int index = 0; index < run.length(); index++) {
            char ch = run.charAt(index);
            int digit = chineseDigit(ch);
            if (digit >= 0) {
                number = digit;
                any = true;
                continue;
            }
            switch (ch) {
                case '十':
                    if (number == 0L) {
                        number = 1L;
                    }
                    section = addSafe(section, multiplySafe(number, 10L));
                    number = 0L;
                    any = true;
                    break;
                case '百':
                    if (number == 0L) {
                        number = 1L;
                    }
                    section = addSafe(section, multiplySafe(number, 100L));
                    number = 0L;
                    any = true;
                    break;
                case '千':
                    if (number == 0L) {
                        number = 1L;
                    }
                    section = addSafe(section, multiplySafe(number, 1000L));
                    number = 0L;
                    any = true;
                    break;
                case '万':
                    section = addSafe(section, number);
                    total = addSafe(total, multiplySafe(section, 10000L));
                    section = 0L;
                    number = 0L;
                    any = true;
                    break;
                case '半':
                    half = true;
                    break;
                default:
                    return null;
            }
            if (total < 0L || section < 0L) {
                return null;
            }
        }
        long value = addSafe(total, addSafe(section, number));
        if (value < 0L) {
            return null;
        }
        if (!any && !half) {
            return null;
        }
        double result = (double) value + (half ? 0.5 : 0.0);
        if (result < 0.0 || result > 1_000_000_000.0) {
            return null;
        }
        return result;
    }

    private static int chineseDigit(char ch) {
        switch (ch) {
            case '零':
            case '〇':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }

    private static boolean isIntegral(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
    }

    /** 币 → 分；非法/过大返回 -1。 */
    private static long toCents(double coins) {
        if (Double.isNaN(coins) || Double.isInfinite(coins) || coins <= 0.0) {
            return -1L;
        }
        double raw = coins * 100.0;
        if (raw > (double) MAX_AMOUNT_CENTS) {
            return -1L;
        }
        long cents = Math.round(raw);
        return cents < 1L ? -1L : cents;
    }

    // ── 文本小工具 ──

    private static String normalize(String text) {
        String value = text.replace('\u3000', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
        value = value.replaceAll("(?i)^\\s*/?(?:ex|exchange|market)\\b\\s*", "");
        value = value.replaceAll("\\s+", " ").trim();
        return value;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String removeIgnoringCase(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return text;
        }
        return Pattern.compile(Pattern.quote(needle), Pattern.CASE_INSENSITIVE).matcher(text).replaceFirst(" ");
    }

    private static long addSafe(long left, long right) {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            return -1L;
        }
        return left + right;
    }

    private static long multiplySafe(long left, long right) {
        if (left < 0L || right < 0L || (right != 0L && left > Long.MAX_VALUE / right)) {
            return -1L;
        }
        return left * right;
    }

    // ── 输出 ──

    private static JsonObject simple(String intent, String reply) {
        return result(intent, new JsonArray(), "", 0L, 0L, reply);
    }

    private static JsonArray singleItem(String id, long count) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("count", count);
        JsonArray items = new JsonArray();
        items.add(item);
        return items;
    }

    private static JsonObject unknown(String reply) {
        return result("unknown", new JsonArray(), "", 0L, 0L, reply == null ? helpHint() : reply);
    }

    private static JsonObject result(String intent, JsonArray items, String symbol, long shares, long amountCents,
                                     String reply) {
        JsonObject json = new JsonObject();
        json.addProperty("intent", intent);
        json.add("items", items == null ? new JsonArray() : items);
        json.addProperty("symbol", symbol == null ? "" : symbol);
        json.addProperty("shares", shares);
        json.addProperty("amountCents", amountCents);
        json.addProperty("reply", reply == null ? "" : reply);
        return json;
    }
}
