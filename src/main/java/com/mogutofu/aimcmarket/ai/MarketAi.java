package com.mogutofu.aimcmarket.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * AI 接入：exchange（严格 JSON）→ legacy（/mc-ai 文本模板）→ 本地解析（调用方兜底）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>构造函数只记参数，<b>不发任何网络请求</b>；</li>
 *   <li>所有方法都是同步阻塞的，由模组的 daemon 线程池调用，内部<b>不自己起线程</b>；</li>
 *   <li>任何失败（超时、连接失败、坏 JSON、schema 不过、预算用完、AI 关闭）都返回 {@code null}
 *       或 {@code false}，<b>绝不把异常抛给调用方</b>，调用方据此走本地兜底；</li>
 *   <li>只请求配置里的 {@code ai.endpoint}，请求头只带必要字段，响应体按 UTF-8 读、大小有上限；</li>
 *   <li>日志只记 task / 链路 / 耗时 / 长度，不记玩家原话。</li>
 * </ul>
 */
public final class MarketAi implements AiGateway {
    /** 固定身份，不用玩家 UUID（和插件约定一致）。 */
    public static final String IDENTITY_PLAYER = "AIMCmarket";
    public static final String IDENTITY_UUID = "00000000-0000-0000-0000-00000000a1c0";

    public static final String ROUTE_EXCHANGE = "exchange";
    public static final String ROUTE_LEGACY = "legacy";
    public static final String ROUTE_LOCAL = "local";
    public static final String ROUTE_BUDGET = "budget";
    public static final String ROUTE_DISABLED = "disabled";
    public static final String ROUTE_ERROR = "error";

    /** 送给模型/插件的玩家消息上限（字符）。 */
    private static final int MAX_MESSAGE_CHARS = 200;
    /** legacy 模板里上下文 JSON 的截断上限（字符）。 */
    private static final int MAX_CONTEXT_CHARS = 12_000;
    /** 响应体读取上限，防止插件抽风把内存吃满。 */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    /** ping 用短超时。 */
    private static final int PING_TIMEOUT_MS = 2_000;
    /** 预算滑窗。 */
    private static final long BUDGET_WINDOW_MS = 3_600_000L;

    private final MarketSettings settings;
    private final MarketStore market;
    /** 最近一小时内真正发过 HTTP 的时间戳（毫秒），只增删不排序，队头最旧。 */
    private final ArrayDeque<Long> budgetLog = new ArrayDeque<>();

    private volatile String lastRoute = ROUTE_LOCAL;

    /**
     * @param settings 运行期配置；传 null 时用户默认配置（方便单测）
     * @param market   商品目录，只用于校验 parse 里的物品 id；可以为 null
     */
    public MarketAi(MarketSettings settings, MarketStore market) {
        this.settings = settings == null ? MarketSettings.defaults() : settings;
        this.market = market;
    }

    // ── AiGateway ──

    @Override
    public JsonObject parse(String playerName, String text, JsonObject context) {
        try {
            if (offline()) {
                return null;
            }
            String message = playerMessage(text);
            if (message.isEmpty()) {
                return null;
            }
            return runTask("parse", message, context);
        } catch (RuntimeException error) {
            return failed("parse", error);
        }
    }

    @Override
    public JsonObject news(JsonObject context) {
        try {
            if (offline()) {
                return null;
            }
            return runTask("news", "生成一条 Minecraft 服务器市场新闻", context);
        } catch (RuntimeException error) {
            return failed("news", error);
        }
    }

    @Override
    public JsonObject report(JsonObject context) {
        try {
            if (offline()) {
                return null;
            }
            String code = JsonFile.str(context, "code", "");
            if (code.isEmpty()) {
                code = JsonFile.str(context, "symbol", "");
            }
            String message = code.isEmpty() ? "生成一份上市公司财报" : "为 " + code + " 生成一份上市公司财报";
            return runTask("report", message, context);
        } catch (RuntimeException error) {
            return failed("report", error);
        }
    }

    @Override
    public JsonObject prospectus(JsonObject context) {
        try {
            if (offline()) {
                return null;
            }
            String name = JsonFile.str(context, "name", "");
            if (name.isEmpty()) {
                name = JsonFile.str(context, "companyName", "");
            }
            String message = name.isEmpty() ? "生成一份 IPO 招股书" : "为「" + name + "」生成一份 IPO 招股书";
            return runTask("prospectus", message, context);
        } catch (RuntimeException error) {
            return failed("prospectus", error);
        }
    }

