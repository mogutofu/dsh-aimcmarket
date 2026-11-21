package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.TradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TradeService} 商品买卖的单元测试：扣款/销毁/增发、各种拒单、requestId 幂等。
 *
 * <p>交易有 3 秒/单/人的限流，测试里每次都把 {@code lastTradeAtMs} 归零。</p>
 */
class TradeServiceTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String TEST_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String IRON = "minecraft:iron_ingot";

    @TempDir
    Path tempDir;

    private AimcContext newContext() {
        return AimcContext.load(tempDir, tempDir.resolve("config"), MarketSettings.defaults(), NOW_MS, SEED);
    }

    private Account newAccount(AimcContext ctx) {
        return ctx.accounts.getOrCreate(TEST_UUID, "Tester", NOW_MS, ctx.settings.startingBalanceCents, true);
    }

    private static void give(AimcContext ctx, Account account, long cents) {
        ctx.ledger.issue(account, cents, "test.fund", "Tester", "测试注资");
    }

    @Test
    void buyDeductsBalanceAndBurnsMoney() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        account.lastTradeAtMs = 0L;

        long unit = TradeService.effectiveAskCents(ctx, IRON);
        assertTrue(unit > 0L, "买价必须为正");
        long balanceBefore = account.balanceCents();
        long moneyBefore = ctx.totalMoneyCents();

        ActionResult result = TradeService.buy(ctx, account, IRON, 3);
        assertTrue(result.ok, "买入应该成功：" + result.message);

        long expectedCost = unit * 3L;
        assertEquals(expectedCost, balanceBefore - account.balanceCents(), "扣款应等于单价×数量");
        assertEquals(expectedCost, moneyBefore - ctx.totalMoneyCents(), "买入应销毁货币（点差回收）");
        assertTrue(result.message.contains("买入"), "成功消息应包含买入提示，实际：" + result.message);
        assertTrue(ctx.ledger.isBalanced(), "买入后账本应平衡");
    }

    @Test
    void buyRejectsInvalidOrders() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);

        // 1) 余额不足
        long all = account.balanceCents();
        ctx.ledger.burn(account, all, "test.burn", "Tester", "清空钱包");
        account.lastTradeAtMs = 0L;
        assertFalse(TradeService.buy(ctx, account, IRON, 1).ok, "余额为 0 时买入应被拒");
        give(ctx, account, 3_000_000L);

        // 2) 数量越界
        account.lastTradeAtMs = 0L;
        assertFalse(TradeService.buy(ctx, account, IRON, 0).ok, "数量 0 应被拒");
        account.lastTradeAtMs = 0L;
        assertFalse(TradeService.buy(ctx, account, IRON, -1).ok, "负数量应被拒");
        account.lastTradeAtMs = 0L;
        assertFalse(TradeService.buy(ctx, account, IRON, 577).ok, "数量 577（>576）应被拒");

        // 3) 目录外物品
        account.lastTradeAtMs = 0L;
        assertFalse(TradeService.buy(ctx, account, "minecraft:not_a_real_item", 1).ok, "目录外物品应被拒");

        // 4) 单笔金额上限（默认 5000 币）
        account.lastTradeAtMs = 0L;
        long unit = TradeService.effectiveAskCents(ctx, IRON);
        long tooMany = Math.min(576L, ctx.settings.maxOrderValueCents / Math.max(1L, unit) + 1L);
        assertTrue(unit * tooMany > ctx.settings.maxOrderValueCents, "构造的数量应真的超过单笔上限");
        assertFalse(TradeService.buy(ctx, account, IRON, (int) tooMany).ok, "超过单笔金额上限应被拒");

        // 5) 管理员冻结
        ctx.settings.adminFreeze = true;
        account.lastTradeAtMs = 0L;
        assertFalse(TradeService.buy(ctx, account, IRON, 1).ok, "adminFreeze=true 时买入应被拒");
        ctx.settings.adminFreeze = false;

        assertTrue(ctx.ledger.isBalanced(), "被拒订单不应破坏账本平衡");
    }

    @Test
    void duplicateRequestIdIsIdempotent() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 3_000_000L);
        String requestId = "junit-" + UUID.randomUUID();

        account.lastTradeAtMs = 0L;
        long before = account.balanceCents();
        ActionResult first = TradeService.buy(ctx, account, IRON, 2, requestId);
        long afterFirst = account.balanceCents();
        assertTrue(first.ok, "第一次买入应该成功：" + first.message);
        assertTrue(afterFirst < before, "第一次买入应真的扣钱");

        account.lastTradeAtMs = 0L;
        ActionResult second = TradeService.buy(ctx, account, IRON, 2, requestId);
        assertTrue(second.ok, "重复 requestId 应返回第一次的成功结果");
        assertEquals(afterFirst, account.balanceCents(), "重复 requestId 不能重复扣钱");
        assertEquals(first.message, second.message, "重复 requestId 应返回同一条结果消息");
    }

    @Test
    void sellIssuesMoneyAtBidPrice() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        account.lastTradeAtMs = 0L;

        long unit = TradeService.effectiveBidCents(ctx, IRON);
        assertTrue(unit > 0L, "回收价必须为正");
        assertTrue(unit < TradeService.effectiveAskCents(ctx, IRON), "回收价应低于买价（有点差）");

        long balanceBefore = account.balanceCents();
        long moneyBefore = ctx.totalMoneyCents();
        ActionResult result = TradeService.sell(ctx, account, IRON, 2);
        assertTrue(result.ok, "卖出应该成功：" + result.message);

        long expected = unit * 2L;
        assertEquals(expected, account.balanceCents() - balanceBefore, "回收金额应等于回收价×数量");
        assertEquals(expected, ctx.totalMoneyCents() - moneyBefore, "卖出应由系统增发货币");
        assertTrue(ctx.ledger.isBalanced(), "卖出后账本应平衡");
    }

    @Test
    void effectivePricesArePositiveForCatalogItems() {
        AimcContext ctx = newContext();
        for (var item : ctx.market.enabledItems()) {
            assertTrue(TradeService.effectiveAskCents(ctx, item.id) > 0L, item.id + " 买价必须为正");
            assertTrue(TradeService.effectiveBidCents(ctx, item.id) > 0L, item.id + " 回收价必须为正");
        }
        assertEquals(1L, TradeService.effectiveAskCents(ctx, "minecraft:not_a_real_item"),
                "目录外物品的报价兜底为 1 分");
    }
}
