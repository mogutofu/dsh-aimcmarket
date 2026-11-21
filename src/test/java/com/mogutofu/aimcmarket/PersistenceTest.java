package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.engine.CompanyEngine;
import com.mogutofu.aimcmarket.engine.FinanceService;
import com.mogutofu.aimcmarket.engine.FuturesEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 持久化/对账的单元测试：{@code saveAll()} 后重新 load 数据还在、账本平衡、重放核对一致；
 * 坏 JSON 文件不能让服务器启动失败。
 */
class PersistenceTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String TEST_UUID = "00000000-0000-0000-0000-000000000005";

    @TempDir
    Path tempDir;

    private AimcContext load() {
        return AimcContext.load(tempDir, tempDir.resolve("config"), MarketSettings.defaults(), NOW_MS, SEED);
    }

    @Test
    void saveAndReloadKeepsAccountsCompaniesHoldingsAndLedger() {
        AimcContext ctx = load();
        Account account = ctx.accounts.getOrCreate(TEST_UUID, "Tester", NOW_MS,
                ctx.settings.startingBalanceCents, true);
        ctx.ledger.issue(account, 2_000_000L, "test.fund", "Tester", "测试注资");

        assertTrue(CompanyEngine.create(ctx, account, "持久化矿业", "mining").ok, "创建公司应该成功");
        CompanyStore.Company company = ctx.companies.byCeo(account.uuid).get(0);
        assertNotNull(company, "创建后应能找到公司");
        assertTrue(FinanceService.buyStock(ctx, account, "BLK", 5L).ok, "买 5 股 BLK 应该成功");
        assertTrue(FuturesEngine.open(ctx, account, "IRON", true, 1L, 7).ok, "开 1 手 IRON 期货应该成功");

        long wallet = account.balanceCents();
        long companyCash = company.cashCents;
        long shares = account.stockShares("BLK");
        long companyValuation = company.valuationCents;

        ctx.saveAll();

        AimcContext reloaded = AimcContext.load(tempDir, tempDir.resolve("config"),
                MarketSettings.defaults(), NOW_MS + 5_000L, 7L);

        Account again = reloaded.accounts.get(TEST_UUID);
        assertNotNull(again, "重新加载后账户应还在");
        assertEquals(wallet, again.balanceCents(), "重新加载后钱包余额应一致");
        assertEquals(shares, again.stockShares("BLK"), "重新加载后股票持仓应一致");

        CompanyStore.Company reloadedCompany = reloaded.companies.get(company.id);
        assertNotNull(reloadedCompany, "重新加载后公司应还在");
        assertEquals(companyCash, reloadedCompany.cashCents, "重新加载后公司现金应一致");
        assertEquals(companyValuation, reloadedCompany.valuationCents, "重新加载后公司估值应一致");
        assertEquals(company.name, reloadedCompany.name, "重新加载后公司名应一致");

        assertEquals(1, reloaded.futures.of(TEST_UUID).size(), "重新加载后期货持仓应还在");
        assertEquals(1, reloaded.accounts.all().size(), "重新加载后账户数量应一致");

        assertTrue(reloaded.ledger.isBalanced(), "重新加载后账本应平衡（流通量 + 净发行 = 0）");
        Ledger.ReplayResult replay = reloaded.reconcile();
        assertTrue(replay.ok(), "重新加载后重放核对应一致：" + replay.detail);
        assertEquals(0, replay.badLines, "ledger.jsonl 不应有坏行");
    }

    @Test
    void npcStockPriceAndStateSurviveReload() {
        AimcContext ctx = load();
        assertEquals(10, ctx.stocks.all().size(), "默认应该有 10 家 NPC 上市公司");
        com.mogutofu.aimcmarket.data.StockStore.Stock blk = ctx.stocks.stock("BLK");
        assertNotNull(blk, "BLK 应存在");
        blk.priceCents = 13_300L;
        blk.lastDividendDay = 3L;
        blk.lastOutlook = "up";
        ctx.saveAll();

        AimcContext reloaded = AimcContext.load(tempDir, tempDir.resolve("config"),
                MarketSettings.defaults(), NOW_MS + 5_000L, 7L);
        com.mogutofu.aimcmarket.data.StockStore.Stock again = reloaded.stocks.stock("BLK");
        assertNotNull(again, "重新加载后 BLK 应存在");
        assertEquals(13_300L, again.priceCents, "重启后股价应保留，不能被重置成初始价");
        assertEquals(3L, again.lastDividendDay, "重启后分红日应保留");
        assertEquals("up", again.lastOutlook, "重启后财报展望应保留");
        assertEquals(10, reloaded.stocks.all().size(), "重新加载后仍是 10 家 NPC 公司");
    }

    @Test
    void corruptAccountsFileLoadsAsEmptyInsteadOfCrashing() throws Exception {
        Path dataDir = tempDir.resolve("world").resolve("aimcmarket");
        Files.createDirectories(dataDir);
        Path accountsFile = dataDir.resolve("accounts.json");
        Files.writeString(accountsFile, "{\"accounts\": {\"broken\": ", StandardCharsets.UTF_8);

        AimcContext ctx = assertDoesNotThrow(
                () -> AimcContext.load(tempDir, tempDir.resolve("config"),
                        MarketSettings.defaults(), NOW_MS, SEED),
                "坏 accounts.json 不应让 AimcContext.load 抛异常");

        assertTrue(ctx.accounts.all().isEmpty(), "坏账户文件应被当成空账户处理，而不是崩");
        assertTrue(ctx.ledger.isBalanced(), "空账户加载后账本应平衡");

        boolean hasBackup;
        try (var files = Files.list(dataDir)) {
            hasBackup = files.anyMatch(path ->
                    path.getFileName().toString().startsWith("accounts.json.broken-"));
        }
        assertTrue(hasBackup, "坏文件应该被备份成 accounts.json.broken-* 而不是直接丢弃");
    }
}
