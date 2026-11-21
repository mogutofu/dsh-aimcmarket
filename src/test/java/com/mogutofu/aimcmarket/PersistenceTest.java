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
    void dynamicCatalogItemSurvivesReloadAndCanBeTraded() {
        AimcContext ctx = load();
        Account account = ctx.accounts.getOrCreate(TEST_UUID, "Tester", NOW_MS,
                ctx.settings.startingBalanceCents, true);
        ctx.ledger.issue(account, 1_000_000L, "test.fund", "Tester", "测试注资");
        // 模拟 AI 估价后自动上市：齿轮（目录原本没有）
        com.mogutofu.aimcmarket.data.MarketStore.CatalogItem item = ctx.market.addItem(
                "create:cogwheel", "齿轮", java.util.List.of("cogwheel"), 150L, 0.05, 12_000L);
        assertNotNull(item, "新商品应该创建成功");
        assertTrue(ctx.market.hasItem("create:cogwheel"), "目录里应有新商品");
        long balance = account.balanceCents();
        assertTrue(com.mogutofu.aimcmarket.engine.TradeService.buy(ctx, account, "create:cogwheel", 64).ok,
                "动态上市的商品应该能买");
        assertTrue(account.balanceCents() < balance, "买入后应扣钱");
        assertTrue(ctx.market.price("create:cogwheel") > 0L, "动态商品应有价格");
        ctx.saveAll();

        AimcContext reloaded = AimcContext.load(tempDir, tempDir.resolve("config"),
                MarketSettings.defaults(), NOW_MS + 5_000L, 7L);
        assertTrue(reloaded.market.hasItem("create:cogwheel"), "重启后动态商品应还在目录里");
        assertEquals(150L, reloaded.market.item("create:cogwheel").basePriceCents, "重启后基准价应一致");
        assertTrue(reloaded.ledger.isBalanced(), "动态商品交易后账本应平衡");
    }

    @Test
    void purchaseRecordsSupportReturnAndPersistence() {
        AimcContext ctx = load();
        Account account = ctx.accounts.getOrCreate(TEST_UUID, "Tester", NOW_MS,
                ctx.settings.startingBalanceCents, true);
        ctx.market.addItem("kubejs:test_bomb", "测试音爆弹", java.util.List.of("test bomb"),
                9_000L, 0.05, 12_000L);
        ctx.purchases.record(account.uuid, "kubejs:test_bomb", 3, 9_000L, NOW_MS);
        assertEquals(3, ctx.purchases.returnable(account.uuid, "kubejs:test_bomb", NOW_MS + 1_000L),
                "7 天内应可退 3 个");
        ctx.saveAll();

        AimcContext reloaded = AimcContext.load(tempDir, tempDir.resolve("config"),
                MarketSettings.defaults(), NOW_MS + 5_000L, 7L);
        assertEquals(3, reloaded.purchases.returnable(account.uuid, "kubejs:test_bomb", NOW_MS + 5_000L),
                "重启后买入记录应还在");
        long refund = reloaded.purchases.refund(account.uuid, "kubejs:test_bomb", 2, NOW_MS + 5_000L);
        assertEquals(18_000L, refund, "退 2 个应按原价 90 币/个 退 180 币");
        assertEquals(1, reloaded.purchases.returnable(account.uuid, "kubejs:test_bomb", NOW_MS + 5_000L),
                "退完 2 个后还剩 1 个可退");
        assertEquals(0, reloaded.purchases.returnable(account.uuid, "kubejs:test_bomb",
                        NOW_MS + com.mogutofu.aimcmarket.data.PurchaseStore.RETURN_WINDOW_MS + 10_000L),
                "超过 7 天不能再退");
    }

    @Test
    void buybackAtCustomPriceAddsMoneyAndKeepsLedgerBalanced() {
        AimcContext ctx = load();
        Account account = ctx.accounts.getOrCreate(TEST_UUID, "Tester", NOW_MS,
                ctx.settings.startingBalanceCents, true);
        ctx.market.addItem("kubejs:test_bomb", "测试音爆弹", java.util.List.of("test bomb"),
                9_000L, 0.05, 12_000L);
        long before = account.balanceCents();
        long unit = 12_340L;
        com.mogutofu.aimcmarket.engine.ActionResult result =
                com.mogutofu.aimcmarket.engine.TradeService.sellWithPrice(ctx, account,
                        "kubejs:test_bomb", 2, unit, "收购", "trade.buyback");
        assertTrue(result.ok, "按指定单价收购应该成功：" + result.message);
        assertEquals(before + unit * 2, account.balanceCents(), "收购款应按指定单价入账");
        assertTrue(ctx.ledger.isBalanced(), "收购后账本应平衡");
    }

    @Test
    void realStockSyncPersistsAndSkipsRandomWalk() {
        AimcContext ctx = load();
        com.mogutofu.aimcmarket.data.StockStore.Stock stock = ctx.stocks.ensureRealStock(
                "AAPL", "苹果", "科技", "usAAPL", "US", 10_000L);
        assertNotNull(stock, "应该能新增现实股票");
        ctx.stocks.applyRealQuote("AAPL", 34_256L, -0.02, NOW_MS);
        assertEquals(34_256L, ctx.stocks.stock("AAPL").priceCents, "股价应被现实行情覆盖");
        assertEquals(34_256L, ctx.stocks.stock("AAPL").initialPriceCents, "首次同步应把初始价设为现实价");
        com.mogutofu.aimcmarket.engine.PriceEngine.tickStocks(ctx);
        assertEquals(34_256L, ctx.stocks.stock("AAPL").priceCents, "现实股票不参与游戏内随机波动");
        ctx.saveAll();

        AimcContext reloaded = AimcContext.load(tempDir, tempDir.resolve("config"),
                MarketSettings.defaults(), NOW_MS + 5_000L, 7L);
        com.mogutofu.aimcmarket.data.StockStore.Stock again = reloaded.stocks.stock("AAPL");
        assertNotNull(again, "重启后现实股票应还在");
        assertEquals("usAAPL", again.realSymbol, "重启后现实代码应保留");
        assertEquals(34_256L, again.priceCents, "重启后同步价应保留");
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
