package com.mogutofu.aimcmarket.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 政府 / 央行引擎：宏观指标、压力分体检、AI 政府请求构造、本地规则兜底、政策落地校验与广播。
 *
 * <p>这个类 <b>不</b> 发 AI 请求（AI 调用由上层异步调度），也 <b>不</b> import 任何 Minecraft 类：
 * 只提供"要不要问 AI"的判断、请求 context 的构造、以及把 AI/本地规则给出的决策
 * 按 {@link GovernmentStore} 的边界校验后落地。所有金额单位是分（long），比率是 double。</p>
 *
 * <p>时间口径：政策冷却用现实毫秒（{@code nowMs}），持续型政策（补贴/关税/刺激/流动性）
 * 和利率方向冷却用交易日（{@code tradingDay}）。</p>
 */
public final class GovernmentEngine {

    /** 压力分达到这个值就直接问 AI（不等检查间隔）。 */
    public static final int STRESS_AI_THRESHOLD = 60;
    /** 通胀目标 3%。 */
    public static final double TARGET_INFLATION = 0.03;
    /** 通胀偏离 10 个百分点就吃满 25 分。 */
    public static final double MAX_INFLATION_DEVIATION = 0.10;
    /** 大盘 7 日跌幅 15% 就吃满 25 分。 */
    public static final double MAX_INDEX_DROP = 0.15;
    /** 政府预算低于 5000 币（500000 分）开始计入财政风险。 */
    public static final long BUDGET_LOW_CENTS = 500_000L;
    /** 7 日成交额低于 1000 币（100000 分）开始计入流动性风险。 */
    public static final long VOLUME_FLOOR_CENTS = 100_000L;
    /** 福利每人上限 500 币。 */
    public static final long WELFARE_PER_PLAYER_MAX_CENTS = 50_000L;
    /** 最低工资 10-50 币。 */
    public static final long MIN_WAGE_MIN_CENTS = 1_000L;
    public static final long MIN_WAGE_MAX_CENTS = 5_000L;
    /** 政策利率允许区间 0-10%。 */
    public static final double MIN_POLICY_RATE = 0.0;
    public static final double MAX_POLICY_RATE = 0.10;
    /** 单次利率调整允许的基点。 */
    public static final int[] RATE_BP_STEPS = {25, 50, 75, 100};
    /** 利率同一方向冷却：6 个交易日。 */
    public static final long RATE_COOLDOWN_DAYS = 6L;
    /** 流动性政策持续 30 个交易日。 */
    public static final long LIQUIDITY_DAYS = 30L;

    private GovernmentEngine() {
    }

    // ── 指标 ────────────────────────────────────────────────────────────

    /**
     * 更新宏观指标（每个 market tick 至少调一次；玩家市场操作时也会调）。
     *
     * <p>先做一次当日 CPI 快照（同一天重复快照会覆盖，没问题），这样
     * {@link MarketStore#inflation7d(long)} 当天就有数据。然后计算压力分并写进
     * {@link GovernmentStore#indicators}。</p>
     *
     * @param onlineAccounts 在线玩家；可以为 null（会退化为全量账户中位数）
     */
    public static void updateIndicators(AimcContext ctx, Collection<Account> onlineAccounts) {
        if (ctx == null) {
            return;
        }
        long day = ctx.tradingDay();
        // 每个 market tick 至少一次 CPI 快照；同日覆盖。
        ctx.market.snapshotCpi(day);

        long moneyCents = ctx.ledger.circulationCents();
        double inflation7d = ctx.market.inflation7d(day);
        double indexChange7d = ctx.stocks.indexChange7d(day);
        long medianWealthCents = ctx.accounts.medianBalanceCents(
                onlineAccounts == null ? Collections.emptyList() : onlineAccounts);
        double employment = ctx.employmentRate();
        long volume7dCents = ctx.market.volume7Days(day);
        int stress = stressScore(ctx);

        ctx.government.updateIndicators(moneyCents, inflation7d, indexChange7d, medianWealthCents,
                employment, volume7dCents, ctx.nowMs(), stress);
    }