    @Override
    public JsonObject government(JsonObject context) {
        try {
            if (offline()) {
                return null;
            }
            // 和插件契约里的示例一致；具体宏观数据都在 context 里（含 recentPolicies/cooldowns）
            return runTask("government", "review", context);
        } catch (RuntimeException error) {
            return failed("government", error);
        }
    }

    /**
     * 玩家问答：不做严格 JSON 解析，让 AI 直接回答（只读，不产生任何数据变更）。
     *
     * <p>用 /mc-ai 链路：它背后是能读市场工具的自然语言 agent，适合"帮我看看 XX 股票"这种问题。
     * exchange 的 6 个 task 是给结构化数据用的，不适合开放式问答。</p>
     */
    @Override
    public String chat(String playerName, String text, JsonObject context) {
        try {
            if (offline()) {
                return null;
            }
            if ("exchange".equals(mode())) {
                // 明确只用 exchange 时没有问答能力，交给本地规则
                lastRoute = ROUTE_LOCAL;
                return null;
            }
            String url = buildUrl(settings.aiLegacyPath);
            if (url == null) {
                lastRoute = ROUTE_ERROR;
                return null;
            }
            if (!reserveBudget()) {
                throw new BudgetExceededException();
            }
            JsonObject request = new JsonObject();
            request.addProperty("player", IDENTITY_PLAYER);
            request.addProperty("uuid", IDENTITY_UUID);
            request.addProperty("text", chatPrompt(playerName, text, context));
            long started = System.currentTimeMillis();
            HttpResponse response = post(url, request.toString(), timeoutMs());
            long elapsed = System.currentTimeMillis() - started;
            if (response.status != 200) {
                lastRoute = ROUTE_ERROR;
                Log.warn("[AI] chat 返回 status=" + response.status);
                return null;
            }
            JsonObject root = parseObject(response.body);
            if (root == null || !JsonFile.bool(root, "ok", false)) {
                lastRoute = ROUTE_ERROR;
                Log.warn("[AI] chat 失败：" + (root == null ? "返回不是 JSON" : safeError(root)));
                return null;
            }
            String reply = JsonFile.str(root, "reply", "").trim();
            if (reply.isEmpty()) {
                lastRoute = ROUTE_ERROR;
                return null;
            }
            // 去掉可能出现的 markdown 包裹/代码块
            reply = reply.replace("```", "").trim();
            if (reply.length() > 400) {
                reply = reply.substring(0, 400) + "…";
            }
            lastRoute = ROUTE_LEGACY;
            Log.info("[AI] chat 成功 用时=" + elapsed + "ms 问题长度=" + text.length() + " 回复长度=" + reply.length());
            return reply;
        } catch (IOException | RuntimeException error) {
            lastRoute = ROUTE_ERROR;
            Log.warn("[AI] chat 失败：" + describe(error));
            return null;
        }
    }

    private static String chatPrompt(String playerName, String text, JsonObject context) {
        String player = playerName == null || playerName.isBlank() ? "玩家" : playerName;
        return "你是 Minecraft 服务器「AIMCmarket」的市场先生，正在游戏里回答玩家问题。\n"
                + "玩家（普通玩家，只有只读权限）" + player + " 问：" + text + "\n"
                + "当前市场数据（JSON）：" + contextText(context) + "\n"
                + "要求：直接用中文口语回答，120 字以内；不要 markdown、不要输出 JSON、不要解释你是 AI；"
                + "只根据上面的数据回答，不要编造不存在的股票或物品；"
                + "如果玩家要买卖但物品名服务器不认识（可能是客户端汉化名），告诉他：把该物品拿在主手，"
                + "说 /ex 买手上的 数量；或用 /ex id 查物品 id 后 /ex buy <id> 数量。"
                + "如果玩家想买卖/转账/开公司等，告诉他用对应的 /ex 命令，例如 /ex stock buy BLK 10、/ex buy 铁锭 3。";
    }

