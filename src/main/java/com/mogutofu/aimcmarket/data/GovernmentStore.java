package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 政府/央行：政策利率、税率、预算、政策历史、冷却、宏观指标。
 *
 * <p>AI 只给"要不要出政策、出什么政策"的建议，真正落地由 {@code engine.GovernmentEngine}
 * 按这里的边界校验后调用。所有政策都会记进 {@code government.json} 并广播。</p>
 */
public final class GovernmentStore {
    /** 所有政策动作。 */
    public static final String[] ACTIONS = {
            "none", "rate_cut", "rate_hike", "welfare", "stimulus", "austerity",
            "subsidy", "tariff", "liquidity", "min_wage"
    };

    /** 政策历史里的一条。 */
    public static final class Policy {
        public long id;
        public String action = "none";
        public JsonObject params = new JsonObject();
        public long atMs;
        public long day;
        public String reason = "";
        public String announcement = "";
        /** 持续型政策（补贴/关税/刺激/流动性）到哪个交易日为止；-1 表示长期。 */
        public long expiresDay = -1L;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("action", action);
            json.add("params", params);
            json.addProperty("at", atMs);
            json.addProperty("day", day);
            json.addProperty("reason", reason);
            json.addProperty("announcement", announcement);
            json.addProperty("expiresDay", expiresDay);
            return json;
        }

        public static Policy fromJson(JsonObject json) {
            Policy policy = new Policy();
            policy.id = JsonFile.lng(json, "id", 0L);
            policy.action = JsonFile.str(json, "action", "none");
            policy.params = JsonFile.object(json, "params");
            policy.atMs = JsonFile.lng(json, "at", 0L);
            policy.day = JsonFile.lng(json, "day", 0L);
            policy.reason = JsonFile.str(json, "reason", "");
            policy.announcement = JsonFile.str(json, "announcement", "");
            policy.expiresDay = JsonFile.lng(json, "expiresDay", -1L);
            return policy;
        }