    /**
     * 压力分 0-100（加权）：
     * <ul>
     *   <li>通胀偏离 3% 目标：|inf-0.03| 封顶 0.10 → 25 分；</li>
     *   <li>大盘 7 日跌幅：封顶 15% → 25 分；</li>
     *   <li>预算赤字 / 预算过低（低于 5000 币）→ 15 分；</li>
     *   <li>财富分化：中位财富 &lt; 人均/3 → 10 分；</li>
     *   <li>失业率（1-employment）→ 15 分；</li>
     *   <li>流动性：7 日成交额低于 1000 币 → 10 分（线性，越低越接近满分）。</li>
     * </ul>
     */
    public static int stressScore(AimcContext ctx) {
        if (ctx == null) {
            return 0;
        }
        long day = ctx.tradingDay();
        double inflation = ctx.market.inflation7d(day);
        double indexChange = ctx.stocks.indexChange7d(day);
        long budget = ctx.government.budgetCents();
        double employment = ctx.employmentRate();
        long volume = ctx.market.volume7Days(day);

        double score = 0.0;

        // 1) 通胀偏离 3% 目标：25 分
        double deviation = Math.abs(inflation - TARGET_INFLATION);
        score += 25.0 * Math.min(1.0, deviation / MAX_INFLATION_DEVIATION);

        // 2) 大盘 7 日跌幅：25 分（只有跌才计）
        double drop = Math.max(0.0, -indexChange);
        score += 25.0 * Math.min(1.0, drop / MAX_INDEX_DROP);

        // 3) 预算赤字 / 过低：15 分
        score += 15.0 * budgetRisk(budget);

        // 4) 财富分化：10 分（简化成"中位 < 人均/3"）
        score += 10.0 * wealthConcentrationRisk(ctx);

        // 5) 失业：15 分
        double unemployment = 1.0 - Cents.clampDouble(employment, 0.0, 1.0);
        score += 15.0 * unemployment;

        // 6) 流动性：10 分
        if (VOLUME_FLOOR_CENTS > 0L) {
            double liquidityRisk = volume >= VOLUME_FLOOR_CENTS
                    ? 0.0
                    : 1.0 - (double) volume / (double) VOLUME_FLOOR_CENTS;
            score += 10.0 * Cents.clampDouble(liquidityRisk, 0.0, 1.0);
        }

        return (int) Cents.clamp(Math.round(score), 0L, 100L);
    }

    private static double budgetRisk(long budgetCents) {
        if (budgetCents <= 0L) {
            return 1.0;
        }
        if (budgetCents >= BUDGET_LOW_CENTS) {
            return 0.0;
        }
        return 1.0 - (double) budgetCents / (double) BUDGET_LOW_CENTS;
    }

    private static double wealthConcentrationRisk(AimcContext ctx) {
        Collection<Account> all = ctx.accounts.all();
        if (all.isEmpty()) {
            return 0.0;
        }
        long sum = 0L;
        for (Account account : all) {
            sum = Math.addExact(sum, account.balanceCents());
        }
        double average = (double) sum / (double) all.size();
        if (average <= 0.0) {
            return 0.0;
        }
        long median = ctx.accounts.medianBalanceCents(all);
        return (double) median < average / 3.0 ? 1.0 : 0.0;
    }

    // ── AI 调度判断 ─────────────────────────────────────────────────────

    /**
     * 要不要问 AI 政府：AI 政府开关关掉 → 否；压力分 ≥60 → 是；
     * 距上次检查 ≥ {@code aiGovernmentCheckMinutes} 分钟 → 是。
     *
     * <p>真正发请求前上层要调 {@link #markChecked(AimcContext, long)}。</p>
     */
    public static boolean shouldAskAi(AimcContext ctx, long nowMs) {
        if (ctx == null || !ctx.settings.aiGovernmentEnabled) {
            return false;
        }
        if (stressScore(ctx) >= STRESS_AI_THRESHOLD) {
            return true;
        }
        long intervalMs = Math.max(1, ctx.settings.aiGovernmentCheckMinutes) * 60_000L;
        long last = ctx.government.lastAiCheckMs;
        return last <= 0L || nowMs - last >= intervalMs;
    }

    /** 记录"已经问过 AI 了"（真正发请求前调用）。 */
    public static void markChecked(AimcContext ctx, long nowMs) {
        if (ctx == null) {
            return;
        }
        ctx.government.lastAiCheckMs = nowMs;
    }

    // ── AI 请求 context ─────────────────────────────────────────────────

