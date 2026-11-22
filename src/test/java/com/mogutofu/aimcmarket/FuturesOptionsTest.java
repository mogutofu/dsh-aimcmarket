package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.FuturesStore;
import com.mogutofu.aimcmarket.data.OptionsStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.FuturesEngine;
import com.mogutofu.aimcmarket.engine.OptionsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FuturesEngine} 期货与 {@link OptionsEngine} 期权的单元测试：保证金、盯市、平仓、到期结算、记账平衡、BS 定价。
 */
class FuturesOptionsTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String TEST_UUID = "00000000-0000-0000-0000-000000000004";
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

    // ══════════════════════════════════════════════════════════════
    // 期货
    // ══════════════════════════════════════════════════════════════

    @Test
    void futuresOpenMarksAndClosesBalanced() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);

        ActionResult open = FuturesEngine.open(ctx, account, "IRON", true, 1L, 7);
        assertTrue(open.ok, "期货开多应该成功：" + open.message);
        assertEquals(1, ctx.futures.all().size(), "开仓后应有一笔持仓");
        FuturesStore.FuturePosition position = ctx.futures.of(account.uuid).get(0);
        assertTrue(position.marginCents > 0L, "开仓保证金应为正，实际 " + position.marginCents);
        assertEquals(position.initialMarginCents, position.marginCents, "刚开仓时权益应等于初始保证金");
        assertEquals(ctx.tradingDay() + 7L, position.expiryDay, "7 交易日合约应在第 7 个交易日到期");
        assertTrue(ctx.ledger.isBalanced(), "期货开仓四腿记账后账本应平衡");

        // 价格拉到基准价 10 倍（高于买价 500% 保底）→ 盯市：lastMarkPriceCents 与 marginCents 都应变化
        long entry = position.entryPriceCents;
        long price = ctx.market.price(IRON);
        ctx.market.setPrice(IRON, price * 10L);
        FuturesEngine.onMarketTick(ctx);
        assertNotEquals(entry, position.lastMarkPriceCents, "盯市后 lastMarkPriceCents 应变化");
        assertTrue(position.marginCents > position.initialMarginCents,
                "多头标的上涨后权益应增加：" + position.initialMarginCents + " → " + position.marginCents);
        assertTrue(ctx.ledger.isBalanced(), "盯市只改权益字段，账本应保持平衡");

        ActionResult close = FuturesEngine.close(ctx, account, position.id);
        assertTrue(close.ok, "期货平仓应该成功：" + close.message);
        assertTrue(ctx.futures.of(account.uuid).isEmpty(), "平仓后持仓应为空");
        assertTrue(ctx.ledger.isBalanced(), "平仓后账本应平衡");
    }

    @Test
    void futuresExpirySettlesAndRemovesPosition() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);

        ActionResult open = FuturesEngine.open(ctx, account, "GOLD", false, 1L, 7);
        assertTrue(open.ok, "期货开空应该成功：" + open.message);
        FuturesStore.FuturePosition position = ctx.futures.of(account.uuid).get(0);
        long balanceBefore = account.balanceCents();

        // 把到期日设成今天 → 下一个 tick 现金交割
        position.expiryDay = ctx.tradingDay();
        FuturesEngine.onMarketTick(ctx);

        assertTrue(ctx.futures.of(account.uuid).isEmpty(), "到期后合约应删除");
        assertTrue(ctx.ledger.isBalanced(), "到期交割后账本应平衡");
        assertTrue(account.balanceCents() >= balanceBefore, "到期退回结算不应减少余额");
    }

    @Test
    void futuresOpenRejectsBadInput() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);

        assertFalse(FuturesEngine.open(ctx, account, "IRON", true, 0L, 7).ok, "手数 0 应被拒");
        assertFalse(FuturesEngine.open(ctx, account, "IRON", true, 1L, 5).ok, "非法到期期限应被拒");
        assertFalse(FuturesEngine.open(ctx, account, "NOT_A_SYMBOL", true, 1L, 7).ok, "非法标的应被拒");
        assertEquals(0, ctx.futures.all().size(), "被拒后不应产生持仓");
    }

    // ══════════════════════════════════════════════════════════════
    // 期权
    // ══════════════════════════════════════════════════════════════

    @Test
    void blackScholesMatchesReferenceValues() {
        long call = OptionsEngine.blackScholesCents(10_000.0, 10_000.0, 0.03, 0.3, 1.0, true);
        long put = OptionsEngine.blackScholesCents(10_000.0, 10_000.0, 0.03, 0.3, 1.0, false);
        assertTrue(Math.abs(call - 1328L) <= 10L, "call 应约为 1328 分，实际 " + call);
        assertTrue(Math.abs(put - 1032L) <= 10L, "put 应约为 1032 分，实际 " + put);

        long parity = Math.round(10_000.0 - 10_000.0 * Math.exp(-0.03));
        assertTrue(Math.abs((call - put) - parity) <= 10L,
                "看涨看跌平价关系不成立：call-put = " + (call - put) + "，应为 " + parity);

        assertEquals(500L, OptionsEngine.blackScholesCents(10_500.0, 10_000.0, 0.03, 0.3, 0.0, true),
                "到期 call 应退化为内在价值 500 分");
        assertEquals(0L, OptionsEngine.blackScholesCents(10_500.0, 10_000.0, 0.03, 0.3, 0.0, false),
                "到期虚值 put 应为 0");
    }

    @Test
    void optionBuyerPaysPremiumAndCloseKeepsLedgerBalanced() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);
        StockStore.Stock stock = ctx.stocks.stock("BLK");
        assertNotNull(stock, "BLK 应存在");
        long strike = stock.priceCents;

        long before = account.balanceCents();
        ActionResult buy = OptionsEngine.open(ctx, account, false, "call", "BLK", strike, 7, 1L);
        assertTrue(buy.ok, "期权买方开仓应该成功：" + buy.message);
        assertTrue(account.balanceCents() < before, "买方应支付权利金");
        assertEquals(1, ctx.options.of(account.uuid).size(), "开仓后应有 1 笔期权持仓");
        OptionsStore.OptionPosition position = ctx.options.of(account.uuid).get(0);
        assertFalse(position.writer, "这笔持仓应是买方");
        assertEquals(0L, position.marginCents, "买方不缴保证金");
        assertTrue(ctx.ledger.isBalanced(), "期权买方开仓后账本应平衡");

        ActionResult close = OptionsEngine.close(ctx, account, position.id);
        assertTrue(close.ok, "期权买方平仓应该成功：" + close.message);
        assertTrue(ctx.options.of(account.uuid).isEmpty(), "平仓后持仓应为空");
        assertTrue(ctx.ledger.isBalanced(), "期权平仓后账本应平衡");
    }

    @Test
    void optionWriterPostsMarginAndCloseKeepsLedgerBalanced() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);
        StockStore.Stock stock = ctx.stocks.stock("BLK");
        long strike = stock.priceCents;

        long before = account.balanceCents();
        ActionResult write = OptionsEngine.open(ctx, account, true, "call", "BLK", strike, 7, 1L);
        assertTrue(write.ok, "期权卖方开仓应该成功：" + write.message);
        OptionsStore.OptionPosition position = null;
        for (OptionsStore.OptionPosition candidate : ctx.options.of(account.uuid)) {
            if (candidate.writer) {
                position = candidate;
            }
        }
        assertNotNull(position, "应能找到卖方持仓");
        assertTrue(position.marginCents > 0L, "卖方保证金应大于 0，实际 " + position.marginCents);
        assertTrue(account.balanceCents() < before, "卖方净支出应大于 0（保证金 > 权利金）");
        assertTrue(ctx.ledger.isBalanced(), "期权卖方开仓后账本应平衡");

        ActionResult close = OptionsEngine.close(ctx, account, position.id);
        assertTrue(close.ok, "期权卖方平仓应该成功：" + close.message);
        assertTrue(ctx.options.of(account.uuid).isEmpty(), "平仓后持仓应为空");
        assertTrue(ctx.ledger.isBalanced(), "期权平仓后账本应平衡");
    }

    @Test
    void optionExpiryClearsPositionsAndKeepsLedgerBalanced() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);
        StockStore.Stock stock = ctx.stocks.stock("BLK");
        long strike = stock.priceCents;

        ActionResult open = OptionsEngine.open(ctx, account, false, "put", "BLK", strike, 7, 1L);
        assertTrue(open.ok, "期权买方开仓应该成功：" + open.message);
        OptionsStore.OptionPosition position = ctx.options.of(account.uuid).get(0);

        position.expiryDay = ctx.tradingDay();
        OptionsEngine.onMarketTick(ctx);
        assertTrue(ctx.options.of(account.uuid).isEmpty(), "到期后期权持仓应清空");
        assertTrue(ctx.ledger.isBalanced(), "期权到期结算后账本应平衡");
    }

    @Test
    void optionOpenRejectsBadInput() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);
        StockStore.Stock stock = ctx.stocks.stock("BLK");
        long strike = stock.priceCents;

        assertFalse(OptionsEngine.open(ctx, account, false, "straddle", "BLK", strike, 7, 1L).ok,
                "非法期权类型应被拒");
        assertFalse(OptionsEngine.open(ctx, account, false, "call", "BLK", strike, 5, 1L).ok,
                "非法到期期限应被拒");
        assertFalse(OptionsEngine.open(ctx, account, false, "call", "BLK", strike, 7, 0L).ok,
                "张数 0 应被拒");
        assertFalse(OptionsEngine.open(ctx, account, false, "call", "BLK", strike * 3L, 7, 1L).ok,
                "行权价超出 ±10% 应被拒");
        assertEquals(0, ctx.options.of(account.uuid).size(), "被拒后不应产生持仓");
    }
}
