package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.CompanyEngine;
import com.mogutofu.aimcmarket.engine.IpoEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CompanyEngine} 玩家公司与 {@link IpoEngine} IPO 流程的单元测试。
 *
 * <p>"天"全部是交易日（trading day），不是现实天。</p>
 */
class CompanyIpoTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String CEO_UUID = "00000000-0000-0000-0000-000000000003";

    @TempDir
    Path tempDir;

    private AimcContext newContext() {
        return AimcContext.load(tempDir, tempDir.resolve("config"), MarketSettings.defaults(), NOW_MS, SEED);
    }

    private Account newCeo(AimcContext ctx) {
        return ctx.accounts.getOrCreate(CEO_UUID, "Ceo", NOW_MS, ctx.settings.startingBalanceCents, true);
    }

    private static void give(AimcContext ctx, Account account, long cents) {
        ctx.ledger.issue(account, cents, "test.fund", "Tester", "测试注资");
    }

    /** 给公司账户注资（公司也是 BalanceAccount，只能走账本）。 */
    private static void giveCompany(AimcContext ctx, CompanyStore.Company company, long cents) {
        ctx.ledger.issue(company, cents, "test.companyFund", "Tester", "公司注资");
    }

    private CompanyStore.Company createCompany(AimcContext ctx, Account ceo, String name) {
        ActionResult result = CompanyEngine.create(ctx, ceo, name, "mining");
        assertTrue(result.ok, "创建公司应该成功：" + result.message);
        CompanyStore.Company company = ctx.companies.findByName(name);
        assertNotNull(company, "创建后应能按名字找到公司：" + name);
        return company;
    }

    // ══════════════════════════════════════════════════════════════
    // 公司基础
    // ══════════════════════════════════════════════════════════════

    @Test
    void createCompanyChargesFeeAndGivesCeoAllShares() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        long before = ceo.balanceCents();

        ActionResult result = CompanyEngine.create(ctx, ceo, "铜矿工坊", "mining");
        assertTrue(result.ok, "创建公司应该成功：" + result.message);
        assertEquals(before - ctx.settings.companyCreateFeeCents, ceo.balanceCents(),
                "创建公司应恰好扣掉创建费 " + ctx.settings.companyCreateFeeCents + " 分");
        assertEquals(1, ctx.companies.byCeo(ceo.uuid).size(), "CEO 名下应有一家公司");

        CompanyStore.Company company = ctx.companies.byCeo(ceo.uuid).get(0);
        assertEquals(company.sharesTotal, company.sharesOf(ceo.uuid), "CEO 应持有 100% 股份");
        assertEquals(ctx.settings.companySharesTotal, company.sharesTotal, "初始总股本应取配置值");
        assertEquals("mining", company.sector, "行业应为 mining");
        assertEquals(ctx.tradingDay(), company.createdDay, "应记录创建交易日");
        assertFalse(company.listed, "新公司不应处于上市状态");
        assertTrue(ctx.ledger.isBalanced(), "创建公司后账本应平衡");

        // 非法行业/名字要被拒
        assertFalse(CompanyEngine.create(ctx, ceo, "另一个公司", "不存在的行业").ok, "非法行业应被拒");
        assertFalse(CompanyEngine.create(ctx, ceo, "A", "mining").ok, "公司名太短应被拒");
    }

    @Test
    void hireRespectsEmployeeCap() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "招聘测试公司");
        ctx.companies.deposit(ceo, company, 300_000L);

        int cap = company.maxEmployees();
        assertEquals(8, cap, "1 级公司的员工上限应为 5+3×1=8");
        ActionResult hire = CompanyEngine.hire(ctx, ceo, company, cap);
        assertTrue(hire.ok, "在上限内招聘应该成功：" + hire.message);
        assertEquals(cap, company.employees, "员工数应等于招聘人数");

        ActionResult over = CompanyEngine.hire(ctx, ceo, company, 1);
        assertFalse(over.ok, "超过员工上限应被拒");
        assertEquals(cap, company.employees, "被拒后员工数不应变化");
        assertTrue(ctx.ledger.isBalanced(), "招聘后账本应平衡");
    }

    @Test
    void dailySettleRecordsRevenueProfitAndValuation() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "日结测试公司");

        long profit = ctx.companies.settleDaily(company, ctx.tradingDay(), 1.0, ctx.settings, ctx.random,
                ctx.government.effectiveCompanyTax());
        assertEquals(1, company.revenueHistory.size(), "日结应记录一条营收历史");
        assertEquals(1, company.profitHistory.size(), "日结应记录一条利润历史");
        assertTrue(company.revenueHistory.get(0) > 0L,
                "采矿公司日营收应为正，实际 " + company.revenueHistory.get(0));
        assertEquals(profit, company.profitHistory.get(0).longValue(), "利润历史应等于日结返回值");
        assertEquals(ctx.tradingDay(), company.lastSettleDay, "应记录结算交易日");
        assertTrue(company.cumulativeRevenueCents > 0L, "累计营收应增加");
        assertTrue(company.valuationCents >= 500_000L,
                "估值不应低于 5000 币下限，实际 " + company.valuationCents);
        assertTrue(ctx.ledger.isBalanced(), "日结后账本应平衡");
    }

    @Test
    void valuationHasLowerBound() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "空壳估值公司");

        ctx.companies.updateValuation(company);
        assertEquals(500_000L, company.valuationCents, "没有利润的公司估值应取 5000 币下限");
    }

    @Test
    void levelUpOnlyNeedsCash() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "升级测试公司");

        // 公司现金不够升级费 → 拒绝
        assertFalse(CompanyEngine.levelUp(ctx, ceo, company).ok, "公司现金不足升级费时不应能升级");
        assertEquals(1, company.level, "被拒后等级应保持 1");

        // 不看员工数/累计营收，只要公司现金够升级费就能升
        company.cumulativeRevenueCents = 0L;
        company.employees = 0;
        giveCompany(ctx, company, CompanyStore.LEVEL_UP_FEE[1]);
        ActionResult up = CompanyEngine.levelUp(ctx, ceo, company);
        assertTrue(up.ok, "交够升级费就应该能升级（不看员工/营收）：" + up.message);
        assertEquals(2, company.level, "升级后等级应为 2");
        assertTrue(ctx.ledger.isBalanced(), "升级后账本应平衡");
    }

    @Test
    void investorCanFundCompanyForShares() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "融资测试公司");
        Account partner = ctx.accounts.getOrCreate("00000000-0000-0000-0000-0000000000aa",
                "Partner", NOW_MS, ctx.settings.startingBalanceCents, true);
        give(ctx, partner, 2_000_000L);
        long cashBefore = company.cashCents;
        long balanceBefore = partner.balanceCents();

        ActionResult invest = CompanyEngine.invest(ctx, partner, company, 100_000L);
        assertTrue(invest.ok, "合伙人投资应该成功：" + invest.message);
        assertEquals(cashBefore + 100_000L, company.cashCents, "投资款应进公司现金");
        assertEquals(balanceBefore - 100_000L, partner.balanceCents(), "投资人应扣钱");
        assertTrue(company.sharesOf(partner.uuid) > 0L, "投资人应拿到股份");
        assertTrue(company.sharesOf(ceo.uuid) > company.sharesOf(partner.uuid), "CEO 仍应是大股东");
        assertTrue(ctx.ledger.isBalanced(), "投资后账本应平衡");

        // 单次想拿走近半股份 → 拒绝
        assertFalse(CompanyEngine.invest(ctx, partner, company, 100_000_000L).ok, "单次投资最多拿 49% 股份");
    }

    @Test
    void negativeCashAndThreeLosingDaysMarksBankrupt() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "破产测试公司");
        ctx.companies.deposit(ceo, company, 100_000L);
        assertTrue(CompanyEngine.hire(ctx, ceo, company, 4).ok, "先招 4 个员工制造工资成本");

        // 清空公司现金并做成负数（公司账户允许为负），这样工资发不出来、利润为负。
        long cash = company.cashCents;
        if (cash > 0L) {
            ctx.ledger.burn(company, cash, "test.burn", "Tester", "清空公司现金");
        }
        ctx.ledger.burn(company, 1_000L, "test.burn", "Tester", "制造负现金");
        assertEquals(-1_000L, company.cashCents, "公司现金应为 -1000 分");

        for (long day = 1L; day <= 3L; day++) {
            long profit = ctx.companies.settleDaily(company, day, 0.0, ctx.settings, ctx.random,
                    ctx.government.effectiveCompanyTax());
            assertTrue(profit < 0L, "行业景气 0 + 有员工时当天利润应为负，实际 " + profit);
        }

        assertTrue(company.bankrupt, "现金为负且连续 3 个交易日亏损应标记破产");
        assertEquals(0, company.employees, "破产后员工应全部清退");
        assertFalse(company.listed, "破产公司不应处于上市状态");
        assertTrue(ctx.companies.bankruptHistory().contains(company.name),
                "破产历史里应记录公司名 " + company.name);
        assertTrue(ctx.ledger.isBalanced(), "破产清算后账本应平衡");
    }

    // ══════════════════════════════════════════════════════════════
    // IPO
    // ══════════════════════════════════════════════════════════════

    private CompanyStore.Company prepareIpoReadyCompany(AimcContext ctx, Account ceo) {
        CompanyStore.Company company = createCompany(ctx, ceo, "上市测试公司");
        company.level = IpoEngine.MIN_LEVEL;
        company.createdDay = ctx.tradingDay() - IpoEngine.MIN_TRADING_DAYS;
        company.valuationCents = 6_000_000L;
        company.profitHistory.clear();
        for (int i = 0; i < (int) IpoEngine.MIN_TRADING_DAYS; i++) {
            company.profitHistory.add(100_000L);
        }
        // 承销费 = max(100 币, 估值×3%) = 1800 币
        giveCompany(ctx, company, 1_000_000L);
        return company;
    }

    @Test
    void ipoApplyConfirmListAndDelist() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = prepareIpoReadyCompany(ctx, ceo);

        StringBuilder whyNot = new StringBuilder();
        assertTrue(IpoEngine.canApply(ctx, company, whyNot), "满足条件却无法申请 IPO：" + whyNot);
        long oldShares = company.sharesTotal;
        long estimated = IpoEngine.estimateIpoPrice(company, 0.25);
        assertTrue(estimated > 0L, "发行价预估应为正，实际 " + estimated);

        ActionResult apply = IpoEngine.apply(ctx, ceo, 0.25, null);
        assertTrue(apply.ok, "IPO 申请应该成功：" + apply.message);
        assertTrue(company.ipoPending, "申请后应处于待确认状态");
        assertFalse(apply.broadcast.isEmpty(), "IPO 受理应有全服公告");
        long applyDay = company.ipoApplyDay;
        assertEquals(applyDay, ctx.tradingDay(), "ipoApplyDay 应等于申请当天");

        // 推进 1 个交易日
        ctx.clock.resetForTest(ctx.nowMs() + ctx.clock.dayMillis(), 12L, applyDay + 1L);
        ActionResult confirm = IpoEngine.confirm(ctx, ceo);
        assertTrue(confirm.ok, "IPO 确认应该成功：" + confirm.message);
        assertTrue(company.listed, "确认后公司应上市");
        assertNotNull(company.code, "上市后应有股票代码");
        assertTrue(company.code.startsWith("P"), "玩家公司股票代码应以 P 开头，实际 " + company.code);
        assertTrue(company.sharesTotal > oldShares,
                "上市应稀释扩大总股本：" + oldShares + " → " + company.sharesTotal);
        assertEquals(ctx.tradingDay() + IpoEngine.LOCK_DAYS, company.lockUntilDay,
                "CEO 股份应锁定 " + IpoEngine.LOCK_DAYS + " 个交易日");

        String code = company.code;
        StockStore.Stock stock = ctx.stocks.stock(code);
        assertNotNull(stock, "交易所里应有上市股票 " + code);
        assertFalse(stock.npc, "玩家公司股票不应标记为 NPC");
        assertEquals(ctx.tradingDay(), stock.listedDay, "上市日应记录");
        assertTrue(stock.priceCents > 0L, "上市首日股价应为正");

        // 退市：股价 < 1 币连续 7 个交易日
        stock.priceCents = IpoEngine.LOW_PRICE_CENTS - 1L;
        stock.lowPriceDays = IpoEngine.LOW_PRICE_DELIST_DAYS - 1;
        IpoEngine.onTradingDay(ctx);
        assertFalse(company.listed, "股价连续 7 个交易日低于 1 币应退市");
        assertNull(ctx.stocks.stock(code), "退市后股票应从交易所移除");
        assertTrue(ctx.ledger.isBalanced(), "退市后账本应平衡");
    }

    @Test
    void ipoApplyRejectsUnqualifiedCompany() {
        AimcContext ctx = newContext();
        Account ceo = newCeo(ctx);
        give(ctx, ceo, 2_000_000L);
        CompanyStore.Company company = createCompany(ctx, ceo, "不够格公司");

        StringBuilder whyNot = new StringBuilder();
        assertFalse(IpoEngine.canApply(ctx, company, whyNot), "1 级新成立公司不应满足上市条件");
        assertFalse(whyNot.toString().isEmpty(), "不满足条件时应写明原因");
        assertFalse(IpoEngine.apply(ctx, ceo, 0.25, null).ok, "不满足条件时 IPO 申请应被拒");
        assertFalse(company.ipoPending, "被拒后不应进入待确认状态");
        assertFalse(IpoEngine.apply(ctx, ceo, 0.99, null).ok, "非法流通比例应被拒");
    }
}