    /**
     * 给"目录里还没有的物品"估一个入市基准价（分）。失败返回 -1，调用方用本地启发式兜底。
     *
     * <p>只读操作：AI 只给一个数字，价格最终由模组写进目录并开始跟价格引擎波动。</p>
     */
    public long estimatePriceCents(String itemId, String displayName, JsonObject context) {
        try {
            if (offline()) {
                return -1L;
            }
            if ("exchange".equals(mode())) {
                return -1L;
            }
            String url = buildUrl(settings.aiLegacyPath);
            if (url == null || !reserveBudget()) {
                return -1L;
            }
            JsonObject request = new JsonObject();
            request.addProperty("player", IDENTITY_PLAYER);
            request.addProperty("uuid", IDENTITY_UUID);
            request.addProperty("text", "你是 Minecraft 经济服务器 AIMCmarket 的市场先生。请给物品 " + itemId
                    + "（" + displayName + "）在当前服务器市场估一个合理单价（币/个）。\n"
                    + "参考信息（JSON）：" + contextText(context) + "\n"
                    + "只输出一个 JSON 对象：{\"priceCents\": 整数}，单位是分（1 币 = 100 分），"
                    + "价格范围 100～2000000 分，不要解释、不要 markdown。");
            long started = System.currentTimeMillis();
            HttpResponse response = post(url, request.toString(), Math.max(timeoutMs(), 20_000));
            long elapsed = System.currentTimeMillis() - started;
            if (response.status != 200) {
                return -1L;
            }
            JsonObject root = parseObject(response.body);
            if (root == null || !JsonFile.bool(root, "ok", false)) {
                return -1L;
            }
            String reply = JsonFile.str(root, "reply", "");
            JsonObject data = AiJson.parseLooseObject(reply);
            if (data == null || !data.has("priceCents")) {
                Log.warn("[AI] 估价回复里没有 priceCents：" + (reply.length() > 120 ? reply.substring(0, 120) : reply));
                return -1L;
            }
            long cents = data.get("priceCents").getAsLong();
            cents = Cents.clamp(cents, 100L, 2_000_000L);
            Log.info("[AI] 估价 " + itemId + " = " + Cents.format(cents) + " 币 用时=" + elapsed + "ms");
            return cents;
        } catch (IOException | RuntimeException error) {
            Log.warn("[AI] 估价失败：" + describe(error));
            return -1L;
        }
    }

    /**
     * 让 AI 推断"这个中文名对应哪个已注册物品 id"：返回 {keywords:[英文关键词], candidates:[ns:path]}。
     *
     * <p>服务端没有客户端汉化名时用这个兜底：AI 给英文关键词，模组再拿关键词去注册表里匹配。
     * AI 不可用返回 null。</p>
     */
    @Override
    public JsonObject resolveItemId(String zhName, JsonObject context) {
        try {
            if (offline() || "exchange".equals(mode())) {
                return null;
            }
            String url = buildUrl(settings.aiLegacyPath);
            if (url == null || !reserveBudget()) {
                return null;
            }
            JsonObject request = new JsonObject();
            request.addProperty("player", IDENTITY_PLAYER);
            request.addProperty("uuid", IDENTITY_UUID);
            request.addProperty("text", "你是 Minecraft 模组物品识别助手。玩家看到的物品中文名是「" + zhName
                    + "」，但服务器语言文件里没有这个中文名（可能是客户端汉化或资源包翻译）。\n"
                    + "服务器里已注册的物品 id 只能来自这些模组命名空间：" + contextText(context) + "\n"
                    + "请推断它最可能的物品注册 id 和英文关键词。只输出一个 JSON 对象："
                    + "{\"keywords\":[\"小写英文关键词，3~8 个\"],\"candidates\":[\"命名空间:物品路径，最多 5 个\"]}。"
                    + "不要解释、不要 markdown。如果完全无法判断，keywords 和 candidates 都输出空数组。");
            long started = System.currentTimeMillis();
            HttpResponse response = post(url, request.toString(), Math.max(timeoutMs(), 25_000));
            long elapsed = System.currentTimeMillis() - started;
            if (response.status != 200) {
                return null;
            }
            JsonObject root = parseObject(response.body);
            if (root == null || !JsonFile.bool(root, "ok", false)) {
                return null;
            }
            JsonObject data = AiJson.parseLooseObject(JsonFile.str(root, "reply", ""));
            if (data == null) {
                Log.warn("[AI] 物品 id 推断回复不是 JSON");
                return null;
            }
            Log.info("[AI] 物品 id 推断 " + zhName + " → " + data + " 用时=" + elapsed + "ms");
            return data;
        } catch (IOException | RuntimeException error) {
            Log.warn("[AI] 物品 id 推断失败：" + describe(error));
            return null;
        }
    }

