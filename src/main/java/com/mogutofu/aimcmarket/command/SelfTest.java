package com.mogutofu.aimcmarket.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mogutofu.aimcmarket.ai.AiJson;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.FuturesStore;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.data.OptionsStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.CompanyEngine;
import com.mogutofu.aimcmarket.engine.FinanceService;
import com.mogutofu.aimcmarket.engine.FuturesEngine;
import com.mogutofu.aimcmarket.engine.GovernmentEngine;
import com.mogutofu.aimcmarket.engine.IpoEngine;
import com.mogutofu.aimcmarket.engine.OptionsEngine;
import com.mogutofu.aimcmarket.engine.PriceEngine;
import com.mogutofu.aimcmarket.engine.TradeService;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ex admin selftest}：覆盖全部模块的自检。
 *
 * <p>会在系统临时目录里新建一个独立的数据环境跑，**不动线上数据**。
 * 每一项独立 try/catch，最后打印 {@code SELFTEST PASS} 或 {@code SELFTEST FAIL}。</p>
 */
public final class SelfTest {
    /** 自检结果。 */
    public static final class Result {
        public final int passed;
        public final int failed;
        public final List<String> lines;
        public final long elapsedMs;

        Result(int passed, int failed, List<String> lines, long elapsedMs) {
            this.passed = passed;
            this.failed = failed;
            this.lines = lines;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final String TEST_UUID = "00000000-0000-0000-0000-00000000cafe";

    private SelfTest() {
    }

    private static final class Runner {
        final AimcContext ctx;
        final Account account;
        final List<String> lines = new ArrayList<>();
        int passed;
        int failed;

        Runner(AimcContext ctx, Account account) {
            this.ctx = ctx;
            this.account = account;
        }

        void test(String name, Runnable body) {
            long start = System.currentTimeMillis();
            try {
                body.run();
                passed++;
                lines.add("  [OK] " + name + "（" + (System.currentTimeMillis() - start) + "ms）");
            } catch (Throwable error) {
                failed++;
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                lines.add("  [FAIL] " + name + "：" + message);
                com.mogutofu.aimcmarket.util.Log.warn("selftest 失败：" + name, error);
            }
        }
    }

    public static Result run(AimcContext production) {
        long start = System.currentTimeMillis();
        Path root;
        try {
            // 用数据目录下的临时子目录：服务器进程的 java.io.tmpdir 有时不可写
            // （受限账户/服务器配置），放在 world/aimcmarket 下最稳，跑完删掉。
            Path base = production == null ? null : production.dataDir;
            if (base != null) {
                try {
                    Files.createDirectories(base);
                } catch (Exception ignored) {
                    // fall through
                }
            }
            root = base == null
                    ? Files.createTempDirectory("aimcmarket-selftest-")
                    : Files.createTempDirectory(base, "selftest-");
        } catch (Exception error) {
            Result result = new Result(0, 1, List.of("[FAIL] 建立临时目录失败：" + error), 0L);
            return result;
        }
        Path serverDir = root;
        Path configDir = root.resolve("config");
        long now = 1_700_000_000_000L;
        AimcContext ctx = AimcContext.load(serverDir, configDir, MarketSettings.defaults(), now, 42L);
        current = ctx;
        Account account = ctx.accounts.getOrCreate(TEST_UUID, "Selftest", now,
                ctx.settings.startingBalanceCents, true);
        Runner runner = new Runner(ctx, account);
        runner.lines.add("AIMCmarket selftest：环境 " + root);

        // 1. 价格引擎
        runner.test("价格引擎 1000 tick 不越界、不发散", () -> {
            for (int i = 0; i < 1000; i++) {
                PriceEngine.tickPrices(ctx);
            }
            for (MarketStore.CatalogItem item : ctx.market.enabledItems()) {
                long price = ctx.market.price(item.id);
                long floor = Math.max(1L, item.basePriceCents / 4L);
                long ceiling = Math.max(floor, item.basePriceCents * 4L);
                check(price >= floor && price <= ceiling,
                        item.nameZh + " 价格越界：" + price + " 不在 [" + floor + "," + ceiling + "]");
            }
        });
        runner.test("价格引擎均值回归方向", () -> {
            MarketStore.CatalogItem item = ctx.market.enabledItems().iterator().next();
            long base = item.basePriceCents;
            ctx.market.setPrice(item.id, base * 3L);
            for (int i = 0; i < 300; i++) {
                PriceEngine.tickPrices(ctx);
            }
            check(ctx.market.price(item.id) < base * 3L * 0.99, "高价没有回归（价格 "
                    + ctx.market.price(item.id) + "）");
            ctx.market.setPrice(item.id, Math.max(1L, base / 3L));
            for (int i = 0; i < 300; i++) {
                PriceEngine.tickPrices(ctx);
            }
            check(ctx.market.price(item.id) > Math.max(1L, base / 3L) * 1.01, "低价没有回归");
            ctx.market.setPrice(item.id, base);
        });
        runner.test("价格冲击有上限且方向符合公式", () -> {
            MarketStore.CatalogItem item = ctx.market.enabledItems().iterator().next();
            ctx.market.setPrice(item.id, item.basePriceCents);
            long before = ctx.market.price(item.id);
            ctx.market.addImpact(item.id, item.liquidity * 10L);
            PriceEngine.tickPrices(ctx);
            long after = ctx.market.price(item.id);
            check(after < before, "净买入冲击应该压低价格（" + before + " → " + after + "）");
            check(after >= Math.max(1L, item.basePriceCents / 4L), "冲击后价格低于下限");
        });
        runner.test("股票价格引擎 100 tick 不越界", () -> {
            for (int i = 0; i < 100; i++) {
                PriceEngine.tickStocks(ctx);
            }
            for (StockStore.Stock stock : ctx.stocks.all()) {
                check(stock.priceCents >= Math.max(1L, stock.initialPriceCents / 10L)
                                && stock.priceCents <= stock.initialPriceCents * 10L,
                        stock.code + " 股价越界：" + stock.priceCents);
            }
            check(ctx.stocks.indexCentiPoints() > 0L, "大盘指数应该为正");
        });

        // 2. 交易
        runner.test("买入：扣钱、销毁、成交额、冲击", () -> {
            resetTradeCooldown(account);
            long beforeMoney = ctx.totalMoneyCents();
            long unit = TradeService.effectiveAskCents(ctx, "minecraft:iron_ingot");
            long balanceBefore = account.balanceCents();
            ActionResult result = TradeService.buy(ctx, account, "minecraft:iron_ingot", 3);
            check(result.ok, "买入失败：" + result.message);
            check(account.balanceCents() == balanceBefore - unit * 3L, "扣款金额不对");
            check(ctx.totalMoneyCents() == beforeMoney - unit * 3L, "买入应该销毁货币（点差回收）");
        });
        runner.test("买入：余额不足被拒", () -> {
            resetTradeCooldown(account);
            long saved = account.balanceCents();
            ctx.ledger.burn(account, saved, "selftest", "Selftest", "清空余额");
            ActionResult result = TradeService.buy(ctx, account, "minecraft:iron_ingot", 1);
            check(!result.ok, "余额为 0 还能买？");
            give(account, saved);
        });
        runner.test("买入：数量/白名单/冻结校验", () -> {
            resetTradeCooldown(account);
            check(!TradeService.buy(ctx, account, "minecraft:not_a_real_item", 1).ok, "目录外物品应该被拒");
            check(!TradeService.buy(ctx, account, "minecraft:iron_ingot", 0).ok, "数量 0 应该被拒");
            check(!TradeService.buy(ctx, account, "minecraft:iron_ingot", 577).ok, "数量 577 应该被拒");
            ctx.settings.adminFreeze = true;
            resetTradeCooldown(account);
            check(!TradeService.buy(ctx, account, "minecraft:iron_ingot", 1).ok, "冻结后还能买？");
            ctx.settings.adminFreeze = false;
        });
        runner.test("买入：重复 requestId 幂等", () -> {
            resetTradeCooldown(account);
            long before = account.balanceCents();
            String requestId = "selftest-req-" + System.nanoTime();
            ActionResult first = TradeService.buy(ctx, account, "minecraft:iron_ingot", 2, requestId);
            long afterFirst = account.balanceCents();
            ActionResult second = TradeService.buy(ctx, account, "minecraft:iron_ingot", 2, requestId);
            check(first.ok && second.ok, "幂等调用应该都成功");
            check(afterFirst == account.balanceCents(), "重复 requestId 不能重复扣钱");
            check(afterFirst < before, "第一次应该真的扣了钱");
        });
        runner.test("卖出：按回收价增发", () -> {
            resetTradeCooldown(account);
            long unit = TradeService.effectiveBidCents(ctx, "minecraft:iron_ingot");
            long before = account.balanceCents();
            ActionResult result = TradeService.sell(ctx, account, "minecraft:iron_ingot", 2);
            check(result.ok, "卖出失败：" + result.message);
            check(account.balanceCents() == before + unit * 2L, "回收金额不对");
        });

        // 3. 股票 / 基金 / 银行
        runner.test("股票：买卖、手续费与税", () -> {
            give(account, 1_500_000L);
            long before = account.balanceCents();
            long govBefore = ctx.government.budgetCents();
            ActionResult buy = FinanceService.buyStock(ctx, account, "BLK", 10);
            check(buy.ok, "买股票失败：" + buy.message);
            check(account.stockShares("BLK") == 10L, "持仓应为 10 股");
            check(account.balanceCents() < before, "买股票应该扣钱");
            check(ctx.government.budgetCents() > govBefore, "交易税应该进政府预算");
            ActionResult sell = FinanceService.sellStock(ctx, account, "BLK", 10);
            check(sell.ok, "卖股票失败：" + sell.message);
            check(account.stockShares("BLK") == 0L, "卖完持仓应为 0");
        });
        runner.test("股票：分红（持股满 1 交易日才发）", () -> {
            FinanceService.buyStock(ctx, account, "BLK", 100);
            Account.StockHolding holding = account.stocks.get("BLK");
            holding.sinceTick = ctx.tick() - 100L;
            StockStore.Stock stock = ctx.stocks.stock("BLK");
            stock.lastDividendDay = -100L;
            long before = account.balanceCents();
            FinanceService.payDividends(ctx);
            check(account.balanceCents() >= before, "分红不应该让余额变少");
            check(stock.lastDividendDay >= 0L, "分红后应该记录 lastDividendDay");
        });
        runner.test("股票：财报写入", () -> {
            JsonObject data = CompanyEngine.localReportData(ctx, "BLK");
            check(data != null && data.has("outlook"), "本地财报模板应该给出 outlook");
            CompanyEngine.applyReport(ctx, "BLK", data);
            check(ctx.stocks.latestReport("BLK") != null, "财报应该写进 StockStore");
        });
        runner.test("基金：申购/净值/份额守恒", () -> {
            long cashBefore = ctx.funds.fund("FND01").cashCents;
            ActionResult buy = FinanceService.buyFund(ctx, account, "FND01", 10_000L);
            check(buy.ok, "申购失败：" + buy.message);
            ctx.funds.onTick(ctx.tick() + 1L, ctx.tradingDay(), 12L, 0.0, ctx.accounts, ctx.ledger,
                    ctx.settings, ctx.random);
            long units = account.fundUnitsMilli("FND01");
            check(units > 0L, "申购后应该有份额");
            long fundCash = ctx.funds.fund("FND01").cashCents;
            check(fundCash > cashBefore, "基金现金应该增加");
            long value = ctx.funds.valueOfUnits("FND01", units);
            check(Math.abs(value - fundCash) <= 1L, "净值×份额应该等于基金现金（差 " + (value - fundCash) + " 分）");
        });
        runner.test("基金：赎回（不足 1 交易日有早赎费）", () -> {
            long units = account.fundUnitsMilli("FND01");
            check(units > 0L, "没有份额可赎回");
            ActionResult sell = FinanceService.sellFund(ctx, account, "FND01", units);
            check(sell.ok, "赎回失败：" + sell.message);
            ctx.funds.onTick(ctx.tick() + 2L, ctx.tradingDay(), 12L, 0.0, ctx.accounts, ctx.ledger,
                    ctx.settings, ctx.random);
            check(account.fundUnitsMilli("FND01") == 0L, "赎回后份额应为 0");
        });
        runner.test("银行：活期存取与计息", () -> {
            ActionResult deposit = FinanceService.deposit(ctx, account, 20_000L);
            check(deposit.ok, "存款失败：" + deposit.message);
            check(account.bank.currentCents >= 20_000L, "活期余额不对");
            ctx.bank.accrueCurrentInterest(ctx.accounts, 60L, ctx.government.policyRate, ctx.settings);
            check(account.bank.currentCents >= 20_000L, "活期计息不应减少本金");
            ActionResult withdraw = FinanceService.withdraw(ctx, account, 5_000L);
            check(withdraw.ok, "取款失败：" + withdraw.message);
        });
        runner.test("银行：定期到期自动回活期", () -> {
            long before = account.bank.currentCents;
            ctx.bank.openTerm(account, 10_000L, 7, 0L, ctx.government.policyRate, ctx.settings);
            check(account.bank.terms.size() == 1, "应该有一笔定期");
            ctx.bank.onTradingDay(ctx.accounts, 7L, ctx.government.policyRate, ctx.settings, ctx.nowMs());
            check(account.bank.terms.isEmpty(), "到期后定期应该消失");
            check(account.bank.currentCents > before, "到期本金和利息应该回到活期");
        });
        runner.test("银行：贷款发放与还款", () -> {
            // 额度按"累计收入/账户天数 ×3"算，测试里给一个历史收入，不然额度是 0
            account.totalIncomeCents = 3_000_000L;
            account.createdAtMs = ctx.nowMs() - 30L * Account.MILLIS_PER_DAY;
            long before = account.balanceCents();
            ctx.bank.takeLoan(account, 10_000L, ctx.tradingDay(), ctx.government.policyRate, ctx.settings, ctx.nowMs());
            check(account.balanceCents() == before + 10_000L, "贷款本金应该到账");
            check(account.bank.totalLoanCents() >= 10_000L, "应该有未还贷款");
            ctx.bank.repayLoan(account, 5_000L, ctx.settings);
            check(account.bank.totalLoanCents() < 10_000L, "还款后贷款应该变少");
        });

        // 4. 公司 / IPO

        runner.test("公司：创建（费用、股份、现金）", () -> {
            give(account, 3_000_000L);
            ActionResult create = CompanyEngine.create(ctx, account, "测试矿业", "mining");
            check(create.ok, "创建公司失败：" + create.message);
            CompanyStore.Company created = ctx.companies.byCeo(account.uuid).get(0);
            check(created != null, "创建后应能在 CEO 名下找到公司");
            check(created.holders.getOrDefault(account.uuid, 0L) == created.sharesTotal, "CEO 应该 100% 持股");


        });
        runner.test("公司：雇佣、日结、估值", () -> {
            CompanyStore.Company target = mustFindCompany();
            ctx.companies.deposit(account, target, 200_000L);
            ActionResult hire = CompanyEngine.hire(ctx, account, target, 2);
            check(hire.ok, "雇佣失败：" + hire.message);
            check(target.employees == 2, "员工数不对");
            double climate = ctx.stocks.sectorClimate(target.sector);
            ctx.companies.settleDaily(target, ctx.tradingDay(), climate, ctx.settings, ctx.random,
                    ctx.government.effectiveCompanyTax());
            check(!target.revenueHistory.isEmpty(), "日结应该记营收");
            check(target.lastSettleDay == ctx.tradingDay(), "应该记录结算日");
            check(target.valuationCents >= 500_000L, "估值不应低于 5000 币");
        });
        runner.test("公司：升级", () -> {
            CompanyStore.Company target = mustFindCompany();
            target.cumulativeRevenueCents = 1_000_000L;
            // 不要直接改 cashCents：那会绕过账本，把对账搞失衡。走 ledger 注资。
            ctx.ledger.issue(target, 1_000_000L, "selftest.fund", "Selftest", "自检给公司注资");
            ActionResult levelUp = CompanyEngine.levelUp(ctx, account, target);
            check(levelUp.ok, "升级失败：" + levelUp.message);
            check(target.level >= 2, "等级应该到 2");
        });
        runner.test("公司：破产标记", () -> {
            give(account, 1_000_000L);
            CompanyStore.Company victim = ctx.companies.create(account, "测试破产公司", "mining",
                    ctx.tradingDay(), ctx.settings);
            check(victim != null, "应该能创建第二家公司");
            ctx.companies.markBankrupt(victim, ctx.tradingDay());
            check(victim.bankrupt, "应该标记破产");
            check(!victim.listed, "破产公司不应处于上市状态");
        });
        runner.test("IPO：条件校验、定价、稀释、锁定", () -> {
            CompanyStore.Company target = mustFindCompany();
            target.level = 3;
            target.createdDay = ctx.tradingDay() - 10L;
            target.valuationCents = 6_000_000L;
            ctx.ledger.issue(target, 1_000_000L, "selftest.fund", "Selftest", "自检给公司注资");
            target.profitHistory.clear();
            for (int i = 0; i < 7; i++) {
                target.profitHistory.add(100_000L);
            }
            StringBuilder whyNot = new StringBuilder();
            check(IpoEngine.canApply(ctx, target, whyNot), "满足条件却不能申请 IPO：" + whyNot);
            long sharesBefore = target.sharesTotal;
            long price = IpoEngine.estimateIpoPrice(target, 0.25);
            check(price > 0L, "发行价应该为正");
            ActionResult apply = IpoEngine.apply(ctx, account, 0.25, null);
            check(apply.ok, "IPO 申请失败：" + apply.message);
            check(target.ipoPending, "应该处于待确认状态");
            ctx.clock.resetForTest(ctx.nowMs() + 2L * ctx.clock.dayMillis(), 24L, ctx.tradingDay() + 2L);
            ActionResult confirm = IpoEngine.confirm(ctx, account);
            check(confirm.ok, "IPO 确认失败：" + confirm.message);
            check(target.listed, "确认后应该上市");
            check(target.code != null && target.code.startsWith("P"), "上市代码应为 Pxxx");
            check(target.sharesTotal > sharesBefore, "总股本应该被稀释扩大");
            StockStore.Stock stock = ctx.stocks.stock(target.code);
            check(stock != null, "交易所里应该有这只股票");
            check(stock.lockUntilDay >= ctx.tradingDay(), "锁定期应该设置");
            check(stock.priceCents > 0L, "上市价应该为正");
        });
        runner.test("IPO：上市后可以交易", () -> {
            CompanyStore.Company target = mustFindCompany();
            check(target.listed, "公司还没上市");
            ActionResult buy = FinanceService.buyStock(ctx, account, target.code, 5);
            check(buy.ok, "上市股票应该能买：" + buy.message);
        });
        runner.test("IPO：退市条件", () -> {
            CompanyStore.Company target = mustFindCompany();
            StockStore.Stock stock = ctx.stocks.stock(target.code);
            check(stock != null, "股票不存在");
            stock.priceCents = 50L;
            stock.lowPriceDays = 6;
            IpoEngine.onTradingDay(ctx);
            check(!target.listed || ctx.stocks.stock(target.code) == null, "连续 7 交易日低于 1 币应该退市");
        });

        // 5. 期货 / 期权
        runner.test("期货：开仓保证金与报价", () -> {
            give(account, 2_000_000L);
            ActionResult quote = FuturesEngine.quote(ctx, "IRON", 7);
            check(quote.ok, "期货报价失败：" + quote.message);
            ActionResult open = FuturesEngine.open(ctx, account, "IRON", true, 1L, 7);
            check(open.ok, "开多失败：" + open.message);
            check(!ctx.futures.of(account.uuid).isEmpty(), "应该有持仓");
            check(ctx.futures.of(account.uuid).get(0).marginCents > 0L, "保证金应该为正");
        });
        runner.test("期货：盯市与平仓", () -> {
            FuturesStore.FuturePosition position = ctx.futures.of(account.uuid).get(0);
            long priceBefore = ctx.market.price("minecraft:iron_ingot");
            ctx.market.setPrice("minecraft:iron_ingot", priceBefore * 2L);
            FuturesEngine.onMarketTick(ctx);
            check(position.lastMarkPriceCents != position.entryPriceCents, "盯市价格应该变化");
            ActionResult close = FuturesEngine.close(ctx, account, position.id);
            check(close.ok, "平仓失败：" + close.message);
            check(ctx.futures.of(account.uuid).isEmpty(), "平仓后应该没有持仓");
            check(ctx.ledger.isBalanced(), "平仓后账本应保持平衡");
        });
        runner.test("期货：到期现金交割", () -> {
            FuturesEngine.open(ctx, account, "GOLD", false, 1L, 7);
            FuturesStore.FuturePosition position = ctx.futures.of(account.uuid).get(0);
            position.expiryDay = ctx.tradingDay();
            FuturesEngine.onMarketTick(ctx);
            check(ctx.futures.of(account.uuid).isEmpty(), "到期后合约应删除");
            check(ctx.ledger.isBalanced(), "交割后账本应保持平衡");
        });
        runner.test("期权：Black-Scholes 固定输入", () -> {
            // 标准 BS：S=K=100 币、r=3%、σ=30%、T=1 年 → call≈13.28 币、put≈10.32 币，
            // 平价关系 call-put = S - K·e^{-rT} ≈ 2.955 币。
            long call = OptionsEngine.blackScholesCents(10_000.0, 10_000.0, 0.03, 0.3, 1.0, true);
            long put = OptionsEngine.blackScholesCents(10_000.0, 10_000.0, 0.03, 0.3, 1.0, false);
            check(Math.abs(call - 1328L) <= 10L, "call 价格偏离期望值：" + call + "（期望约 1328 分）");
            check(Math.abs(put - 1032L) <= 10L, "put 价格偏离期望值：" + put + "（期望约 1032 分）");
            check(Math.abs((call - put) - Math.round(10_000.0 - 10_000.0 * Math.exp(-0.03))) <= 10L,
                    "看涨看跌平价关系不成立");
        });
        runner.test("期权：买方与卖方开仓/保证金/平仓", () -> {
            StockStore.Stock stock = ctx.stocks.stock("BLK");
            long strike = stock.priceCents;
            long before = account.balanceCents();
            ActionResult buy = OptionsEngine.open(ctx, account, false, "call", "BLK", strike, 7, 1L);
            check(buy.ok, "买期权失败：" + buy.message);
            check(account.balanceCents() < before, "买方应该付权利金");
            ActionResult write = OptionsEngine.open(ctx, account, true, "call", "BLK", strike, 7, 1L);
            check(write.ok, "卖期权失败：" + write.message);
            OptionsStore.OptionPosition writer = null;
            for (OptionsStore.OptionPosition position : ctx.options.of(account.uuid)) {
                if (position.writer) {
                    writer = position;
                }
            }
            check(writer != null && writer.marginCents > 0L, "卖方应该缴保证金");
            ActionResult close = OptionsEngine.close(ctx, account, writer.id);
            check(close.ok, "卖方平仓失败：" + close.message);
            check(ctx.ledger.isBalanced(), "期权平仓后账本应平衡");
        });
        runner.test("期权：到期结算", () -> {
            for (OptionsStore.OptionPosition position : new ArrayList<>(ctx.options.of(account.uuid))) {
                position.expiryDay = ctx.tradingDay();
            }
            OptionsEngine.onMarketTick(ctx);
            check(ctx.options.of(account.uuid).isEmpty(), "到期后期权持仓应清空");
            check(ctx.ledger.isBalanced(), "期权到期后账本应平衡");
        });

        // 6. 政府
        runner.test("政府：指标与压力分", () -> {
            GovernmentEngine.updateIndicators(ctx, List.of(account));
            int stress = GovernmentEngine.stressScore(ctx);
            check(stress >= 0 && stress <= 100, "压力分应在 0-100：" + stress);
            JsonObject fallback = GovernmentEngine.localFallback(ctx);
            check(fallback != null && fallback.has("action"), "本地兜底应该给出 action");
            String action = fallback.get("action").getAsString();
            check(java.util.Arrays.asList(GovernmentStore.ACTIONS).contains(action), "兜底 action 非法：" + action);
        });
        runner.test("政府：政策落地、预算与冷却", () -> {
            ctx.ledger.issue(ctx.government.budgetAccount(), 2_000_000L, "selftest", "Selftest", "测试预算");
            long budgetBefore = ctx.government.budgetCents();
            // 福利只发给"余额低于在线中位数"的玩家：造一个穷玩家一起在线
            Account poor = ctx.accounts.getOrCreate("00000000-0000-0000-0000-00000000cafe02", "SelftestPoor",
                    now, 1_000L, true);
            long povertyBefore = poor.balanceCents();
            JsonObject welfare = new JsonObject();
            welfare.addProperty("action", "welfare");
            JsonObject params = new JsonObject();
            params.addProperty("amountCents", 10_000L);
            welfare.add("params", params);
            welfare.addProperty("reason", "selftest");
            ActionResult first = GovernmentEngine.applyDecision(ctx, welfare, false, List.of(account, poor));
            check(first.ok, "福利政策失败：" + first.message);
            check(ctx.government.budgetCents() < budgetBefore, "福利应该从预算扣钱");
            check(poor.balanceCents() > povertyBefore, "穷玩家应该收到福利");
            int policiesBefore = ctx.government.policies().size();
            ActionResult second = GovernmentEngine.applyDecision(ctx, welfare, false, List.of(account, poor));
            check(!second.ok || ctx.government.policies().size() > policiesBefore + 1,
                    "24 小时冷却内不应该重复发福利");
        });
        runner.test("政府：none 不发广播、非法 action 被拒", () -> {
            JsonObject none = new JsonObject();
            none.addProperty("action", "none");
            none.add("params", new JsonObject());
            int before = ctx.government.policies().size();
            ActionResult result = GovernmentEngine.applyDecision(ctx, none, true, List.of(account));
            check(result.ok, "none 应该被接受");
            check(result.broadcast.isEmpty(), "none 不应该广播");
            check(ctx.government.policies().size() == before, "none 不应记成政策");
            JsonObject illegal = new JsonObject();
            illegal.addProperty("action", "print_money");
            check(!GovernmentEngine.applyDecision(ctx, illegal, true, List.of(account)).ok, "非法 action 应该被拒");
            JsonObject badRate = new JsonObject();
            badRate.addProperty("action", "rate_cut");
            JsonObject badParams = new JsonObject();
            badParams.addProperty("bp", 33);
            badRate.add("params", badParams);
            check(!GovernmentEngine.applyDecision(ctx, badRate, true, List.of(account)).ok, "非法 bp 应该被拒");
        });
        runner.test("政府：利率政策与同方向冷却", () -> {
            double before = ctx.government.policyRate;
            JsonObject cut = new JsonObject();
            cut.addProperty("action", "rate_cut");
            JsonObject params = new JsonObject();
            params.addProperty("bp", 25);
            cut.add("params", params);
            cut.addProperty("reason", "selftest");
            ActionResult result = GovernmentEngine.applyDecision(ctx, cut, false, List.of(account));
            check(result.ok, "降息失败：" + result.message);
            check(Math.abs(ctx.government.policyRate - (before - 0.0025)) < 1e-9, "政策利率应该降 25bp");
            JsonObject again = cut.deepCopy();
            check(!GovernmentEngine.applyDecision(ctx, again, false, List.of(account)).ok, "同方向 6 交易日内应该被冷却拒绝");
        });

        // 7. 对账
        runner.test("对账：账本恒等式 + 重放核对", () -> {
            check(ctx.ledger.isBalanced(), "所有账户余额之和应该为 0");
            Ledger.ReplayResult replay = ctx.reconcile();
            check(replay.ok(), "重放核对不一致：" + replay.detail);
        });

        // 8. AI JSON 坏样本
        runner.test("AI：20 组坏样本全部被拒", () -> {
            String[] badParse = {
                    "",
                    "不是 JSON",
                    "```json\n{\"intent\":\"buy\"}\n```",
                    "{\"intent\":\"hack\",\"items\":[]}",
                    "{\"intent\":\"buy\",\"items\":[{\"id\":\"iron_ingot\",\"count\":1}]}",
                    "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":0}]}",
                    "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":999}]}",
                    "{\"intent\":\"sell\",\"items\":\"not-array\"}",
                    "{\"intent\":\"stock_buy\",\"symbol\":\"XYZ\",\"shares\":0}",
                    "{\"intent\":\"stock_buy\",\"symbol\":\"BLK\",\"shares\":-5}",
                    "{\"intent\":\"deposit\",\"amountCents\":-100}",
                    "{\"intent\":\"fund_buy\",\"symbol\":\"FND01\",\"amountCents\":0}",
                    "{\"intent\":null}",
                    "{\"intent\":123}",
                    "[]",
                    "null",
                    "{",
                    "{\"intent\":\"buy\",\"items\":[{}]}",
                    "{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":1.5}]}",
                    "{\"intent\":\"teleport\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":1}]}",
            };
            for (String text : badParse) {
                JsonObject parsed = JsonFile.parseLooseObject(text);
                if (parsed == null) {
                    continue; // 抠不出对象 = 被拒，正确
                }
                JsonObject valid = AiJson.validateParse(parsed, ctx.market);
                check(valid == null, "坏样本居然通过校验：" + text);
            }
            String[] badGovernment = {
                    "{\"action\":\"print_money\"}",
                    "{\"action\":\"rate_cut\",\"params\":{\"bp\":33}}",
                    "{\"action\":\"welfare\",\"params\":{\"amountCents\":-1}}",
                    "{\"action\":\"subsidy\",\"params\":{\"target\":\"iron\",\"ratio\":0.5,\"days\":3}}",
                    "{\"action\":\"stimulus\",\"params\":{\"amountCents\":1,\"sectors\":[],\"days\":0}}",
                    "{\"action\":\"liquidity\",\"params\":{\"ratio\":5}}",
                    "{\"action\":\"min_wage\",\"params\":{\"amountCents\":1}}",
                    "{\"action\":null}",
                    "{}",
                    "{\"action\":\"austerity\",\"params\":{\"ratio\":0.9}}",
            };
            for (String text : badGovernment) {
                JsonObject parsed = JsonFile.parseLooseObject(text);
                if (parsed == null) {
                    continue;
                }
                check(AiJson.validateGovernment(parsed) == null, "坏的政府返回居然通过校验：" + text);
            }
        });
        runner.test("AI：正常样本与 markdown 包裹能解析", () -> {
            String text = "好的，结果如下：\n```json\n{\"intent\":\"buy\",\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":3}],"
                    + "\"symbol\":\"\",\"shares\":0,\"amountCents\":0,\"reply\":\"买 3 个铁锭\"}\n```";
            JsonObject parsed = JsonFile.parseLooseObject(text);
            check(parsed != null, "markdown 包裹的 JSON 应该抠得出来");
            JsonObject valid = AiJson.validateParse(parsed, ctx.market);
            check(valid != null, "正常 parse 样本应该通过校验");
            check("buy".equals(valid.get("intent").getAsString()), "intent 应该保留");
            JsonObject government = JsonFile.parseLooseObject(
                    "{\"action\":\"rate_hike\",\"params\":{\"bp\":50},\"reason\":\"通胀高\",\"announcement\":\"加息\"}");
            check(government != null, "政府 JSON 解析失败");
            check(AiJson.validateGovernment(government) != null, "正常政府样本应该通过校验");
        });

        // 9. 持久化
        runner.test("持久化：落盘后重新加载数据还在、对账平衡", () -> {
            ctx.saveAll();
            AimcContext reloaded = AimcContext.load(serverDir, configDir, MarketSettings.defaults(), now + 5_000L, 7L);
            check(reloaded.accounts.all().size() >= 1, "重新加载后应该有账户");
            Account again = reloaded.accounts.get(TEST_UUID);
            check(again != null, "重新加载后账户丢失");
            check(again.balanceCents() == account.balanceCents(), "重新加载后余额不一致");
            check(!reloaded.companies.byCeo(TEST_UUID).isEmpty(), "重新加载后公司丢失");
            check(reloaded.ledger.isBalanced(), "重新加载后账本应该平衡");
            Ledger.ReplayResult replay = reloaded.reconcile();
            check(replay.ok(), "重新加载后重放对账不一致：" + replay.detail);
        });

        long elapsed = System.currentTimeMillis() - start;
        int total = runner.passed + runner.failed;
        if (runner.failed == 0) {
            runner.lines.add("SELFTEST PASS：" + total + " 项全部通过");
        } else {
            runner.lines.add("SELFTEST FAIL：" + runner.failed + " 项失败 / 共 " + total + " 项");
        }
        try {
            deleteRecursively(root);
        } catch (Exception ignored) {
            // 临时目录删不掉不影响结果
        }
        return new Result(runner.passed, runner.failed, runner.lines, elapsed);
    }

    // ── 工具 ──

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void give(Account account, long cents) {
        // 通过一个独立的"外部注入"账户加钱，保证账本平衡
        // 直接用系统增发到玩家（self test 专用）
        AimcContext ctx = current;
        ctx.ledger.issue(account, cents, "selftest.fund", "Selftest", "自检注资");
    }

    private static AimcContext current;

    private static void resetTradeCooldown(Account account) {
        account.lastTradeAtMs = 0L;
    }

    private static CompanyStore.Company mustFindCompany() {
        List<CompanyStore.Company> list = current.companies.byCeo(TEST_UUID);
        for (CompanyStore.Company company : list) {
            if (!company.bankrupt) {
                return company;
            }
        }
        throw new AssertionError("找不到测试公司");
    }


    private static void deleteRecursively(Path path) throws java.io.IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (java.io.IOException ignored) {
                    // ignore
                }
            });
        }
    }

}
