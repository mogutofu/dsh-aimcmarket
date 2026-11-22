package com.mogutofu.aimcmarket.data;

import com.mogutofu.aimcmarket.ai.AiGateway;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.util.Log;
import com.mogutofu.aimcmarket.util.MarketClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 全部持久化状态 + 时钟 + 账本 + 配置的总入口。
 *
 * <p>所有引擎都是无状态的静态方法，入参是 {@code AimcContext}（加上命令上下文/玩家）。
 * 这样单测可以直接造一个临时目录的 AimcContext 跑完整流程，不需要 Minecraft。</p>
 */
public final class AimcContext {
    public final Path serverDir;
    public final Path dataDir;
    public final Path configDir;
    public final MarketSettings settings;
    public final MarketClock clock;
    public final Random random;
    public final Ledger ledger;

    public final Accounts accounts;
    public final MarketStore market;
    public final StockStore stocks;
    public final FundStore funds;
    public final BankStore bank;
    public final CompanyStore companies;
    public final FuturesStore futures;
    public final OptionsStore options;
    public final GovernmentStore government;
    public final NewsStore news;
    /** 玩家买入记录（退货用）。 */
    public final PurchaseStore purchases;

    /** AI 网关（模组启动后注入；单测可以注入假实现或 null）。 */
    public AiGateway ai;

    /**
     * AI 异步提交器：由模组注入（daemon 线程池跑同步调用，回调切回服务器主线程）。
     * 单测里可以注入一个同步实现。null 表示不能异步，调用方直接走本地兜底。
     */
    public AiSubmitter aiSubmitter;

    /** 政府 AI 请求在飞（避免重复提交）。 */
    public volatile boolean govInFlight;
    /** 新闻 AI 请求在飞。 */
    public volatile boolean newsInFlight;

    /** AI 异步提交窄接口。 */
    public interface AiSubmitter {
        void submit(String label, java.util.function.Supplier<com.google.gson.JsonObject> call,
                    java.util.function.Consumer<com.google.gson.JsonObject> apply);
    }

    /** 全服广播窄接口（由模组注入，引擎层不碰 Minecraft）。 */
    public interface Broadcaster {
        void broadcast(String message, boolean government);
    }

    public volatile Broadcaster broadcaster;

    /** 在线玩家（由模组每次 tick 更新，政府指标/福利要用）。 */
    public volatile java.util.Collection<Account> onlineAccounts = java.util.Collections.emptyList();

    public long loadedAtMs;
    /** 上一次成功落盘的时间（显示在 /ex admin stats）。 */
    public long lastSavedAtMs;
    /** 引擎层往这里塞要广播/私聊的通知（追保、到期交割、IPO 结果等），模组侧每个 tick 取走。 */
    public final java.util.List<String> notices =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private AimcContext(Path serverDir, Path configDir, MarketSettings settings, long seed) {
        this.serverDir = serverDir;
        this.dataDir = serverDir.resolve("world").resolve("aimcmarket");
        this.configDir = configDir;
        this.settings = settings;
        this.clock = new MarketClock(settings.marketTickMinutes, settings.tradingDayMinutes);
        this.random = new Random(seed);
        this.ledger = new Ledger(dataDir.resolve("ledger.jsonl"));
        this.accounts = new Accounts(dataDir.resolve("accounts.json"), ledger);
        this.market = new MarketStore(dataDir.resolve("market.json"), configDir.resolve("catalog.json"));
        this.stocks = new StockStore(dataDir.resolve("stocks.json"));
        this.funds = new FundStore(dataDir.resolve("funds.json"));
        this.bank = new BankStore(dataDir.resolve("bank.json"), ledger);
        this.companies = new CompanyStore(dataDir.resolve("companies.json"), ledger);
        this.futures = new FuturesStore(dataDir.resolve("futures.json"));
        this.options = new OptionsStore(dataDir.resolve("options.json"));
        this.government = new GovernmentStore(dataDir.resolve("government.json"), ledger);
        this.news = new NewsStore(dataDir.resolve("news.json"));
        this.purchases = new PurchaseStore(dataDir.resolve("purchases.json"));
    }