    /**
     * 一次 AI 调用识别买卖意图 + 物品 id + 估价，避免"关键词直接执行"猜错物品。
     */
    @Override
    public JsonObject parseTradeIntent(String playerName, String text, JsonObject context) {
        try {
            if (offline() || "exchange".equals(mode())) {
                return null;
            }
            String url = buildUrl(settings.aiLegacyPath);
            if (url == null || !reserveBudget()) {
                return null;
            }
            JsonObject request = new JsonObject();
            request.addProperty("player", IDENTITY_PLAYER);
            request.addProperty("uuid", IDENTITY_UUID);
            request.addProperty("text", "你是 Minecraft 服务器 AIMCmarket 的市场先生。玩家在游戏里说：「" + text + "」\n"
                    + "当前数据（JSON）：" + contextText(context) + "\n"
                    + "请判断玩家意图：\n"
                    + "1) 想买物品：action=buy；想卖/收购（把背包里的东西卖给市场）：action=sell；想退货（退掉之前在本市场买的）：action=return。\n"
                    + "   quantity = 数量（buy 没说就 1；sell/return 没说就填 0 表示背包里全部），itemName = 玩家原话里的物品叫法；\n"
                    + "   candidates 填最可能对应的物品注册 id（命名空间:路径，最多 5 个，必须来自上面 namespaces 里列出的命名空间）；\n"
                    + "   如果玩家消息里直接写了形如 命名空间:路径 的物品 id，把该 id 放在 candidates 第一位，优先用它。\n"
                    + "   keywords 填 3~8 个小写英文关键词（用于在服务器注册表里匹配）；\n"
                    + "   priceCents 填该物品的合理单价（分，1 币=100 分，100~2000000）；如果目录里已有这个物品就填 0。\n"
                    + "2) 其他问题：action 填 none，其余字段留空/0。\n"
                    + "只输出一个 JSON 对象：{\"action\":\"buy|sell|none\",\"quantity\":1,\"itemName\":\"\","
                    + "\"candidates\":[],\"keywords\":[],\"priceCents\":0}。不要解释、不要 markdown。");
            long started = System.currentTimeMillis();
            HttpResponse response = post(url, request.toString(), Math.max(timeoutMs(), 25_000));
            long elapsed = System.currentTimeMillis() - started;
            if (response.status != 200) {
                return null;
            }
            JsonObject root = parseObject(response.body);
            if (root == null || !JsonFile.bool(root, "ok", false)) {
                return null;
            }
            JsonObject data = AiJson.parseLooseObject(JsonFile.str(root, "reply", ""));
            if (data == null) {
                Log.warn("[AI] 买卖意图回复不是 JSON");
                return null;
            }
            Log.info("[AI] 买卖意图 " + text + " → " + data + " 用时=" + elapsed + "ms");
            return data;
        } catch (IOException | RuntimeException error) {
            Log.warn("[AI] 买卖意图解析失败：" + describe(error));
            return null;
        }
    }

    @Override
    public boolean ping() {
        try {
            if (!settings.aiEnabled) {
                lastRoute = ROUTE_DISABLED;
                return false;
            }
            String mode = mode();
            if ("local".equals(mode)) {
                lastRoute = ROUTE_LOCAL;
                return false;
            }
            if ("legacy".equals(mode)) {
                // 明确不用 legacy 打 ping
                return false;
            }
            String url = buildUrl(settings.aiExchangePath);
            if (url == null) {
                lastRoute = ROUTE_ERROR;
                return false;
            }
            JsonObject request = new JsonObject();
            request.addProperty("task", "ping");
            request.addProperty("player", IDENTITY_PLAYER);
            request.addProperty("uuid", IDENTITY_UUID);
            request.addProperty("message", "ping");
            request.add("context", new JsonObject());
            recordUsage(); // ping 也是真实 HTTP，计入预算；但不因为预算拦它（/ex admin stats 要看健康）
            HttpResponse response = post(url, request.toString(), PING_TIMEOUT_MS);
            boolean ok = response.status == 200;
            JsonObject root = ok ? parseObject(response.body) : null;
            ok = ok && root != null && JsonFile.bool(root, "ok", false);
            lastRoute = ok ? ROUTE_EXCHANGE : ROUTE_ERROR;
            return ok;
        } catch (IOException | RuntimeException error) {
            lastRoute = ROUTE_ERROR;
            Log.warn("[AI] ping 失败：" + describe(error));
            return false;
        }
    }