        public String paramsString() {
            return params == null ? "" : params.toString();
        }
    }

    /** 宏观指标快照。 */
    public static final class Indicators {
        public long moneyCents;
        public double inflation7d;
        public double indexChange7d;
        public long medianWealthCents;
        public long budgetCents;
        public double employment;
        public long volume7dCents;
        public long updatedAtMs;
        /** 压力分 0-100。 */
        public int stress;
    }

    private final Path file;
    private final Ledger ledger;
    private long budgetCents;

    // 利率与税率
    public double policyRate = 0.03;
    public double tradeTax = 0.005;
    public double companyTax = 0.10;

    // 政策
    private final List<Policy> policies = new ArrayList<>();
    private final Map<String, Long> cooldownUntilMs = new LinkedHashMap<>();
    private long lastRateChangeDay = -1000L;
    private int lastRateDirection = 0; // +1 加息 -1 降息
    private long nextPolicyId = 1L;

    // 指标
    public final Indicators indicators = new Indicators();
    public long lastAiCheckMs;
    public long lastAiDecisionMs;
    public long lastPolicyAtMs;

    private final BalanceAccount budget = new BalanceAccount() {
        @Override
        public String accountId() {
            return Ledger.GOV_BUDGET;
        }

        @Override
        public long balanceCents() {
            return budgetCents;
        }

        @Override
        public void addBalanceCents(long delta) {
            budgetCents = Math.addExact(budgetCents, delta);
        }
    };

    public GovernmentStore(Path file, Ledger ledger) {
        this.file = file;
        this.ledger = ledger;
    }

    public void load() {
        policies.clear();
        cooldownUntilMs.clear();
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            policyRate = Cents.clampDouble(JsonFile.dbl(root, "policyRate", 0.03), 0.0, 0.10);
            tradeTax = Cents.clampDouble(JsonFile.dbl(root, "tradeTax", 0.005), 0.0, 0.05);
            companyTax = Cents.clampDouble(JsonFile.dbl(root, "companyTax", 0.10), 0.0, 0.50);
            budgetCents = JsonFile.lng(root, "budgetCents", 0L);
            lastRateChangeDay = JsonFile.lng(root, "lastRateChangeDay", -1000L);
            lastRateDirection = JsonFile.intVal(root, "lastRateDirection", 0);
            nextPolicyId = JsonFile.lng(root, "nextPolicyId", 1L);
            lastAiCheckMs = JsonFile.lng(root, "lastAiCheckMs", 0L);
            lastAiDecisionMs = JsonFile.lng(root, "lastAiDecisionMs", 0L);
            lastPolicyAtMs = JsonFile.lng(root, "lastPolicyAtMs", 0L);
            for (JsonElement element : JsonFile.array(root, "policies")) {
                if (element.isJsonObject()) {
                    Policy policy = Policy.fromJson(element.getAsJsonObject());
                    if (!"none".equals(policy.action)) {
                        policies.add(policy);
                    }
                }
            }
            for (Map.Entry<String, JsonElement> entry : JsonFile.object(root, "cooldownUntilMs").entrySet()) {
                try {
                    cooldownUntilMs.put(entry.getKey(), entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            JsonObject indicatorJson = JsonFile.object(root, "indicators");
            indicators.moneyCents = JsonFile.lng(indicatorJson, "moneyCents", 0L);
            indicators.inflation7d = JsonFile.dbl(indicatorJson, "inflation7d", 0.0);
            indicators.indexChange7d = JsonFile.dbl(indicatorJson, "indexChange7d", 0.0);
            indicators.medianWealthCents = JsonFile.lng(indicatorJson, "medianWealthCents", 0L);
            indicators.budgetCents = JsonFile.lng(indicatorJson, "budgetCents", 0L);
            indicators.employment = JsonFile.dbl(indicatorJson, "employment", 0.0);
            indicators.volume7dCents = JsonFile.lng(indicatorJson, "volume7dCents", 0L);
            indicators.updatedAtMs = JsonFile.lng(indicatorJson, "updatedAtMs", 0L);
            indicators.stress = JsonFile.intVal(indicatorJson, "stress", 0);
        }
        while (policies.size() > 100) {
            policies.remove(0);
        }
        Log.info("政府加载完成：政策利率 " + Cents.formatPercent(policyRate)
                + "，预算 " + Cents.format(budgetCents) + " 币，历史政策 " + policies.size() + " 条");
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("policyRate", policyRate);
        root.addProperty("tradeTax", tradeTax);
        root.addProperty("companyTax", companyTax);
        root.addProperty("budgetCents", budgetCents);
        root.addProperty("lastRateChangeDay", lastRateChangeDay);
        root.addProperty("lastRateDirection", lastRateDirection);
        root.addProperty("nextPolicyId", nextPolicyId);
        root.addProperty("lastAiCheckMs", lastAiCheckMs);
        root.addProperty("lastAiDecisionMs", lastAiDecisionMs);
        root.addProperty("lastPolicyAtMs", lastPolicyAtMs);
        JsonArray array = new JsonArray();
        for (Policy policy : policies) {
            array.add(policy.toJson());
        }
        root.add("policies", array);
        JsonObject cooldown = new JsonObject();
        for (Map.Entry<String, Long> entry : cooldownUntilMs.entrySet()) {
            cooldown.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("cooldownUntilMs", cooldown);
        JsonObject indicatorJson = new JsonObject();
        indicatorJson.addProperty("moneyCents", indicators.moneyCents);
        indicatorJson.addProperty("inflation7d", indicators.inflation7d);
        indicatorJson.addProperty("indexChange7d", indicators.indexChange7d);
        indicatorJson.addProperty("medianWealthCents", indicators.medianWealthCents);
        indicatorJson.addProperty("budgetCents", indicators.budgetCents);
        indicatorJson.addProperty("employment", indicators.employment);
        indicatorJson.addProperty("volume7dCents", indicators.volume7dCents);
        indicatorJson.addProperty("updatedAtMs", indicators.updatedAtMs);
        indicatorJson.addProperty("stress", indicators.stress);
        root.add("indicators", indicatorJson);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 government.json 失败（下个 tick 重试）", error);
        }
    }

    // ── 预算 ──

    public BalanceAccount budgetAccount() {
        return budget;
    }

    public long budgetCents() {
        return budgetCents;
    }

    public boolean canSpend(long amountCents) {
        return amountCents > 0L && budgetCents >= amountCents;
    }

    // ── 有效利率/税率 ──

    public double effectiveTradeTax() {
        return Cents.clampDouble(tradeTax, 0.0, 0.05);
    }

    public double effectiveCompanyTax() {
        return Cents.clampDouble(companyTax, 0.0, 0.50);
    }

    /** 政策带来的商品价格乘数：补贴降价、关税涨价，可叠加，夹在 [0.5, 2.0]。 */
    public double commodityMultiplier(String itemId, long tradingDay) {
        if (itemId == null) {
            return 1.0;
        }
        double multiplier = 1.0;
        for (Policy policy : policies) {
            if (policy.expiresDay >= 0L && tradingDay > policy.expiresDay) {
                continue;
            }
            if (!"subsidy".equals(policy.action) && !"tariff".equals(policy.action)) {
                continue;
            }
            String target = JsonFile.str(policy.params, "target", "");
            if (!target.equalsIgnoreCase(itemId)) {
                continue;
            }
            double ratio = Cents.clampDouble(JsonFile.dbl(policy.params, "ratio", 0.0), 0.0, 0.20);
            if ("subsidy".equals(policy.action)) {
                multiplier *= (1.0 - ratio);
            } else {
                multiplier *= (1.0 + ratio);
            }
        }
        return Cents.clampDouble(multiplier, 0.5, 2.0);
    }

    /** 刺激政策带来的行业景气加成（1.0 = 没有）。 */
    public double sectorStimulusMultiplier(String sector, long tradingDay) {
        double bonus = 0.0;
        for (Policy policy : policies) {
            if (!"stimulus".equals(policy.action)) {
                continue;
            }
            if (policy.expiresDay >= 0L && tradingDay > policy.expiresDay) {
                continue;
            }
            for (JsonElement element : JsonFile.array(policy.params, "sectors")) {
                try {
                    if (element.getAsString().equalsIgnoreCase(sector)) {
                        bonus += Cents.clampDouble(JsonFile.dbl(policy.params, "ratio", 0.05), 0.0, 0.20);
                    }
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
        }
        return Cents.clampDouble(1.0 + bonus, 0.9, 1.2);
    }

    /** 流动性支持倍数（1.0 = 没有）。 */
    public double liquidityMultiplier(long tradingDay) {
        double multiplier = 1.0;
        for (Policy policy : policies) {
            if (!"liquidity".equals(policy.action)) {
                continue;
            }
            if (policy.expiresDay >= 0L && tradingDay > policy.expiresDay) {
                continue;
            }
            multiplier *= 1.0 + Cents.clampDouble(JsonFile.dbl(policy.params, "ratio", 0.0), 0.0, 1.0);
        }
        return Cents.clampDouble(multiplier, 1.0, 3.0);
    }

    /** 最低工资政策（分）；没有政策返回默认值。 */
    public long effectiveMinWageCents(long defaultWageCents, long tradingDay) {
        long wage = defaultWageCents;
        for (Policy policy : policies) {
            if (!"min_wage".equals(policy.action)) {
                continue;
            }
            if (policy.expiresDay >= 0L && tradingDay > policy.expiresDay) {
                continue;
            }
            wage = Math.max(wage, JsonFile.lng(policy.params, "amountCents", defaultWageCents));
        }
        return Cents.clamp(wage, defaultWageCents, 5_000L);
    }

    // ── 冷却 ──

    /** 每种政策的冷却时间（毫秒）；rate 用交易日判断，这里返回 0。 */
    public static long cooldownMs(String action) {
        return switch (action) {
            case "welfare", "min_wage" -> 24L * 3_600_000L;
            case "stimulus", "austerity", "subsidy", "tariff", "liquidity" -> 12L * 3_600_000L;
            default -> 0L;
        };
    }

    public boolean isOnCooldown(String action, long nowMs) {
        Long until = cooldownUntilMs.get(action);
        return until != null && nowMs < until;
    }

    public long cooldownRemainingMs(String action, long nowMs) {
        Long until = cooldownUntilMs.get(action);
        return until == null ? 0L : Math.max(0L, until - nowMs);
    }

    public void setCooldown(String action, long nowMs) {
        long duration = cooldownMs(action);
        if (duration > 0L) {
            cooldownUntilMs.put(action, nowMs + duration);
        }
    }

    /** 利率方向冷却：同一方向 6 个交易日内只能动一次。 */
    public boolean canChangeRate(int direction, long tradingDay) {
        if (direction == 0) {
            return false;
        }
        if (lastRateDirection != direction) {
            return true;
        }
        return tradingDay - lastRateChangeDay >= 6L;
    }

    public long rateCooldownRemainingDays(int direction, long tradingDay) {
        if (lastRateDirection != direction) {
            return 0L;
        }
        return Math.max(0L, 6L - (tradingDay - lastRateChangeDay));
    }

    public void applyRateChange(int direction, long tradingDay) {
        lastRateDirection = direction;
        lastRateChangeDay = tradingDay;
    }

    // ── 政策记录 ──

    public Policy recordPolicy(String action, JsonObject params, long nowMs, long tradingDay,
                               String reason, String announcement, long expiresDay) {
        Policy policy = new Policy();
        policy.id = nextPolicyId++;
        policy.action = action;
        policy.params = params == null ? new JsonObject() : params;
        policy.atMs = nowMs;
        policy.day = tradingDay;
        policy.reason = reason == null ? "" : reason;
        policy.announcement = announcement == null ? "" : announcement;
        policy.expiresDay = expiresDay;
        policies.add(policy);
        while (policies.size() > 100) {
            policies.remove(0);
        }
        setCooldown(action, nowMs);
        lastPolicyAtMs = nowMs;
        return policy;
    }

    public List<Policy> policies() {
        return policies;
    }

    /** 最近 N 条政策（新的在前）。 */
    public List<Policy> recentPolicies(int limit) {
        List<Policy> result = new ArrayList<>();
        for (int i = policies.size() - 1; i >= 0 && result.size() < limit; i--) {
            result.add(policies.get(i));
        }
        return result;
    }

    /** 政策是否还在生效（显示用）。 */
    public boolean isActive(Policy policy, long tradingDay) {
        return policy.expiresDay < 0L || tradingDay <= policy.expiresDay;
    }

    /** 宏观指标更新。 */
    public void updateIndicators(long moneyCents, double inflation7d, double indexChange7d,
                                 long medianWealthCents, double employment, long volume7dCents,
                                 long nowMs, int stress) {
        indicators.moneyCents = moneyCents;
        indicators.inflation7d = inflation7d;
        indicators.indexChange7d = indexChange7d;
        indicators.medianWealthCents = medianWealthCents;
        indicators.budgetCents = budgetCents;
        indicators.employment = employment;
        indicators.volume7dCents = volume7dCents;
        indicators.updatedAtMs = nowMs;
        indicators.stress = stress;
    }

    public void setPolicyRate(double rate) {
        policyRate = Cents.clampDouble(rate, 0.0, 0.10);
    }

    public void setTradeTax(double tax) {
        tradeTax = Cents.clampDouble(tax, 0.0, 0.05);
    }

    public void setCompanyTax(double tax) {
        companyTax = Cents.clampDouble(tax, 0.0, 0.50);
    }

    /** 政府支出：预算 → 目标账户。 */
    public void spend(BalanceAccount to, long amountCents, String kind, String memo) {
        if (!canSpend(amountCents)) {
            throw new IllegalStateException("政府预算不足");
        }
        ledger.transfer(budget, to, amountCents, kind, "政府", memo);
    }

    /** 政府收入：从某账户收到预算（税等）。 */
    public void collect(BalanceAccount from, long amountCents, String kind, String memo) {
        if (amountCents <= 0L) {
            return;
        }
        ledger.transfer(from, budget, amountCents, kind, "政府", memo);
    }

    public Path file() {
        return file;
    }

    public Ledger ledger() {
        return ledger;
    }
}