    /**
     * 构造 {@code task=government} 请求里的 {@code context} 部分：
     * indicators / rates / tax / recentPolicies / cooldowns（数值单位分，比率是小数）。
     *
     * <p>完整请求形如 {@code {"task":"government","message":"review","context":{...}}}，
     * 包外壳由上层 AI 网关负责。</p>
     */
    public static JsonObject buildRequest(AimcContext ctx) {
        JsonObject context = new JsonObject();
        if (ctx == null) {
            return context;
        }
        GovernmentStore gov = ctx.government;
        GovernmentStore.Indicators ind = gov.indicators;

        JsonObject indicators = new JsonObject();
        indicators.addProperty("moneyCents", ind.moneyCents);
        indicators.addProperty("inflation7d", ind.inflation7d);
        indicators.addProperty("indexChange7d", ind.indexChange7d);
        indicators.addProperty("medianWealthCents", ind.medianWealthCents);
        indicators.addProperty("budgetCents", gov.budgetCents());
        indicators.addProperty("employment", ind.employment);
        indicators.addProperty("volume7dCents", ind.volume7dCents);
        context.add("indicators", indicators);

        MarketSettings settings = ctx.settings;
        double policy = gov.policyRate;
        double depositCurrent = Math.max(settings.currentRateFloor, policy + settings.currentRateSpread);
        double loan = policy + settings.loanRateSpread;
        JsonObject rates = new JsonObject();
        rates.addProperty("policy", policy);
        rates.addProperty("depositCurrent", depositCurrent);
        rates.addProperty("loan", loan);
        context.add("rates", rates);

        JsonObject tax = new JsonObject();
        tax.addProperty("trade", gov.effectiveTradeTax());
        tax.addProperty("company", gov.effectiveCompanyTax());
        context.add("tax", tax);

        JsonArray recentPolicies = new JsonArray();
        for (GovernmentStore.Policy policyRecord : gov.recentPolicies(5)) {
            JsonObject one = new JsonObject();
            one.addProperty("at", policyRecord.atMs);
            one.addProperty("id", policyRecord.id);
            one.addProperty("action", policyRecord.action);
            one.add("params", policyRecord.params == null ? new JsonObject() : policyRecord.params);
            recentPolicies.add(one);
        }
        context.add("recentPolicies", recentPolicies);

        JsonObject cooldowns = new JsonObject();
        long now = ctx.nowMs();
        long day = ctx.tradingDay();
        long secondsPerDay = Math.max(1L, ctx.clock.dayMillis() / 1_000L);
        long rateDays = Math.max(gov.rateCooldownRemainingDays(1, day),
                gov.rateCooldownRemainingDays(-1, day));
        cooldowns.addProperty("rate", rateDays * secondsPerDay);
        for (String action : new String[]{
                "welfare", "stimulus", "austerity", "subsidy", "tariff", "liquidity", "min_wage"}) {
            cooldowns.addProperty(action, gov.cooldownRemainingMs(action, now) / 1_000L);
        }
        context.add("cooldowns", cooldowns);
        return context;
    }

    // ── 本地规则兜底 ────────────────────────────────────────────────────

    /**
     * AI 不可用时按任务书规则表出政策（和 AI 返回同形状的决策 JSON）：
     * 通胀 &gt;8% 加息 50bp；&lt;0% 降息 50bp；指数 7 日跌 &gt;15% 流动性支持；
     * 预算 &gt;5000 币且指数跌 → 刺激；就业 &lt;0.4 → 福利；都不满足 → {@code {"action":"none"}}。
     *
     * <p>规则只按宏观条件判断；冷却和预算由 {@link #applyDecision} 在落地时强制校验
     * （预算不足会记录 rejected 政策）。</p>
     */
    public static JsonObject localFallback(AimcContext ctx) {
        if (ctx == null) {
            return noneDecision("没有市场上下文");
        }
        GovernmentStore gov = ctx.government;
        long day = ctx.tradingDay();
        double inflation = ctx.market.inflation7d(day);
        double indexChange = ctx.stocks.indexChange7d(day);
        double employment = ctx.employmentRate();
        long budget = gov.budgetCents();

        if (inflation > 0.08) {
            JsonObject params = new JsonObject();
            params.addProperty("bp", 50);
            return decisionObject("rate_hike", params, String.format(Locale.ROOT,
                    "通胀 %.2f%% 明显高于 3%% 目标，本地规则加息 50 个基点", inflation * 100.0));
        }
        if (inflation < 0.0) {
            JsonObject params = new JsonObject();
            params.addProperty("bp", 50);
            return decisionObject("rate_cut", params, String.format(Locale.ROOT,
                    "通胀 %.2f%% 为负，本地规则降息 50 个基点", inflation * 100.0));
        }
        if (indexChange < -0.15) {
            JsonObject params = new JsonObject();
            params.addProperty("ratio", 0.5);
            return decisionObject("liquidity", params, String.format(Locale.ROOT,
                    "大盘 7 日下跌 %.2f%%，本地规则提供流动性支持", -indexChange * 100.0));
        }
        if (budget > BUDGET_LOW_CENTS && indexChange < 0.0) {
            JsonObject params = new JsonObject();
            params.addProperty("amountCents", 100_000L); // 1000 币
            JsonArray sectors = new JsonArray();
            for (String code : CompanyStore.SECTORS.keySet()) {
                sectors.add(code);
            }
            params.add("sectors", sectors);
            params.addProperty("days", 3);
            return decisionObject("stimulus", params, String.format(Locale.ROOT,
                    "大盘 7 日下跌 %.2f%% 且财政有结余，本地规则刺激经济", -indexChange * 100.0));
        }
        if (employment < 0.4) {
            JsonObject params = new JsonObject();
            params.addProperty("amountCents", 10_000L); // 每人 100 币
            return decisionObject("welfare", params, String.format(Locale.ROOT,
                    "就业率 %.1f%% 低于 40%%，本地规则发放福利", employment * 100.0));
        }
        return noneDecision("宏观指标平稳，本轮不出政策");
    }

