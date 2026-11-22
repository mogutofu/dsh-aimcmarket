package com.mogutofu.aimcmarket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.GovernmentEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GovernmentEngine} 政府/央行的单元测试：宏观体检、本地兜底、政策边界、预算、冷却、补贴乘数。
 */
class GovernmentTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String UUID_A = "00000000-0000-0000-0000-0000000000a1";
    private static final String UUID_B = "00000000-0000-0000-0000-0000000000b2";
    private static final String IRON = "minecraft:iron_ingot";

    @TempDir
    Path tempDir;

    private AimcContext newContext() {
        return AimcContext.load(tempDir, tempDir.resolve("config"), MarketSettings.defaults(), NOW_MS, SEED);
    }

    private Account newAccount(AimcContext ctx, String uuid, String name) {
        return ctx.accounts.getOrCreate(uuid, name, NOW_MS, ctx.settings.startingBalanceCents, true);
    }

    /** 构造一条决策 JSON；paramsJson 为 null 时给空对象。 */
    private static JsonObject decision(String action, String paramsJson) {
        JsonObject json = new JsonObject();
        json.addProperty("action", action);
        JsonObject params = new JsonObject();
        if (paramsJson != null && !paramsJson.isBlank()) {
            params = JsonParser.parseString(paramsJson).getAsJsonObject();
        }
        json.add("params", params);
        json.addProperty("reason", "单元测试");
        return json;
    }

    // ══════════════════════════════════════════════════════════════
    // 指标 / 本地兜底
    // ══════════════════════════════════════════════════════════════

    @Test
    void indicatorsAndLocalFallbackAreSane() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx, UUID_A, "TesterA");

        GovernmentEngine.updateIndicators(ctx, List.of(account));

        int stress = GovernmentEngine.stressScore(ctx);
        assertTrue(stress >= 0 && stress <= 100, "压力分应在 0-100，实际 " + stress);
        assertEquals(stress, ctx.government.indicators.stress, "指标快照里的压力分应与 stressScore 一致");
        assertEquals(ctx.nowMs(), ctx.government.indicators.updatedAtMs, "指标应记录更新时间");
        assertEquals(ctx.ledger.circulationCents(), ctx.government.indicators.moneyCents,
                "指标里的货币总量应等于流通量");

        JsonObject fallback = GovernmentEngine.localFallback(ctx);
        assertNotNull(fallback, "本地兜底必须返回决策 JSON");
        assertTrue(fallback.has("action"), "本地兜底必须带 action");
        String action = fallback.get("action").getAsString();
        assertTrue(Arrays.asList(GovernmentStore.ACTIONS).contains(action),
                "本地兜底 action 必须是合法枚举，实际 " + action);
        assertTrue(fallback.has("params"), "本地兜底必须带 params 对象");
    }

    // ══════════════════════════════════════════════════════════════
    // 预算
    // ══════════════════════════════════════════════════════════════

    @Test
    void welfareRejectedWhenBudgetInsufficient() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx, UUID_A, "TesterA");
        // 加载时会做 80000 币预算保底，这里先清空，验证"预算不足"分支
        long floor = ctx.government.budgetCents();
        if (floor > 0L) {
            ctx.ledger.burn(ctx.government.budgetAccount(), floor, "test.drain", "Tester", "清空预算");
        }
        assertEquals(0L, ctx.government.budgetCents(), "清空后预算应为 0");
        long walletBefore = account.balanceCents();

        ActionResult result = GovernmentEngine.applyDecision(ctx,
                decision("welfare", "{\"amountCents\":5000}"), false, List.of(account));

        assertFalse(result.ok, "预算不足时福利政策应失败");
        assertTrue(result.message.contains("预算不足"), "失败原因应说明预算不足，实际：" + result.message);
        assertTrue(result.broadcast.isEmpty(), "预算不足的福利不应广播");
        assertEquals(1, ctx.government.policies().size(), "应记录一条被拒的政策");
        GovernmentStore.Policy rejected = ctx.government.policies().get(0);
        assertEquals("insufficient_budget",
                rejected.params.get("rejected").getAsString(), "政策记录应带 insufficient_budget 标记");
        assertEquals(walletBefore, account.balanceCents(), "被拒后玩家余额不应变化");
        assertTrue(ctx.ledger.isBalanced(), "被拒政策不应破坏账本平衡");
    }

    @Test
    void welfarePaidWhenBudgetSufficient() {
        AimcContext ctx = newContext();
        // A 有钱、B 没钱：在线两人中位数 25000 分，只有 B 低于中位数，符合发放条件。
        Account rich = newAccount(ctx, UUID_A, "TesterA");
        Account poor = ctx.accounts.getOrCreate(UUID_B, "TesterB", NOW_MS, 0L, false);
        ctx.ledger.issue(ctx.government.budgetAccount(), 1_000_000L, "test.budget", "Tester", "测试预算");
        long budgetBefore = ctx.government.budgetCents();
        long richBefore = rich.balanceCents();

        ActionResult result = GovernmentEngine.applyDecision(ctx,
                decision("welfare", "{\"amountCents\":10000}"), false, List.of(rich, poor));

        assertTrue(result.ok, "预算充足的福利政策应该成功：" + result.message);
        assertEquals(10_000L, poor.balanceCents(), "低于中位数的玩家应收到 100 币福利");
        assertEquals(richBefore, rich.balanceCents(), "高于中位数的玩家不应收到福利");
        assertEquals(budgetBefore - 10_000L, ctx.government.budgetCents(), "福利支出应从预算扣除");
        assertEquals(1, ctx.government.policies().size(), "应记录一条已生效政策");
        assertFalse(result.broadcast.isEmpty(), "生效的福利政策应有全服公告");
        assertTrue(ctx.ledger.isBalanced(), "福利发放后账本应平衡");
    }

    // ══════════════════════════════════════════════════════════════
    // none / 非法政策
    // ══════════════════════════════════════════════════════════════

    @Test
    void noneDecisionNeitherBroadcastsNorRecordsPolicy() {
        AimcContext ctx = newContext();
        newAccount(ctx, UUID_A, "TesterA");
        assertEquals(0, ctx.government.policies().size(), "初始不应有政策记录");

        ActionResult result = GovernmentEngine.applyDecision(ctx, decision("none", null), true);

        assertTrue(result.ok, "none 决策应被接受");
        assertTrue(result.broadcast.isEmpty(), "none 不应广播");
        assertEquals(0, ctx.government.policies().size(), "none 不应记成政策");
        assertTrue(result.newsHeadline.isEmpty(), "none 不应写新闻");
    }

    @Test
    void illegalActionAndParamsAreRejected() {
        AimcContext ctx = newContext();
        newAccount(ctx, UUID_A, "TesterA");

        assertFalse(GovernmentEngine.applyDecision(ctx, decision("print_money", null), true).ok,
                "未知 action 应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("rate_cut", "{\"bp\":33}"), true).ok,
                "非法 bp 应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("welfare", "{\"amountCents\":0}"), true).ok,
                "福利金额 0 应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("welfare", "{\"amountCents\":60000}"), true).ok,
                "福利金额超过 500 币应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx,
                decision("stimulus", "{\"amountCents\":100,\"sectors\":[\"mining\"],\"days\":0}"), true).ok,
                "刺激政策天数 0 应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("austerity", "{\"ratio\":0.5}"), true).ok,
                "紧缩比例超过 2% 应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("liquidity", "{\"ratio\":2}"), true).ok,
                "流动性比例超过 100% 应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("min_wage", "{\"amountCents\":1}"), true).ok,
                "最低工资低于 10 币应被拒");
        assertFalse(GovernmentEngine.applyDecision(ctx, decision("subsidy",
                "{\"target\":\"minecraft:not_a_real_item\",\"ratio\":0.1,\"days\":3}"), true).ok,
                "补贴未知商品应被拒");

        assertEquals(0, ctx.government.policies().size(), "被拒的非法政策不应写进历史");
        assertEquals(0.03, ctx.government.policyRate, 1.0e-12, "被拒的政策不应改变利率");
    }

    // ══════════════════════════════════════════════════════════════
    // 利率与冷却
    // ══════════════════════════════════════════════════════════════

    @Test
    void rateCutLowerPolicyRateByExactly25bpWithoutCooldown() {
        AimcContext ctx = newContext();
        newAccount(ctx, UUID_A, "TesterA");
        double before = ctx.government.policyRate;
        assertEquals(0.03, before, 1.0e-12, "默认政策利率应为 3%");

        ActionResult cut = GovernmentEngine.applyDecision(ctx, decision("rate_cut", "{\"bp\":25}"), false);
        assertTrue(cut.ok, "降息应该成功：" + cut.message);
        assertEquals(0.0025, before - ctx.government.policyRate, 1.0e-12, "政策利率应精确下调 25bp");
        assertEquals(before - 0.0025, ctx.government.policyRate, 1.0e-12,
                "降息后政策利率应为 " + (before - 0.0025));
        assertFalse(cut.broadcast.isEmpty(), "降息应当广播");
        assertEquals(1, ctx.government.policies().size(), "降息应记录一条政策");

        ActionResult again = GovernmentEngine.applyDecision(ctx, decision("rate_cut", "{\"bp\":25}"), false);
        assertTrue(again.ok, "冷却已取消，同方向可以连续降息：" + again.message);
        assertEquals(before - 0.005, ctx.government.policyRate, 1.0e-12, "第二次降息应继续下调 25bp");
    }

    // ══════════════════════════════════════════════════════════════
    // 商品政策
    // ══════════════════════════════════════════════════════════════

    @Test
    void subsidyLowersCommodityMultiplier() {
        AimcContext ctx = newContext();
        newAccount(ctx, UUID_A, "TesterA");

        ActionResult result = GovernmentEngine.applyDecision(ctx,
                decision("subsidy", "{\"target\":\"" + IRON + "\",\"ratio\":0.1,\"days\":3}"), false);

        assertTrue(result.ok, "补贴政策应该成功：" + result.message);
        double multiplier = ctx.government.commodityMultiplier(IRON, ctx.tradingDay());
        assertTrue(multiplier < 1.0, "10% 补贴后商品价格乘数应小于 1，实际 " + multiplier);
        assertEquals(0.9, multiplier, 1.0e-9, "10% 补贴后乘数应为 0.9");
        assertEquals(1, ctx.government.policies().size(), "补贴应记录一条政策");
        assertFalse(result.broadcast.isEmpty(), "补贴政策应有全服公告");
    }

    @Test
    void tariffRaisesCommodityMultiplier() {
        AimcContext ctx = newContext();
        newAccount(ctx, UUID_A, "TesterA");

        ActionResult result = GovernmentEngine.applyDecision(ctx,
                decision("tariff", "{\"target\":\"" + IRON + "\",\"ratio\":0.2,\"days\":3}"), false);

        assertTrue(result.ok, "关税政策应该成功：" + result.message);
        double multiplier = ctx.government.commodityMultiplier(IRON, ctx.tradingDay());
        assertTrue(multiplier > 1.0, "20% 关税后商品价格乘数应大于 1，实际 " + multiplier);
        assertEquals(1.2, multiplier, 1.0e-9, "20% 关税后乘数应为 1.2");
    }
}
