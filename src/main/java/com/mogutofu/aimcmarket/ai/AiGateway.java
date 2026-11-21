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

    /** ping 一下，看接口是否可用（用于 /ex admin stats 显示链路状态）。 */
    boolean ping();

    /** 最近一次调用命中的链路：exchange / legacy / local / disabled。 */
    String lastRoute();
}