    @Override
    public String lastRoute() {
        return lastRoute;
    }

    // ── 降级链 ──

    /** AI 关闭 / 只用本地：直接告诉调用方走本地。 */
    private boolean offline() {
        if (!settings.aiEnabled) {
            lastRoute = ROUTE_DISABLED;
            return true;
        }
        if ("local".equals(mode())) {
            lastRoute = ROUTE_LOCAL;
            return true;
        }
        return false;
    }

    /**
     * exchange → legacy 的降级逻辑。
     *
     * <p>auto：exchange 失败（404/disabled/超时/坏 JSON/ok:false/schema 不过）一律再试 legacy；
     * exchange / legacy：只走指定链路。预算超了直接给 {@link ROUTE_BUDGET}。</p>
     */
    private JsonObject runTask(String task, String message, JsonObject context) {
        try {
            String mode = mode();
            if ("auto".equals(mode) || "exchange".equals(mode)) {
                JsonObject data = callExchange(task, message, context);
                if (data != null) {
                    lastRoute = ROUTE_EXCHANGE;
                    return data;
                }
                if ("exchange".equals(mode)) {
                    lastRoute = ROUTE_ERROR;
                    return null;
                }
            }
            JsonObject legacyData = callLegacy(task, message, context);
            if (legacyData != null) {
                lastRoute = ROUTE_LEGACY;
                return legacyData;
            }
            lastRoute = ROUTE_ERROR;
            return null;
        } catch (BudgetExceededException budget) {
            lastRoute = ROUTE_BUDGET;
            Log.info("[AI] 本小时预算用完（task=" + task + "），改走本地兜底");
            return null;
        }
    }

    /** POST settings.aiEndpoint + settings.aiExchangePath，拿 data 过 schema。失败返回 null。 */
    private JsonObject callExchange(String task, String message, JsonObject context) {
        String url = buildUrl(settings.aiExchangePath);
        if (url == null) {
            Log.warn("[AI] exchange 地址配置不可用，task=" + task);
            return null;
        }
        JsonObject request = new JsonObject();
        request.addProperty("task", task);
        request.addProperty("player", IDENTITY_PLAYER);
        request.addProperty("uuid", IDENTITY_UUID);
        request.addProperty("message", message);
        request.add("context", context == null ? new JsonObject() : context);
        if (!reserveBudget()) {
            throw new BudgetExceededException();
        }
        long started = System.currentTimeMillis();
        HttpResponse response;
        try {
            response = post(url, request.toString(), timeoutMs());
        } catch (IOException error) {
            Log.warn("[AI] exchange 请求失败 task=" + task + "：" + describe(error));
            return null;
        }
        long elapsed = System.currentTimeMillis() - started;
        if (response.status == 404 || exchangeDisabled(response.body)) {
            Log.info("[AI] exchange 未启用（status=" + response.status + "，task=" + task + "），尝试 legacy");
            return null;
        }
        if (response.status != 200) {
            Log.warn("[AI] exchange 返回 status=" + response.status + " task=" + task);
            return null;
        }
        JsonObject root = parseObject(response.body);
        if (root == null) {
            Log.warn("[AI] exchange 返回不是合法 JSON task=" + task + " 长度=" + response.body.length());
            return null;
        }
        if (!JsonFile.bool(root, "ok", false)) {
            Log.info("[AI] exchange ok=false task=" + task + "：" + safeError(root));
            return null;
        }
        JsonElement dataElement = root.get("data");
        if (dataElement == null || dataElement.isJsonNull() || !dataElement.isJsonObject()) {
            Log.warn("[AI] exchange 返回缺少 data 对象 task=" + task);
            return null;
        }
        JsonObject validated = validate(task, dataElement.getAsJsonObject());
        if (validated == null) {
            Log.warn("[AI] exchange 的 data 没过 schema 校验 task=" + task);
            return null;
        }
        Log.info("[AI] exchange 成功 task=" + task + " 用时=" + elapsed + "ms 消息长度=" + message.length());
        return validated;
    }

