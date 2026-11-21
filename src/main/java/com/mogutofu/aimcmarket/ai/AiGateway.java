package com.mogutofu.aimcmarket.ai;

import com.google.gson.JsonObject;

/**
 * AI 接口的窄契约（数据层/引擎层只依赖这个接口，方便单测注入假实现）。
 *
 * <p>所有返回都是<b>建议</b>：解析结果、文案、政策建议。价格、余额、扣款、发货、保证金、
 * 交割、政策落地一律在本地计算和校验，AI 没有直接改账的权限。</p>
 */
public interface AiGateway {
    /** 自然语言 → 订单参数。 */
    JsonObject parse(String playerName, String text, JsonObject context);

    /** 生成一条市场新闻。 */
    JsonObject news(JsonObject context);

    /** 生成一份财报。 */
    JsonObject report(JsonObject context);

    /** 生成一份招股书。 */
    JsonObject prospectus(JsonObject context);

    /** 政府政策建议。 */
    JsonObject government(JsonObject context);

    /**
     * 普通玩家问答（只读）：把市场上下文和玩家问题一起交给 AI，返回一段中文自然语言回答。
     *
     * <p>不是下单接口：返回值只给玩家看，模组不会据此改任何数据。
     * AI 不可用/超时/回复为空时返回 null，调用方用本地规则回答。</p>
     */
    String chat(String playerName, String text, JsonObject context);

    /**
     * 给目录里还没有的物品估一个入市基准价（分）；拿不到返回 -1，调用方用本地启发式兜底。
     * 只返回一个数字，价格最终由模组写进目录。
     */
    default long estimatePriceCents(String itemId, String displayName, JsonObject context) {
        return -1L;
    }

    /**
     * 推断中文物品名对应的注册 id：返回 {keywords:[英文关键词], candidates:[ns:path]}。
     * 拿不到返回 null（调用方给玩家替代方案）。
     */
    default JsonObject resolveItemId(String zhName, JsonObject context) {
        return null;
    }

    /**
     * 一次 AI 调用判断玩家想买/卖什么：返回
     * {@code {action:"buy|sell|none", quantity, itemName, candidates:[ns:path], keywords:[...], priceCents}}。
     * 失败返回 null。
     */
    default JsonObject parseTradeIntent(String playerName, String text, JsonObject context) {
        return null;
    }

    /** ping 一下，看接口是否可用（用于 /ex admin stats 显示链路状态）。 */
    boolean ping();

    /** 最近一次调用命中的链路：exchange / legacy / local / disabled。 */
    String lastRoute();
}