    private static JsonObject decisionObject(String action, JsonObject params, String reason) {
        JsonObject decision = new JsonObject();
        decision.addProperty("action", action);
        decision.add("params", params);
        decision.addProperty("reason", reason);
        decision.addProperty("announcement", "");
        return decision;
    }

    private static JsonObject noneDecision(String reason) {
        return decisionObject("none", new JsonObject(), reason);
    }

    // ── 政策落地 ────────────────────────────────────────────────────────

    /**
     * 校验并落地一条政府决策。
     *
     * <p>AI 给的参数一律不信：action 必须在 {@link GovernmentStore#ACTIONS} 里，
     * bp/金额/比率/天数/行业/商品 id 全部按独立边界校验，越界直接拒绝。</p>
     *
     * <p>{@code none} 只更新 lastAiDecisionMs，不广播不写新闻；
     * 预算不足会写一条带 {@code rejected=insufficient_budget} 的政策记录但不广播。</p>
     *
     * @param decision       决策 JSON（AI 返回或 {@link #localFallback(AimcContext)} 产物）
     * @param fromAi         true = AI 决策，false = 本地规则（只影响新闻里的来源标注）
     * @param onlineAccounts 当前在线玩家（福利发放对象）；可以为 null
     * @return 成功时 message 给执行者看、broadcast 给全服（不带 {@code [政府] } 前缀）、
     *         newsHeadline 是已写进 news 的标题
     */
    public static ActionResult applyDecision(AimcContext ctx, JsonObject decision, boolean fromAi,
                                             Collection<Account> onlineAccounts) {
        if (ctx == null || decision == null) {
            return ActionResult.fail("政府：决策为空");
        }
        long now = ctx.nowMs();
        long day = ctx.tradingDay();
        String action = JsonFile.str(decision, "action", "").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty() || !isAction(action)) {
            return ActionResult.fail("政府：未知政策 " + (action.isEmpty() ? "(空)" : action));
        }
        // 收到有效决策就记时间（none 也记，用于 /ex admin stats 和冷却判断）
        ctx.government.lastAiDecisionMs = now;
        if ("none".equals(action)) {
            return ActionResult.ok("");
        }

