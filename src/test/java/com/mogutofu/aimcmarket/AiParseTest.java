package com.mogutofu.aimcmarket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mogutofu.aimcmarket.ai.AiJson;
import com.mogutofu.aimcmarket.ai.LocalParser;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.util.JsonFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 解析层的单元测试：JSON 修复（{@link JsonFile#parseLooseObject}）、schema 校验（{@link AiJson}）、
 * 本地中文解析（{@link LocalParser}）。
 *
 * <p>不联网、不调用任何真实 AI：全部是纯字符串/JSON 处理。</p>
 */
class AiParseTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String IRON = "minecraft:iron_ingot";

    @TempDir
    Path tempDir;

    private AimcContext newContext() {
        return AimcContext.load(tempDir, tempDir.resolve("config"), MarketSettings.defaults(), NOW_MS, SEED);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    // ══════════════════════════════════════════════════════════════
    // JSON 修复
    // ══════════════════════════════════════════════════════════════

    @Test
    void parseLooseObjectHandlesFencesNoiseNestingAndBracesInStrings() {
        JsonObject fenced = JsonFile.parseLooseObject(
                "好的，结果如下：\n```json\n{\"intent\":\"buy\",\"count\":3}\n```\n请确认");
        assertNotNull(fenced, "markdown 包裹的 JSON 应能抠出来");
        assertEquals("buy", fenced.get("intent").getAsString(), "markdown 里的 intent 应保留");
        assertEquals(3, fenced.get("count").getAsInt(), "markdown 里的数字应保留");

        JsonObject noisy = JsonFile.parseLooseObject("这是结果：{\"amount\":1234} 完毕");
        assertNotNull(noisy, "前后有废话的 JSON 应能抠出来");
        assertEquals(1234L, noisy.get("amount").getAsLong(), "前后废话之间的数字应保留");

        JsonObject nested = JsonFile.parseLooseObject("前缀 {\"outer\":{\"inner\":{\"x\":7}}} 后缀");
        assertNotNull(nested, "嵌套花括号应能正确配对");
        assertEquals(7, nested.getAsJsonObject("outer").getAsJsonObject("inner").get("x").getAsInt(),
                "嵌套结构应完整保留");

        JsonObject bracesInString = JsonFile.parseLooseObject("{\"text\":\"}{ 不是括号\",\"n\":2}");
        assertNotNull(bracesInString, "字符串里的花括号不应打断配对");
        assertEquals("}{ 不是括号", bracesInString.get("text").getAsString(), "字符串内容应原样保留");
        assertEquals(2, bracesInString.get("n").getAsInt(), "字符串后面的字段应解析成功");

        assertNull(JsonFile.parseLooseObject("完全没有 JSON"), "没有花括号应返回 null");
        assertNull(JsonFile.parseLooseObject(""), "空串应返回 null");
        assertNull(JsonFile.parseLooseObject(null), "null 应返回 null");
        assertNull(JsonFile.parseLooseObject("[1,2,3]"), "数组不是对象，应返回 null");
    }

    @Test
    void aiJsonRepairsTrailingComma() {
        JsonObject repaired = AiJson.parseLooseObject("好的：{\"a\":1,} 完毕");
        assertNotNull(repaired, "模型常见的尾逗号应被修复");
        assertEquals(1, repaired.get("a").getAsInt(), "修复后字段值应正确");
    }

    // ══════════════════════════════════════════════════════════════
    // parse 任务 schema 校验
    // ══════════════════════════════════════════════════════════════

    @Test
    void validateParseRejectsBadSamples() {
        AimcContext ctx = newContext();
        String[] badSamples = {
                "{}",                                                              // 缺 intent
                "{\"intent\":123}",                                                // intent 类型错
                "{\"intent\":null}",                                               // intent 为 null
                "{\"intent\":\"hack\"}",                                           // 枚举外 intent
                "{\"intent\":\"teleport\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":1}]}",
                "{\"intent\":\"buy\"}",                                            // buy 缺 items
                "{\"intent\":\"buy\",\"items\":[]}",                               // buy items 为空
                "{\"intent\":\"buy\",\"items\":\"not-array\"}",                    // items 类型错
                "{\"intent\":\"buy\",\"items\":[{}]}",                             // item 缺 id
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"iron_ingot\",\"count\":1}]}",        // id 缺命名空间
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:not_a_real_item\",\"count\":1}]}", // 目录外物品
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":0}]}",     // 数量 0
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":577}]}",   // 数量超上限
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":1.5}]}",   // 数量非整数
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":\"abc\"}]}", // 数量类型错
                "{\"intent\":\"stock_buy\",\"symbol\":\"BLK\",\"shares\":0}",       // 股数 0
                "{\"intent\":\"stock_buy\",\"symbol\":\"BLK\",\"shares\":-5}",      // 负股数
                "{\"intent\":\"stock_sell\",\"symbol\":\"\",\"shares\":10}",        // 缺 symbol
                "{\"intent\":\"deposit\",\"amountCents\":0}",                       // 存款金额 0
                "{\"intent\":\"pay\",\"amountCents\":\"abc\"}",                     // 金额类型错
                "{\"intent\":\"company_create\",\"companyName\":\"A\",\"sector\":\"mining\"}", // 公司名太短
                "{\"intent\":\"futures_long\",\"symbol\":\"IRON\",\"lots\":0}",      // 手数 0
                "{\"intent\":\"options_buy\",\"symbol\":\"BLK\",\"optionType\":\"straddle\"}", // 非法期权类型
                "{\"intent\":\"withdraw\",\"amountCents\":5000,\"items\":{}}"        // items 类型错
        };
        for (int i = 0; i < badSamples.length; i++) {
            JsonObject data = json(badSamples[i]);
            assertNull(AiJson.validateParse(data, ctx.market),
                    "第 " + (i + 1) + " 个坏样本不应通过校验：" + badSamples[i]);
        }
        assertNull(AiJson.validateParse(null, ctx.market), "null 输入应返回 null");
    }

    @Test
    void validateParseAcceptsGoodSamples() {
        AimcContext ctx = newContext();
        String[] goodSamples = {
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":3}],\"reply\":\"买3个铁锭\"}",
                "{\"intent\":\"sell\",\"items\":[{\"id\":\"minecraft:diamond\",\"count\":1}]}",
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\"}]}",
                "{\"intent\":\"stock_buy\",\"symbol\":\"blk\",\"shares\":10}",
                "{\"intent\":\"stock_info\",\"symbol\":\"BLK\"}",
                "{\"intent\":\"deposit\",\"amountCents\":20000}",
                "{\"intent\":\"withdraw\",\"amountCents\":20000}",
                "{\"intent\":\"pay\",\"amountCents\":1000,\"player\":\"Steve\"}",
                "{\"intent\":\"work\"}",
                "{\"intent\":\"daily\"}",
                "{\"intent\":\"gov_status\"}",
                "{\"intent\":\"help\"}",
                "{\"intent\":\"futures_long\",\"symbol\":\"IRON\",\"lots\":2,\"expiryDays\":7}",
                "{\"intent\":\"options_sell\",\"symbol\":\"BLK\",\"optionType\":\"call\",\"strikeCents\":12000,"
                        + "\"expiryDays\":7,\"contracts\":1}",
                "{\"intent\":\"company_create\",\"companyName\":\"铜矿工坊\",\"sector\":\"采矿\"}",
                "{\"intent\":\"fund_buy\",\"symbol\":\"FND01\",\"amountCents\":10000}",
                "{\"intent\":\"fund_sell\",\"symbol\":\"FND01\",\"shares\":10}",
                "{\"intent\":\"unknown\"}"
        };
        for (String sample : goodSamples) {
            assertNotNull(AiJson.validateParse(json(sample), ctx.market), "正常样本应通过校验：" + sample);
        }

        JsonObject buy = AiJson.validateParse(json(
                "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":3}],\"reply\":\"买3个铁锭\"}"),
                ctx.market);
        assertNotNull(buy, "正常买入样本应通过");
        assertEquals("buy", buy.get("intent").getAsString(), "intent 应保留");
        assertEquals(IRON, buy.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString(),
                "物品 id 应归一化成小写目录 id");
        assertEquals(3, buy.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt(),
                "数量应保留");
        assertEquals("Steve", AiJson.validateParse(json(
                "{\"intent\":\"pay\",\"amountCents\":1000,\"player\":\"Steve\"}"), ctx.market)
                .get("player").getAsString(), "扩展字段 player 应保留");
    }

    // ══════════════════════════════════════════════════════════════
    // government 任务 schema 校验
    // ══════════════════════════════════════════════════════════════

    @Test
    void validateGovernmentRejectsBadSamples() {
        String[] badSamples = {
                "{}",                                                              // 缺 action
                "{\"action\":null}",                                               // action 为 null
                "{\"action\":123}",                                                // action 类型错
                "{\"action\":\"print_money\"}",                                    // 枚举外 action
                "{\"action\":\"rate_cut\"}",                                       // 缺 bp
                "{\"action\":\"rate_cut\",\"params\":{\"bp\":33}}",                // 非法 bp
                "{\"action\":\"welfare\",\"params\":{\"amountCents\":0}}",         // 福利金额 0
                "{\"action\":\"welfare\",\"params\":{\"amountCents\":60000}}",     // 福利金额超上限
                "{\"action\":\"welfare\",\"params\":[]}",                          // params 类型错
                "{\"action\":\"stimulus\",\"params\":{\"amountCents\":1,\"days\":0,\"sectors\":[\"mining\"]}}",
                "{\"action\":\"stimulus\",\"params\":{\"amountCents\":1,\"days\":3,\"sectors\":[]}}",
                "{\"action\":\"stimulus\",\"params\":{\"amountCents\":1,\"days\":3,\"sectors\":[1]}}",
                "{\"action\":\"austerity\",\"params\":{\"ratio\":0.9}}",           // 紧缩比例超限
                "{\"action\":\"subsidy\",\"params\":{\"target\":\"iron\",\"ratio\":0.1,\"days\":3}}", // target 非物品 id
                "{\"action\":\"subsidy\",\"params\":{\"target\":\"minecraft:iron_ingot\",\"ratio\":0.5,\"days\":3}}",
                "{\"action\":\"tariff\",\"params\":{\"target\":\"minecraft:diamond\",\"ratio\":0.1,\"days\":0}}",
                "{\"action\":\"liquidity\",\"params\":{\"ratio\":5}}",             // 流动性比例超限
                "{\"action\":\"min_wage\",\"params\":{\"amountCents\":1}}",        // 最低工资过低
                "{\"action\":\"none\",\"params\":{\"bp\":25}}"                     // none 的 params 必须为空
        };
        for (int i = 0; i < badSamples.length; i++) {
            assertNull(AiJson.validateGovernment(json(badSamples[i])),
                    "第 " + (i + 1) + " 个坏政府样本不应通过校验：" + badSamples[i]);
        }
        assertNull(AiJson.validateGovernment(null), "null 输入应返回 null");
    }

    @Test
    void validateGovernmentAcceptsGoodSamples() {
        String[] goodSamples = {
                "{\"action\":\"none\",\"params\":{}}",
                "{\"action\":\"rate_cut\",\"params\":{\"bp\":25}}",
                "{\"action\":\"rate_hike\",\"params\":{\"bp\":100}}",
                "{\"action\":\"welfare\",\"params\":{\"amountCents\":10000}}",
                "{\"action\":\"stimulus\",\"params\":{\"amountCents\":100000,\"days\":3,\"sectors\":[\"mining\",\"farming\"]}}",
                "{\"action\":\"austerity\",\"params\":{\"ratio\":0.01}}",
                "{\"action\":\"subsidy\",\"params\":{\"target\":\"minecraft:iron_ingot\",\"ratio\":0.1,\"days\":3}}",
                "{\"action\":\"tariff\",\"params\":{\"target\":\"minecraft:diamond\",\"ratio\":0.2,\"days\":30}}",
                "{\"action\":\"liquidity\",\"params\":{\"ratio\":0.5}}",
                "{\"action\":\"min_wage\",\"params\":{\"amountCents\":2000}}"
        };
        for (String sample : goodSamples) {
            assertNotNull(AiJson.validateGovernment(json(sample)), "合法政府样本应通过：" + sample);
        }
        JsonObject validated = AiJson.validateGovernment(json(
                "{\"action\":\"rate_hike\",\"params\":{\"bp\":50},\"reason\":\"通胀高\",\"announcement\":\"加息\"}"));
        assertNotNull(validated, "带 reason/announcement 的合法样本应通过");
        assertEquals("rate_hike", validated.get("action").getAsString(), "action 应保留");
        assertEquals(50, validated.getAsJsonObject("params").get("bp").getAsInt(), "bp 应保留");
    }

    // ══════════════════════════════════════════════════════════════
    // 本地解析器
    // ══════════════════════════════════════════════════════════════

    private static long firstItemCount(JsonObject parsed) {
        return parsed.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsLong();
    }

    @Test
    void localParserUnderstandsChineseNumbersAndItems() {
        AimcContext ctx = newContext();

        JsonObject threeSets = LocalParser.parse("买三组铁锭", ctx.market);
        assertEquals("buy", threeSets.get("intent").getAsString(), "「买三组铁锭」应解析成买入");
        assertEquals(IRON, threeSets.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString(),
                "「铁锭」应匹配到 minecraft:iron_ingot");
        assertEquals(3L, firstItemCount(threeSets), "中文数字「三」应解析成数量 3");

        JsonObject arabic = LocalParser.parse("买 3 个铁锭", ctx.market);
        assertEquals("buy", arabic.get("intent").getAsString(), "「买 3 个铁锭」应解析成买入");
        assertEquals(3L, firstItemCount(arabic), "阿拉伯数字 3 应解析成数量 3");

        JsonObject sell = LocalParser.parse("卖 2 个钻石", ctx.market);
        assertEquals("sell", sell.get("intent").getAsString(), "「卖 2 个钻石」应解析成卖出");
        assertEquals("minecraft:diamond", sell.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("id").getAsString(), "「钻石」应匹配到 minecraft:diamond");
        assertEquals(2L, firstItemCount(sell), "数量应为 2");
    }

    @Test
    void localParserUnderstandsMoneyStocksAndSimpleIntents() {
        AimcContext ctx = newContext();

        JsonObject twoHundred = LocalParser.parse("存两百", ctx.market);
        assertEquals("deposit", twoHundred.get("intent").getAsString(), "「存两百」应解析成存款");
        assertEquals(20_000L, twoHundred.get("amountCents").getAsLong(), "「两百」= 200 币 = 20000 分");

        JsonObject half = LocalParser.parse("存半", ctx.market);
        assertEquals("deposit", half.get("intent").getAsString(), "「存半」应解析成存款");
        assertEquals(50L, half.get("amountCents").getAsLong(), "「半」= 0.5 币 = 50 分");

        JsonObject money = LocalParser.parse("存 200 币", ctx.market);
        assertEquals("deposit", money.get("intent").getAsString(), "「存 200 币」应解析成存款");
        assertEquals(20_000L, money.get("amountCents").getAsLong(), "200 币 = 20000 分");

        JsonObject stock = LocalParser.parse("买 10 股 BLK", ctx.market);
        assertEquals("stock_buy", stock.get("intent").getAsString(), "「买 10 股 BLK」应解析成买股票");
        assertEquals("BLK", stock.get("symbol").getAsString(), "股票代码应为 BLK");
        assertEquals(10L, stock.get("shares").getAsLong(), "股数应为 10");

        assertEquals("work", LocalParser.parse("打工", ctx.market).get("intent").getAsString(),
                "「打工」应解析成 work");
        assertEquals("daily", LocalParser.parse("签到", ctx.market).get("intent").getAsString(),
                "「签到」应解析成 daily");
        assertEquals("balance", LocalParser.parse("余额", ctx.market).get("intent").getAsString(),
                "「余额」应解析成 balance");

        JsonObject board = LocalParser.parse("大盘", ctx.market);
        assertEquals("unknown", board.get("intent").getAsString(),
                "「大盘」当前返回 unknown，引导玩家用 /ex board");
        assertFalse(board.get("reply").getAsString().isEmpty(), "unknown 决策应带中文用法提示");
    }

    @Test
    void localParserFallsBackToUnknown() {
        AimcContext ctx = newContext();
        JsonObject nonsense = LocalParser.parse("今天天气不错", ctx.market);
        assertEquals("unknown", nonsense.get("intent").getAsString(), "解析不出的句子应返回 unknown");
        assertFalse(nonsense.get("reply").getAsString().isEmpty(), "unknown 应给出用法提示");

        JsonObject nullInput = LocalParser.parse(null, ctx.market);
        assertNotNull(nullInput, "LocalParser 永远不能返回 null");
        assertEquals("unknown", nullInput.get("intent").getAsString(), "null 输入应返回 unknown");

        // validateParse 能接受 unknown（命令层据此回标题/提示）
        assertNotNull(AiJson.validateParse(nonsense, ctx.market), "unknown 结果本身应是合法 schema");
    }
}