    /** POST settings.aiEndpoint + settings.aiLegacyPath，用文本模板，再从 reply 里抠 JSON。失败返回 null。 */
    private JsonObject callLegacy(String task, String message, JsonObject context) {
        String url = buildUrl(settings.aiLegacyPath);
        if (url == null) {
            Log.warn("[AI] legacy 地址配置不可用，task=" + task);
            return null;
        }
        JsonObject request = new JsonObject();
        request.addProperty("player", IDENTITY_PLAYER);
        request.addProperty("uuid", IDENTITY_UUID);
        request.addProperty("text", legacyPrompt(task, message, context));
        if (!reserveBudget()) {
            throw new BudgetExceededException();
        }
        long started = System.currentTimeMillis();
        HttpResponse response;
        try {
            response = post(url, request.toString(), timeoutMs());
        } catch (IOException error) {
            Log.warn("[AI] legacy 请求失败 task=" + task + "：" + describe(error));
            return null;
        }
        long elapsed = System.currentTimeMillis() - started;
        if (response.status != 200) {
            Log.warn("[AI] legacy 返回 status=" + response.status + " task=" + task);
            return null;
        }
        JsonObject root = parseObject(response.body);
        if (root == null) {
            Log.warn("[AI] legacy 返回不是合法 JSON task=" + task + " 长度=" + response.body.length());
            return null;
        }
        if (!JsonFile.bool(root, "ok", false)) {
            Log.info("[AI] legacy ok=false task=" + task + "：" + safeError(root));
            return null;
        }
        String reply = JsonFile.str(root, "reply", "");
        if (reply.trim().isEmpty()) {
            Log.warn("[AI] legacy 回复为空 task=" + task);
            return null;
        }
        JsonObject loose = AiJson.parseLooseObject(reply);
        if (loose == null) {
            Log.warn("[AI] legacy 回复里没有 JSON task=" + task + " 回复长度=" + reply.length());
            return null;
        }
        JsonObject validated = validate(task, loose);
        if (validated == null) {
            Log.warn("[AI] legacy 回复没过 schema 校验 task=" + task + " 回复长度=" + reply.length());
            return null;
        }
        Log.info("[AI] legacy 成功 task=" + task + " 用时=" + elapsed + "ms 消息长度=" + message.length()
                + " 回复长度=" + reply.length());
        return validated;
    }

    // ── schema 校验分派 ──

    private JsonObject validate(String task, JsonObject data) {
        if (data == null) {
            return null;
        }
        switch (task) {
            case "parse":
                return AiJson.validateParse(data, market);
            case "news":
                return AiJson.validateNews(data);
            case "report":
                return AiJson.validateReport(data);
            case "prospectus":
                return AiJson.validateProspectus(data);
            case "government":
                return AiJson.validateGovernment(data);
            default:
                return null;
        }
    }

    // ── legacy 文本模板（system 风格的规则 + schema + 示例）──