    /**
     * 创建并加载所有数据。
     *
     * <p>顺序很重要：先把所有余额账户注册进账本，最后 {@code ledger.reanchorIssued()}。
     * 任何一个数据文件坏掉都只备份 + 用默认值，绝不抛异常导致服务器启动失败。</p>
     */
    public static AimcContext load(Path serverDir, Path configDir, MarketSettings settings, long nowMs, long seed) {
        AimcContext context = new AimcContext(serverDir, configDir, settings, seed);
        try {
            Files.createDirectories(context.dataDir);
        } catch (IOException error) {
            Log.warn("创建数据目录失败：" + context.dataDir, error);
        }
        try {
            Files.createDirectories(context.configDir);
        } catch (IOException error) {
            Log.warn("创建配置目录失败：" + context.configDir, error);
        }
        context.loadedAtMs = nowMs;
        context.clock.poll(nowMs);

        context.market.load();
        context.ledger.register(context.market.makerAccount());
        context.ledger.register(context.market.feeAccount());
        context.stocks.load();
        context.funds.load();
        for (FundStore.Fund fund : context.funds.all()) {
            context.ledger.register(fund.account);
        }
        context.companies.load();
        context.government.load();
        context.ledger.register(context.government.budgetAccount());
        context.accounts.load();
        context.bank.load();
        context.futures.load();
        context.options.load();
        context.news.load();
        context.purchases.load();

        context.ledger.reanchorIssued();
        long toppedUp = context.government.ensureMinimumBudget(context.ledger, "系统");
        if (toppedUp > 0L) {
            Log.info("政府预算保底补足 " + com.mogutofu.aimcmarket.util.Cents.format(toppedUp) + " 币");
        }
        Log.info("AIMCmarket 数据加载完成：货币总量 " + com.mogutofu.aimcmarket.util.Cents.format(context.ledger.circulationCents())
                + " 币，tick=" + context.clock.tick() + "，交易日=" + context.clock.day());
        return context;
    }

    /** 全部落盘（ServerStopping 和每个 market tick 调用）。单文件失败不影响其它文件。 */
    public void saveAll() {
        long start = System.currentTimeMillis();
        accounts.save();
        market.save();
        stocks.save();
        funds.save();
        bank.save(accounts);
        companies.save();
        futures.save();
        options.save();
        government.save();
        news.save();
        purchases.save();
        lastSavedAtMs = System.currentTimeMillis();
        Log.info("数据已落盘（" + (lastSavedAtMs - start) + "ms）");
    }

    public long nowMs() {
        return clock.nowMs();
    }

    public long tick() {
        return clock.tick();
    }

    public long tradingDay() {
        return clock.day();
    }

    /** 对账：账本重放 + 恒等式。 */
    public Ledger.ReplayResult reconcile() {
        Ledger.ReplayResult replay = ledger.replayAndVerify();
        boolean balanced = ledger.isBalanced();
        if (!balanced || !replay.ok()) {
            Log.warn("对账异常：balanced=" + balanced + "，mismatches=" + replay.mismatches + "，" + replay.detail);
        }
        return replay;
    }

    /** 货币总量（分）。 */
    public long totalMoneyCents() {
        return ledger.circulationCents();
    }

    /** 总就业：所有玩家公司员工数 / 总容量。 */
    public double employmentRate() {
        long employees = 0L;
        long capacity = 0L;
        for (CompanyStore.Company company : companies.all()) {
            if (company.bankrupt) {
                continue;
            }
            employees += company.employees;
            capacity += company.maxEmployees();
        }
        if (capacity <= 0L) {
            return 0.0;
        }
        return Math.min(1.0, (double) employees / (double) capacity);
    }
}