        JsonObject params = JsonFile.object(decision, "params");
        String reason = JsonFile.str(decision, "reason", "").trim();
        String announcement = JsonFile.str(decision, "announcement", "").trim();
        try {
            switch (action) {
                case "rate_cut":
                    return applyRateChange(ctx, params, reason, announcement, -1, fromAi, now, day);
                case "rate_hike":
                    return applyRateChange(ctx, params, reason, announcement, 1, fromAi, now, day);
                case "welfare":
                    return applyWelfare(ctx, params, reason, announcement, onlineAccounts, fromAi, now, day);
                case "stimulus":
                    return applyStimulus(ctx, params, reason, announcement, fromAi, now, day);
                case "austerity":
                    return applyAusterity(ctx, params, reason, announcement, fromAi, now, day);
                case "subsidy":
                    return applyCommodityPolicy(ctx, params, reason, announcement, "subsidy", fromAi, now, day);
                case "tariff":
                    return applyCommodityPolicy(ctx, params, reason, announcement, "tariff", fromAi, now, day);
                case "liquidity":
                    return applyLiquidity(ctx, params, reason, announcement, fromAi, now, day);
                case "min_wage":
                    return applyMinWage(ctx, params, reason, announcement, fromAi, now, day);
                default:
                    return ActionResult.fail("政府：未知政策 " + action);
            }
        } catch (RuntimeException error) {
            Log.warn("政府政策落地失败：" + action, error);
            return ActionResult.fail("政府：政策执行失败（" + shortMessage(error) + "）");
        }
    }

    /** 兼容旧契约（没有在线玩家列表）：福利只发给"余额低于全量中位数"的兜底列表。 */
    public static ActionResult applyDecision(AimcContext ctx, JsonObject decision, boolean fromAi) {
        return applyDecision(ctx, decision, fromAi, null);
    }

    // ── 各政策 ──────────────────────────────────────────────────────────

    private static ActionResult applyRateChange(AimcContext ctx, JsonObject params, String reason,
                                                String announcement, int direction, boolean fromAi,
                                                long now, long day) {
        int bp = JsonFile.intVal(params, "bp", 0);
        if (!isAllowedBp(bp)) {
            return ActionResult.fail("政府：利率调整幅度只能是 25/50/75/100 个基点");
        }
        GovernmentStore gov = ctx.government;
        if (!gov.canChangeRate(direction, day)) {
            long wait = gov.rateCooldownRemainingDays(direction, day);
            return ActionResult.fail("政府：同一方向利率调整冷却中（还要 " + wait + " 个交易日）");
        }
        double current = gov.policyRate;
        double delta = direction * (bp / 10_000.0);
        double target = Cents.clampDouble(current + delta, MIN_POLICY_RATE, MAX_POLICY_RATE);
        if (Math.abs(target - current) < 1.0e-9) {
            return ActionResult.fail("政府：政策利率已到边界（" + Cents.formatPercent(current) + "）");
        }
        gov.setPolicyRate(target);
        gov.applyRateChange(direction, day);

        String action = direction > 0 ? "rate_hike" : "rate_cut";
        String verb = direction > 0 ? "加息" : "降息";
        String tail = direction > 0 ? "升到" : "降到";
        String broadcast = "央行" + verb + " " + bp + " 个基点，政策利率" + tail + " "
                + Cents.formatPercent(target);
        String message = verb + " " + bp + " 个基点，政策利率 "
                + Cents.formatPercent(current) + " → " + Cents.formatPercent(target);
        String headline = "央行" + verb + " " + bp + " 个基点，政策利率" + tail + " "
                + Cents.formatPercent(target);
        String body = "央行宣布" + verb + " " + bp + " 个基点，政策利率由 "
                + Cents.formatPercent(current) + " 调整为 " + Cents.formatPercent(target) + "。"
                + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("bp", bp);
        normalized.addProperty("policyRate", target);
        return finishSuccess(ctx, action, normalized, reason, announcement, headline, body,
                broadcast, message, now, day, -1L, fromAi);
    }

    private static ActionResult applyWelfare(AimcContext ctx, JsonObject params, String reason,
                                             String announcement, Collection<Account> onlineAccounts,
                                             boolean fromAi, long now, long day) {
        long perPlayerCents = JsonFile.lng(params, "amountCents", 0L);
        if (perPlayerCents <= 0L) {
            return ActionResult.fail("政府：福利金额必须为正（单位分）");
        }
        if (perPlayerCents > WELFARE_PER_PLAYER_MAX_CENTS) {
            return ActionResult.fail("政府：每人福利不能超过 500 币");
        }
        GovernmentStore gov = ctx.government;
        if (gov.isOnCooldown("welfare", now)) {
            return ActionResult.fail("政府：福利政策冷却中（还要 "
                    + cooldownMinutes(gov, "welfare", now) + " 分钟）");
        }
        if (!gov.canSpend(perPlayerCents)) {
            return rejectForBudget(ctx, "welfare", params, reason, now, day);
        }
        List<Account> online = onlineAccounts == null
                ? Collections.emptyList() : new ArrayList<>(onlineAccounts);
        if (online.isEmpty()) {
            return ActionResult.fail("政府：没有在线玩家，福利无法发放");
        }
        long median = ctx.accounts.medianBalanceCents(online);
        List<Account> eligible = new ArrayList<>();
        for (Account account : online) {
            if (account != null && account.balanceCents() < median) {
                eligible.add(account);
            }
        }
        if (eligible.isEmpty()) {
            return ActionResult.fail("政府：没有余额低于中位数的在线玩家");
        }
        long totalCents;
        try {
            totalCents = Math.multiplyExact(perPlayerCents, (long) eligible.size());
        } catch (ArithmeticException overflow) {
            return ActionResult.fail("政府：福利总额溢出");
        }
        if (!gov.canSpend(totalCents)) {
            return rejectForBudget(ctx, "welfare", params, reason, now, day);
        }
        int count = 0;
        long paidCents = 0L;
        for (Account account : eligible) {
            gov.spend(account, perPlayerCents, "gov.welfare", "政府福利发放");
            account.recordIncome(perPlayerCents);
            paidCents = Math.addExact(paidCents, perPlayerCents);
            count++;
        }

        String headline = "政府发放福利：" + count + " 名玩家每人获得 "
                + Cents.format(perPlayerCents) + " 币";
        String message = "已向 " + count + " 名玩家发放福利，共 " + Cents.format(paidCents) + " 币";
        String broadcast = "政府向 " + count + " 名玩家发放福利，每人 "
                + Cents.format(perPlayerCents) + " 币，总额 " + Cents.format(paidCents) + " 币";
        String body = "政府动用财政预算，向余额低于中位数的 " + count + " 名在线玩家每人发放 "
                + Cents.format(perPlayerCents) + " 币，总额 " + Cents.format(paidCents) + " 币。"
                + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("amountCents", perPlayerCents);
        normalized.addProperty("players", count);
        normalized.addProperty("paidCents", paidCents);
        return finishSuccess(ctx, "welfare", normalized, reason, announcement, headline, body,
                broadcast, message, now, day, -1L, fromAi);
    }

    private static ActionResult applyStimulus(AimcContext ctx, JsonObject params, String reason,
                                              String announcement, boolean fromAi, long now, long day) {
        long amountCents = JsonFile.lng(params, "amountCents", 0L);
        if (amountCents <= 0L) {
            return ActionResult.fail("政府：刺激金额必须为正（单位分）");
        }
        int days = JsonFile.intVal(params, "days", 0);
        if (days < 1 || days > 30) {
            return ActionResult.fail("政府：刺激政策天数要在 1-30 交易日之间");
        }
        JsonArray sectorArray = JsonFile.array(params, "sectors");
        if (sectorArray.size() == 0) {
            return ActionResult.fail("政府：刺激政策必须指定行业（采矿/农业/制造/贸易/服务）");
        }
        Set<String> sectorCodes = new LinkedHashSet<>();
        for (JsonElement element : sectorArray) {
            String raw;
            try {
                raw = element.getAsString().trim();
            } catch (RuntimeException error) {
                return ActionResult.fail("政府：行业参数不合法");
            }
            if (raw.isEmpty()) {
                continue;
            }
            String code = resolveSector(raw);
            if (code == null) {
                return ActionResult.fail("政府：未知行业 " + raw);
            }
            sectorCodes.add(code);
        }
        if (sectorCodes.isEmpty()) {
            return ActionResult.fail("政府：刺激政策必须指定行业（采矿/农业/制造/贸易/服务）");
        }
        GovernmentStore gov = ctx.government;
        if (gov.isOnCooldown("stimulus", now)) {
            return ActionResult.fail("政府：刺激政策冷却中（还要 "
                    + cooldownMinutes(gov, "stimulus", now) + " 分钟）");
        }
        if (!gov.canSpend(amountCents)) {
            return rejectForBudget(ctx, "stimulus", params, reason, now, day);
        }
        // 政府支出流向做市商账户，代表财政资金进入市场
        gov.spend(ctx.market.makerAccount(), amountCents, "gov.stimulus", "政府刺激支出");

        List<String> names = new ArrayList<>();
        JsonArray normalizedSectors = new JsonArray();
        for (String code : sectorCodes) {
            normalizedSectors.add(code);
            names.add(CompanyStore.SECTORS.getOrDefault(code, code));
        }
        String sectorNames = String.join("、", names);
        String headline = "政府推出 " + Cents.format(amountCents) + " 币刺激计划，扶持" + sectorNames;
        String message = "刺激政策已落地：投放 " + Cents.format(amountCents) + " 币，扶持"
                + sectorNames + "，持续 " + days + " 交易日";
        String broadcast = "政府投入 " + Cents.format(amountCents) + " 币刺激经济，重点扶持"
                + sectorNames + "，持续 " + days + " 交易日";
        String body = "政府向市场投放 " + Cents.format(amountCents) + " 币财政资金，用于扶持"
                + sectorNames + "行业，政策持续 " + days + " 个交易日。" + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("amountCents", amountCents);
        normalized.add("sectors", normalizedSectors);
        normalized.addProperty("days", days);
        normalized.addProperty("ratio", 0.05);
        return finishSuccess(ctx, "stimulus", normalized, reason, announcement, headline, body,
                broadcast, message, now, day, day + days, fromAi);
    }

    private static ActionResult applyAusterity(AimcContext ctx, JsonObject params, String reason,
                                               String announcement, boolean fromAi, long now, long day) {
        double ratio = JsonFile.dbl(params, "ratio", 0.0);
        if (!(ratio > 0.0) || ratio > 0.02) {
            return ActionResult.fail("政府：交易税上调比例要在 (0, 2%] 之间");
        }
        GovernmentStore gov = ctx.government;
        if (gov.isOnCooldown("austerity", now)) {
            return ActionResult.fail("政府：紧缩政策冷却中（还要 "
                    + cooldownMinutes(gov, "austerity", now) + " 分钟）");
        }
        double current = gov.effectiveTradeTax();
        double target = Math.min(0.05, current + ratio);
        if (target <= current + 1.0e-9) {
            return ActionResult.fail("政府：交易税已达上限 5%");
        }
        gov.setTradeTax(target);

        String increase = Cents.formatPercent(target - current);
        String headline = "政府上调交易税至 " + Cents.formatPercent(target);
        String message = "交易税上调 " + increase + "，当前 "
                + Cents.formatPercent(target);
        String broadcast = "政府上调交易税 " + increase + "，当前交易税 "
                + Cents.formatPercent(target);
        String body = "为补充财政，政府将交易税由 " + Cents.formatPercent(current) + " 上调至 "
                + Cents.formatPercent(target) + "。" + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("ratio", ratio);
        normalized.addProperty("tradeTax", target);
        return finishSuccess(ctx, "austerity", normalized, reason, announcement, headline, body,
                broadcast, message, now, day, -1L, fromAi);
    }

    private static ActionResult applyCommodityPolicy(AimcContext ctx, JsonObject params, String reason,
                                                     String announcement, String action, boolean fromAi,
                                                     long now, long day) {
        boolean subsidy = "subsidy".equals(action);
        String targetRaw = JsonFile.str(params, "target", "").trim();
        MarketStore.CatalogItem item = ctx.market.item(targetRaw);
        if (item == null) {
            return ActionResult.fail("政府：未知商品 " + (targetRaw.isEmpty() ? "(空)" : targetRaw));
        }
        double ratio = JsonFile.dbl(params, "ratio", 0.0);
        if (!(ratio > 0.0) || ratio > 0.20) {
            return ActionResult.fail("政府：" + (subsidy ? "补贴" : "关税") + "比例要在 (0, 20%] 之间");
        }
        int days = JsonFile.intVal(params, "days", 0);
        if (days < 1 || days > 30) {
            return ActionResult.fail("政府：" + (subsidy ? "补贴" : "关税")
                    + "天数要在 1-30 交易日之间");
        }
        GovernmentStore gov = ctx.government;
        if (gov.isOnCooldown(action, now)) {
            return ActionResult.fail("政府：" + (subsidy ? "补贴" : "关税") + "政策冷却中（还要 "
                    + cooldownMinutes(gov, action, now) + " 分钟）");
        }
        String name = item.nameZh == null || item.nameZh.isBlank() ? item.id : item.nameZh;
        String percent = Cents.formatPercent(ratio);
        String headline = subsidy
                ? "政府补贴" + name + "：" + percent + " 降价，持续 " + days + " 交易日"
                : "政府加征" + name + "关税 " + percent + "，持续 " + days + " 交易日";
        String message = (subsidy ? "对" + name + "实施 " : "对" + name + "加征 ") + percent
                + (subsidy ? " 价格补贴" : " 关税") + "，持续 " + days + " 交易日";
        String broadcast = (subsidy ? "政府对" + name + "实施 " : "政府对" + name + "加征 ") + percent
                + (subsidy ? " 价格补贴" : " 关税") + "，持续 " + days + " 交易日";
        String body = subsidy
                ? "政府宣布对" + name + "实施 " + percent + " 的价格补贴，持续 " + days
                + " 个交易日，期间买入价格下调。" + reasonSentence(reason)
                : "政府宣布对" + name + "加征 " + percent + " 的关税，持续 " + days
                + " 个交易日，期间买入价格上调。" + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("target", item.id);
        normalized.addProperty("ratio", ratio);
        normalized.addProperty("days", days);
        return finishSuccess(ctx, action, normalized, reason, announcement, headline, body,
                broadcast, message, now, day, day + days, fromAi);
    }

    private static ActionResult applyLiquidity(AimcContext ctx, JsonObject params, String reason,
                                               String announcement, boolean fromAi, long now, long day) {
        double ratio = JsonFile.dbl(params, "ratio", 0.0);
        if (!(ratio > 0.0) || ratio > 1.0) {
            return ActionResult.fail("政府：流动性支持比例要在 (0, 100%] 之间");
        }
        GovernmentStore gov = ctx.government;
        if (gov.isOnCooldown("liquidity", now)) {
            return ActionResult.fail("政府：流动性政策冷却中（还要 "
                    + cooldownMinutes(gov, "liquidity", now) + " 分钟）");
        }
        String percent = Cents.formatPercent(ratio);
        String headline = "央行注入流动性：做市深度提升 " + percent;
        String message = "流动性支持已生效：做市深度提升 " + percent + "，持续 "
                + LIQUIDITY_DAYS + " 交易日";
        String broadcast = "央行提供流动性支持，做市深度提升 " + percent + "，持续 "
                + LIQUIDITY_DAYS + " 交易日";
        String body = "央行宣布为做市商提供流动性支持，做市深度提升 " + percent
                + "，以降低市场冲击，政策持续 " + LIQUIDITY_DAYS + " 个交易日。"
                + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("ratio", ratio);
        return finishSuccess(ctx, "liquidity", normalized, reason, announcement, headline, body,
                broadcast, message, now, day, day + LIQUIDITY_DAYS, fromAi);
    }

    private static ActionResult applyMinWage(AimcContext ctx, JsonObject params, String reason,
                                             String announcement, boolean fromAi, long now, long day) {
        long amountCents = JsonFile.lng(params, "amountCents", 0L);
        if (amountCents < MIN_WAGE_MIN_CENTS || amountCents > MIN_WAGE_MAX_CENTS) {
            return ActionResult.fail("政府：最低工资要在 10-50 币之间");
        }
        GovernmentStore gov = ctx.government;
        if (gov.isOnCooldown("min_wage", now)) {
            return ActionResult.fail("政府：最低工资政策冷却中（还要 "
                    + cooldownMinutes(gov, "min_wage", now) + " 分钟）");
        }
        String amount = Cents.format(amountCents);
        String headline = "政府上调最低日薪至 " + amount + " 币";
        String message = "最低日薪已调整为 " + amount + " 币";
        String broadcast = "政府将最低日薪上调至 " + amount + " 币";
        String body = "政府将公司员工日薪下限上调至 " + amount + " 币，同时员工产出随之上调。"
                + reasonSentence(reason);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("amountCents", amountCents);
        return finishSuccess(ctx, "min_wage", normalized, reason, announcement, headline, body,
                broadcast, message, now, day, -1L, fromAi);
    }

    // ── 落地收尾 ────────────────────────────────────────────────────────

    private static ActionResult finishSuccess(AimcContext ctx, String action, JsonObject params,
                                              String reason, String announcement, String headline,
                                              String body, String broadcast, String message,
                                              long now, long day, long expiresDay, boolean fromAi) {
        // 政策记录里的公告：AI 给了就用 AI 的，否则用引擎生成的正式广播文本
        String recordedAnnouncement =
                announcement == null || announcement.isBlank() ? broadcast : announcement.trim();
        ctx.government.recordPolicy(action, params, now, day, reason, recordedAnnouncement, expiresDay);
        String source = fromAi ? "AI 政府" : "本地规则";
        StringBuilder newsBody = new StringBuilder();
        newsBody.append("【").append(source).append("】").append(body);
        if (announcement != null && !announcement.isBlank()) {
            newsBody.append(" 官方公告：").append(announcement.trim());
        }
        String text = newsBody.toString();
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        ctx.news.add(headline, text, "policy", now, day, new JsonArray());
        Log.info("政府政策落地：" + action + " " + params + "（" + source + "）");
        // 每条政策都带公告一起播出去，不依赖调用方是否传了 announcement
        String publicBroadcast = broadcast;
        if (announcement != null && !announcement.isBlank()) {
            publicBroadcast = broadcast + "｜公告：" + announcement.trim();
            if (publicBroadcast.length() > 200) {
                publicBroadcast = publicBroadcast.substring(0, 200);
            }
        }
        return ActionResult.ok(message, publicBroadcast == null ? "" : publicBroadcast, headline);
    }

    /** 预算不足：写一条带 rejected 标记的政策记录，但不广播。 */
    private static ActionResult rejectForBudget(AimcContext ctx, String action, JsonObject params,
                                                String reason, long now, long day) {
        JsonObject recorded = copyParams(params);
        recorded.addProperty("rejected", "insufficient_budget");
        ctx.government.recordPolicy(action, recorded, now, day, reason, "", -1L);
        Log.info("政府政策因预算不足被拒绝：" + action);
        return ActionResult.fail("政府：预算不足");
    }

    // ── 玩家市场操作回调 ────────────────────────────────────────────────

    /**
     * 每次玩家发起 {@code /ex} 市场调用时调一次：只做一次本地宏观体检（更新指标），
     * <b>不</b>在这里发 AI 请求；上层根据 {@link #shouldAskAi(AimcContext, long)} 决定。
     */
    public static void onPlayerMarketAction(AimcContext ctx, Account actor) {
        if (ctx == null) {
            return;
        }
        try {
            Collection<Account> online = actor == null
                    ? Collections.emptyList() : Collections.singletonList(actor);
            updateIndicators(ctx, online);
        } catch (RuntimeException error) {
            // 体检失败不能影响玩家这次 /ex 操作
            Log.warn("玩家市场操作触发的政府体检失败", error);
        }
    }

    /** 一个交易日等于多少个 market tick（政策"天数"和价格事件"ticks"换算用）。 */
    public static long ticksPerDay(AimcContext ctx) {
        if (ctx == null) {
            return 1L;
        }
        long tickMs = Math.max(1L, ctx.clock.tickMillis());
        long dayMs = Math.max(1L, ctx.clock.dayMillis());
        return Math.max(1L, dayMs / tickMs);
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static boolean isAction(String action) {
        for (String known : GovernmentStore.ACTIONS) {
            if (known.equals(action)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedBp(int bp) {
        for (int step : RATE_BP_STEPS) {
            if (step == bp) {
                return true;
            }
        }
        return false;
    }

    private static String resolveSector(String raw) {
        for (Map.Entry<String, String> entry : CompanyStore.SECTORS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(raw) || entry.getValue().equalsIgnoreCase(raw)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static long cooldownMinutes(GovernmentStore gov, String action, long nowMs) {
        return Math.max(1L, (gov.cooldownRemainingMs(action, nowMs) + 59_999L) / 60_000L);
    }

    private static JsonObject copyParams(JsonObject params) {
        JsonObject copy = new JsonObject();
        if (params != null) {
            for (Map.Entry<String, JsonElement> entry : params.entrySet()) {
                copy.add(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static String reasonSentence(String reason) {
        return reason == null || reason.isBlank() ? "" : "理由：" + reason;
    }

    private static String shortMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