    private static String legacyPrompt(String task, String message, JsonObject context) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append("你是 Minecraft 服务器 AIMCmarket 的后台数据生成器。只输出一个 JSON 对象，")
                .append("不要解释、不要 Markdown 代码块、不要任何多余文字。\n");
        builder.append(taskRules(task)).append('\n');
        if ("government".equals(task)) {
            builder.append("上下文里的 recentPolicies 和 cooldowns 必须一起看：和冷却冲突就不要出政策；")
                    .append("action 只能从给定枚举里选，params 只能用上面列出的键。\n");
        }
        builder.append("玩家指令：").append(message).append('\n');
        builder.append("上下文（JSON）：").append(contextText(context)).append('\n');
        builder.append("现在只输出 JSON：");
        return builder.toString();
    }

    private static String taskRules(String task) {
        switch (task) {
            case "parse":
                return "任务 parse：把玩家的话解析成订单参数。\n"
                        + "输出 JSON：{\"intent\":\"枚举\",\"items\":[{\"id\":\"minecraft:xxx\",\"count\":1}],"
                        + "\"symbol\":\"\",\"shares\":0,\"amountCents\":0,\"reply\":\"一句话\"}\n"
                        + "intent 只能取：buy/sell/price/balance/work/daily/pay/stock_buy/stock_sell/stock_info/"
                        + "fund_buy/fund_sell/fund_info/deposit/withdraw/bank_loan/company_create/ipo_apply/"
                        + "futures_long/futures_short/options_buy/options_sell/gov_status/help/unknown。\n"
                        + "物品 id 必须来自上下文的商品目录，形如 minecraft:iron_ingot；count 是 1-576 的整数；"
                        + "symbol 是股票/基金/期货代码（大写）；shares 是整数股数/手数；amountCents 是金额（分，"
                        + "1 币=100 分），非负整数。\n"
                        + "扩展字段（命令层会读，能确定就写，不确定就省略）：pay 带 player；company_create 带 "
                        + "companyName（2-16 字）和 sector（mining/farming/manufacturing/trading/service）；"
                        + "fund_sell 带 unitsMilli；futures_long/futures_short 带 lots 和 expiryDays（7/14/30）；"
                        + "options_buy/options_sell 带 optionType（call/put）、strikeCents、expiryDays、contracts。\n"
                        + "没把握就输出 {\"intent\":\"unknown\",\"reply\":\"没听懂\"}，绝对不要猜。\n"
                        + "示例：{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":3}],"
                        + "\"symbol\":\"\",\"shares\":0,\"amountCents\":0,\"reply\":\"买 3 个铁锭\"}";
            case "news":
                return "任务 news：生成一条市场新闻。\n"
                        + "输出 JSON：{\"headline\":\"标题\",\"body\":\"正文 1-3 句\","
                        + "\"effects\":[{\"target\":\"minecraft:xxx 或股票代码\",\"multiplier\":1.08,\"ticks\":6}]}\n"
                        + "headline/body 不能为空；effects 1-3 条，multiplier 在 0.85-1.20 之间，ticks 是 3-12 的整数。\n"
                        + "示例：{\"headline\":\"下界能源减产\",\"body\":\"下界能源宣布减产，市场担心供应收紧。\","
                        + "\"effects\":[{\"target\":\"minecraft:gold_ingot\",\"multiplier\":1.06,\"ticks\":6}]}";
            case "report":
                return "任务 report：给一家上市公司写一期财报。\n"
                        + "输出 JSON：{\"revenueCents\":123456,\"profitCents\":20000,\"outlook\":\"up\","
                        + "\"headline\":\"一句话\"}\n"
                        + "revenueCents/profitCents 是非负整数（分），outlook 只能是 up/flat/down，headline 不能为空。\n"
                        + "示例：{\"revenueCents\":1200000,\"profitCents\":180000,\"outlook\":\"up\","
                        + "\"headline\":\"方块重工订单饱满，利润超预期\"}";
            case "prospectus":
                return "任务 prospectus：写一份 IPO 招股书。\n"
                        + "输出 JSON：{\"title\":\"标题\",\"summary\":\"业务概述\",\"risk\":\"风险提示\","
                        + "\"useOfFunds\":\"募资用途\"}；四个字段都是字符串，title 不能为空。\n"
                        + "示例：{\"title\":\"铜矿工坊招股书\",\"summary\":\"主营矿石开采与粗加工。\","
                        + "\"risk\":\"矿石价格波动会影响利润。\",\"useOfFunds\":\"扩建矿场、购买设备。\"}";
            case "government":
                return "任务 government：你是 Minecraft 服务器的政府/央行，根据上下文里的宏观指标决定要不要出政策。\n"
                        + "输出 JSON：{\"action\":\"枚举\",\"params\":{},\"reason\":\"一句话\","
                        + "\"announcement\":\"给玩家看的公告，可为空\"}\n"
                        + "action 只能取：none/rate_cut/rate_hike/welfare/stimulus/austerity/subsidy/tariff/"
                        + "liquidity/min_wage；不需要出手就返回 action=none，且 params 必须是 {}。\n"
                        + "params 边界：rate_cut/rate_hike 只用 bp（25/50/75/100）；welfare amountCents 1-50000；"
                        + "stimulus amountCents>=1 + sectors（1-10 个字符串）+ days（1-30）；austerity ratio 0-0.02；"
                        + "subsidy/tariff target（minecraft:xxx）+ ratio（0-0.2）+ days（1-30）；"
                        + "liquidity ratio 0-1；min_wage amountCents 1000-5000。\n"
                        + "示例：{\"action\":\"rate_cut\",\"params\":{\"bp\":25},\"reason\":\"通胀回落、指数走弱\","
                        + "\"announcement\":\"央行降息 25bp。\"}\n"
                        + "示例（不调整）：{\"action\":\"none\",\"params\":{},\"reason\":\"指标平稳\",\"announcement\":\"\"}";
            default:
                return "任务 " + task + "：只输出 JSON。";
        }
    }

    private static String contextText(JsonObject context) {
        if (context == null) {
            return "{}";
        }
        String text = context.toString();
        if (text.length() <= MAX_CONTEXT_CHARS) {
            return text;
        }
        int end = MAX_CONTEXT_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…（上下文太长已截断）";
    }

    // ── 预算（滑动 1 小时窗口）──

    /** 预扣一次 HTTP 调用额度；超预算返回 false。 */
    private boolean reserveBudget() {
        int limit = settings.aiHourlyBudget;
        long now = System.currentTimeMillis();
        synchronized (budgetLog) {
            pruneBudgetLocked(now);
            if (limit <= 0 || budgetLog.size() >= limit) {
                return false;
            }
            budgetLog.addLast(now);
            return true;
        }
    }

    /** 只记一次真实 HTTP（ping 用），不做超预算拦截。 */
    private void recordUsage() {
        long now = System.currentTimeMillis();
        synchronized (budgetLog) {
            pruneBudgetLocked(now);
            if (budgetLog.size() >= 20_000) {
                budgetLog.pollFirst();
            }
            budgetLog.addLast(now);
        }
    }

    private void pruneBudgetLocked(long now) {
        long cutoff = now - BUDGET_WINDOW_MS;
        while (!budgetLog.isEmpty() && budgetLog.peekFirst() < cutoff) {
            budgetLog.pollFirst();
        }
    }

    // ── HTTP ──

    /** 只允许请求 settings.aiEndpoint 下的路径；配置不合法返回 null。 */
    private String buildUrl(String path) {
        String endpoint = settings.aiEndpoint == null ? "" : settings.aiEndpoint.trim();
        if (endpoint.isEmpty() || path == null || path.trim().isEmpty()) {
            return null;
        }
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (endpoint.isEmpty()) {
            return null;
        }
        String tail = path.trim();
        if (!tail.startsWith("/")) {
            tail = "/" + tail;
        }
        String url = endpoint + tail;
        try {
            URL parsed = new URL(url);
            String protocol = parsed.getProtocol() == null ? "" : parsed.getProtocol().toLowerCase(Locale.ROOT);
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                return null;
            }
            if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
                return null;
            }
            return url;
        } catch (IOException error) {
            return null;
        }
    }

    private int timeoutMs() {
        int seconds = settings.aiTimeoutSeconds;
        if (seconds < 1) {
            seconds = 1;
        } else if (seconds > 120) {
            seconds = 120;
        }
        return seconds * 1000;
    }

    /** 同步 POST JSON；连接/读取都设超时，响应按 UTF-8 读且有大小上限。 */
    private HttpResponse post(String url, String body, int timeoutMs) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false); // 只允许配置的 endpoint，不跟跳转
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AIMCmarket/0.1.0");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
                output.flush();
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = "";
            if (input != null) {
                try (InputStream closable = input) {
                    responseBody = readLimited(closable, MAX_RESPONSE_BYTES);
                }
            }
            return new HttpResponse(status, responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            int keep = Math.min(read, maxBytes - total);
            if (keep > 0) {
                buffer.write(chunk, 0, keep);
            }
            total += read;
            if (total >= maxBytes) {
                break;
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // 坏 JSON 交给降级
        }
        return null;
    }

    private static boolean exchangeDisabled(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("exchange disabled");
    }

    private static String safeError(JsonObject root) {
        String error = JsonFile.str(root, "error", "");
        return truncate(error, 80);
    }

    // ── 杂项 ──

    private String mode() {
        String value = settings.aiMode == null ? "" : settings.aiMode.trim().toLowerCase(Locale.ROOT);
        if ("exchange".equals(value) || "legacy".equals(value) || "local".equals(value)) {
            return value;
        }
        return "auto";
    }

    private static String playerMessage(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return truncate(value, MAX_MESSAGE_CHARS);
    }

    private JsonObject failed(String task, RuntimeException error) {
        lastRoute = ROUTE_ERROR;
        Log.warn("[AI] " + task + " 调用异常：" + describe(error));
        return null;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + " " + truncate(message, 80);
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

    private static final class HttpResponse {
        final int status;
        final String body;

        HttpResponse(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** 预算用尽的内部信号，只在 runTask 里转成 lastRoute=budget，不会漏给调用方。 */
    private static final class BudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceededException() {
            super("AI 本小时预算超限", null, false, false);
        }
    }
}
