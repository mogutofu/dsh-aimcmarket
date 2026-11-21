package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.FundStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.FinanceService;
import com.mogutofu.aimcmarket.util.Cents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FinanceService} 的单元测试：股票手续费/交易税/持仓、NPC 分红、基金申赎与净值守恒、银行存款/定期/贷款。
 */
class FinanceTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String TEST_UUID = "00000000-0000-0000-0000-000000000002";

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
    // 股票
    // ══════════════════════════════════════════════════════════════

    @Test
    void stockBuyAndSellChargeFeeAndTax() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 3_000_000L);
        StockStore.Stock stock = ctx.stocks.stock("BLK");
        assertNotNull(stock, "BLK 应存在");

        long price = stock.priceCents;
        long gross = price * 10L;
        long fee = FinanceService.stockFee(gross);
        long tax = Cents.percent(gross, ctx.government.effectiveTradeTax());
        assertEquals(Cents.COIN, FinanceService.stockFee(0L), "手续费下限应为 1 币");

        long balanceBefore = account.balanceCents();
        long budgetBefore = ctx.government.budgetCents();
        long makerBefore = ctx.market.makerCents();
        long feePoolBefore = ctx.market.feePoolCents();

        ActionResult buy = FinanceService.buyStock(ctx, account, "BLK", 10L);
        assertTrue(buy.ok, "买入股票应该成功：" + buy.message);
        assertEquals(balanceBefore - (gross + fee + tax), account.balanceCents(),
                "买入应扣成交额 + 手续费 + 交易税");
        assertEquals(10L, account.stockShares("BLK"), "持仓应为 10 股");
        assertEquals(budgetBefore + tax, ctx.government.budgetCents(), "交易税应进政府预算");
        assertEquals(feePoolBefore + fee, ctx.market.feePoolCents(), "手续费应进手续费池");
        assertEquals(makerBefore + gross, ctx.market.makerCents(), "成交额应进做市商账户");
        assertTrue(ctx.ledger.isBalanced(), "股票买入后账本应平衡");

        long balanceAfterBuy = account.balanceCents();
        long budgetAfterBuy = ctx.government.budgetCents();
        ActionResult sell = FinanceService.sellStock(ctx, account, "BLK", 10L);
        assertTrue(sell.ok, "卖出股票应该成功：" + sell.message);
        assertEquals(balanceAfterBuy + (gross - fee - tax), account.balanceCents(),
                "卖出应到账成交额 − 手续费 − 交易税");
        assertEquals(0L, account.stockShares("BLK"), "全部卖出后持仓应为 0");
        assertEquals(budgetAfterBuy + tax, ctx.government.budgetCents(), "卖出交易税应进预算");
        assertTrue(ctx.ledger.isBalanced(), "股票卖出后账本应平衡");
    }

    @Test
    void stockTradesRejectBadInput() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 3_000_000L);

        assertFalse(FinanceService.buyStock(ctx, account, "NO_SUCH_CODE", 1L).ok, "不存在的股票应被拒");
        assertFalse(FinanceService.buyStock(ctx, account, "BLK", 0L).ok, "股数 0 应被拒");
        assertFalse(FinanceService.buyStock(ctx, account, "BLK", -5L).ok, "负数股数应被拒");
        assertFalse(FinanceService.sellStock(ctx, account, "BLK", 1L).ok, "没有持仓时卖出应被拒");
    }

    @Test
    void npcStockDividendIsIssuedToHolder() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        give(ctx, account, 5_000_000L);

        ActionResult buy = FinanceService.buyStock(ctx, account, "BLK", 100L);
        assertTrue(buy.ok, "买入 100 股 BLK 应该成功：" + buy.message);
        Account.StockHolding holding = account.stocks.get("BLK");
        assertNotNull(holding, "买入后应有持仓记录");
        // 持股满 1 个交易日才分红：把 sinceTick 设得很早。
        holding.sinceTick = ctx.tick() - 1000L;

        StockStore.Stock stock = ctx.stocks.stock("BLK");
        stock.lastDividendDay = -100L;
        long perShare = Math.round(stock.priceCents * stock.annualDividendRate * 7.0 / 365.0);
        assertTrue(perShare > 0L, "BLK 的年化股息率应能算出正的每股分红，实际 " + perShare);

        long balanceBefore = account.balanceCents();
        FinanceService.payDividends(ctx);
        assertEquals(balanceBefore + perShare * 100L, account.balanceCents(),
                "分红应等于每股分红 × 持股数");
        assertEquals(ctx.tradingDay(), stock.lastDividendDay, "分红后应记录 lastDividendDay");
        assertTrue(ctx.ledger.isBalanced(), "分红后账本应平衡");
    }

    // ══════════════════════════════════════════════════════════════
    // 基金
    // ══════════════════════════════════════════════════════════════

    @Test
    void fundSubscribeAndRedeemKeepNavIdentity() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        FundStore.Fund fund = ctx.funds.fund("FND01");
        assertNotNull(fund, "FND01 应存在");

        long balanceBefore = account.balanceCents();
        long cashBefore = fund.cashCents;
        ActionResult buy = FinanceService.buyFund(ctx, account, "FND01", 10_000L);
        assertTrue(buy.ok, "基金申购应该成功：" + buy.message);
        assertEquals(1, ctx.funds.pending().size(), "申购应先排队等下一个 tick 成交");
        assertEquals(balanceBefore, account.balanceCents(), "申购提交时不扣款");

        long tick = ctx.tick();
        ctx.funds.onTick(tick + 1L, ctx.tradingDay(), FinanceService.ticksPerDay(ctx), 0.0,
                ctx.accounts, ctx.ledger, ctx.settings, ctx.random);
        assertTrue(ctx.funds.pending().isEmpty(), "成交后不应再有待成交单");

        long units = account.fundUnitsMilli("FND01");
        assertTrue(units > 0L, "成交后应持有基金份额，实际 " + units);
        assertTrue(fund.cashCents > cashBefore, "基金现金应增加，实际 " + fund.cashCents);
        assertEquals(balanceBefore - 10_000L, account.balanceCents(), "成交时才从钱包扣钱");
        long value = ctx.funds.valueOfUnits("FND01", units);
        assertTrue(Math.abs(value - fund.cashCents) <= 1L,
                "净值 × 份额应等于基金现金（误差 ≤ 1 分，实际差 " + (value - fund.cashCents) + " 分）");

        ActionResult sell = FinanceService.sellFund(ctx, account, "FND01", units);
        assertTrue(sell.ok, "基金赎回应该成功：" + sell.message);
        ctx.funds.onTick(tick + 2L, ctx.tradingDay(), FinanceService.ticksPerDay(ctx), 0.0,
                ctx.accounts, ctx.ledger, ctx.settings, ctx.random);
        assertEquals(0L, account.fundUnitsMilli("FND01"), "赎回后份额应归零");
        assertTrue(ctx.ledger.isBalanced(), "基金申赎后账本应平衡");
    }

    @Test
    void fundRejectsBadInput() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        assertFalse(FinanceService.buyFund(ctx, account, "FND99", 10_000L).ok, "不存在的基金应被拒");
        assertFalse(FinanceService.buyFund(ctx, account, "FND01", 0L).ok, "申购金额 0 应被拒");
        assertFalse(FinanceService.buyFund(ctx, account, "FND01", -1L).ok, "负申购金额应被拒");
        assertFalse(FinanceService.sellFund(ctx, account, "FND01", 1000L).ok, "没有份额时赎回应被拒");
    }

    // ══════════════════════════════════════════════════════════════
    // 银行
    // ══════════════════════════════════════════════════════════════

    @Test
    void currentAccountDepositWithdrawAndInterest() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        long walletBefore = account.balanceCents();

        ActionResult deposit = FinanceService.deposit(ctx, account, 20_000L);
        assertTrue(deposit.ok, "活期存入应该成功：" + deposit.message);
        assertEquals(walletBefore - 20_000L, account.balanceCents(), "存入应从钱包扣钱");
        assertEquals(20_000L, account.bank.currentCents, "活期余额应增加");

        // 计息：按分钟累计，不能把本金算少。
        FinanceService.accrueInterest(ctx, 60L);
        assertTrue(account.bank.currentCents >= 20_000L,
                "活期计息不应减少本金，实际 " + account.bank.currentCents);

        ActionResult withdraw = FinanceService.withdraw(ctx, account, 5_000L);
        assertTrue(withdraw.ok, "活期取出应该成功：" + withdraw.message);
        assertEquals(15_000L, account.bank.currentCents, "取出后活期余额应为 15000 分");
        assertEquals(walletBefore - 15_000L, account.balanceCents(), "取出后钱包余额应正确");
        assertTrue(ctx.ledger.isBalanced(), "银行存取后账本应平衡");
    }

    @Test
    void termDepositMaturesBackToCurrentAccount() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        assertTrue(FinanceService.deposit(ctx, account, 30_000L).ok, "先存 300 币到活期");
        long currentBeforeTerm = account.bank.currentCents;
        assertEquals(30_000L, currentBeforeTerm, "活期余额应为 30000 分");

        ActionResult termResult = FinanceService.termDeposit(ctx, account, 7, 10_000L);
        assertTrue(termResult.ok, "开 7 交易日定期应该成功：" + termResult.message);
        assertEquals(1, account.bank.terms.size(), "应有 1 笔定期");
        assertEquals(currentBeforeTerm - 10_000L, account.bank.currentCents, "开定期应从活期划走本金");

        Account.TermDeposit term = account.bank.terms.get(0);
        long expectedInterest = Cents.percent(term.amountCents, term.apr * 7 / 365.0);
        assertEquals(0L, term.startDay, "定期起始交易日应为 0");
        assertEquals(7L, term.endDay, "7 交易日定期应在第 7 个交易日到期");

        // 推进到到期日
        ctx.clock.resetForTest(NOW_MS + 7L * ctx.clock.dayMillis(), 84L, term.endDay);
        FinanceService.onTradingDay(ctx);
        assertTrue(account.bank.terms.isEmpty(), "到期后定期应消失");
        assertEquals(currentBeforeTerm + expectedInterest, account.bank.currentCents,
                "到期后本金 + 利息应回活期");
        assertTrue(ctx.ledger.isBalanced(), "定期到期后账本应平衡");
    }

    @Test
    void loanIssueRepayAndCreditScoreRange() {
        AimcContext ctx = newContext();
        Account account = newAccount(ctx);
        // 贷款额度按"30 天平均日收入 ×3"估，先给他一笔收入记录。
        account.totalIncomeCents = 1_000_000L;
        long walletBefore = account.balanceCents();

        ActionResult take = FinanceService.takeLoan(ctx, account, 100_000L);
        assertTrue(take.ok, "贷款应该发放成功：" + take.message);
        assertEquals(walletBefore + 100_000L, account.balanceCents(), "贷款本金应进入钱包");
        assertEquals(100_000L, account.bank.totalLoanCents(), "未还贷款应为 100000 分");
        assertCreditInRange(account, ctx.settings);

        ActionResult repay = FinanceService.repayLoan(ctx, account, 40_000L);
        assertTrue(repay.ok, "部分还款应该成功：" + repay.message);
        assertEquals(60_000L, account.bank.totalLoanCents(), "部分还款后应剩 60000 分");
        assertCreditInRange(account, ctx.settings);

        ActionResult repayAll = FinanceService.repayLoan(ctx, account, 60_000L);
        assertTrue(repayAll.ok, "结清贷款应该成功：" + repayAll.message);
        assertTrue(account.bank.loans.isEmpty(), "结清后不应有未还贷款");
        assertCreditInRange(account, ctx.settings);
        assertTrue(ctx.ledger.isBalanced(), "贷款发放/还款后账本应平衡");
    }

    private static void assertCreditInRange(Account account, MarketSettings settings) {
        assertTrue(account.creditScore >= settings.creditMin && account.creditScore <= settings.creditMax,
                "信用分应夹在 [" + settings.creditMin + ", " + settings.creditMax + "]，实际 " + account.creditScore);
    }
}
