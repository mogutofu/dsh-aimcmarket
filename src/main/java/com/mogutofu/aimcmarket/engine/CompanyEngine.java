package com.mogutofu.aimcmarket.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 玩家公司引擎：交易日日结、破产、员工/升级，以及上市公司（NPC + 玩家公司）的财报。
 *
 * <p>纯 Java 静态方法，不 import 任何 Minecraft 类，也不起线程；所有可预期失败都返回
 * {@link ActionResult#fail(String)}，意外异常在这里吃掉（记日志），绝不抛给命令层或 tick 循环。</p>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>这里的"天"全部是<b>交易日</b>（默认 60 现实分钟 = 1 交易日），不是现实天。</li>
 *   <li>行业景气 = 股票市场里该行业的景气乘数 × 政府行业刺激乘数（两者都来自对应 Store）。</li>
 *   <li>日结的营收/成本/税/破产判定都在 {@link CompanyStore#settleDaily} 里算，
 *       本引擎只负责"什么时候结算、结算完要不要广播"。</li>
 * </ul>
 */
public final class CompanyEngine {
    /** 财报周期：每 7 个交易日一份。 */
    public static final long REPORT_INTERVAL_DAYS = 7L;
    /** 财报标题最多保留的字符数（AI 可能返回一大段，先截断再写新闻）。 */
    private static final int MAX_HEADLINE_CHARS = 120;
    /** NPC 公司没有真实账本，用"股价 × 这个系数"合成 7 日营收（分），只为文案量级合理。 */
    private static final long NPC_REVENUE_PER_PRICE_CENT = 140L;
    /** 合成营收时股价的上限，防止被改坏的价格把 long 乘爆。 */
    private static final long MAX_SYNTHETIC_PRICE_CENTS = 100_000_000L;

    private CompanyEngine() {
    }

    // ══════════════════════════════════════════════════════════════
    // 交易日
    // ══════════════════════════════════════════════════════════════

    /**
     * 每个交易日调一次（{@code MarketTicker} 在交易日切换时调用）。
     *
     * <ol>
     *   <li>遍历所有公司：没破产、且 {@code lastSettleDay < 今天} 的公司做日结；</li>
     *   <li>日结后破产的公司发全服通知 + 破产新闻；</li>
     *   <li>到期（每 7 交易日）的股票生成财报：NPC 用 {@link #localReportData} 本地模板，
     *       玩家公司股票用真实 7 日营收/利润，最后统一走 {@link #applyReport}。</li>
     * </ol>
     */
    public static void onTradingDay(AimcContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            long day = ctx.tradingDay();
            settleCompanies(ctx, day);
            generateScheduledReports(ctx, day);
        } catch (RuntimeException error) {
            Log.warn("公司交易日结算失败（已跳过本日）", error);
        }
    }

    /** 公司日结：一家出错不影响其它公司。 */
    private static void settleCompanies(AimcContext ctx, long day) {
        for (CompanyStore.Company company : ctx.companies.all()) {
            if (company == null || company.bankrupt || company.lastSettleDay >= day) {
                continue;
            }
            try {
                double sectorClimate = ctx.stocks.sectorClimate(company.sector)
                        * ctx.government.sectorStimulusMultiplier(company.sector, day);
                ctx.companies.settleDaily(company, day, sectorClimate, ctx.settings, ctx.random,
                        ctx.government.effectiveCompanyTax());
                if (company.bankrupt) {
                    // settleDaily 内部会在"现金为负且连续 3 天亏"时标记破产；这里只做公告。
                    announceBankruptcy(ctx, company);
                }
            } catch (RuntimeException error) {
                Log.warn("公司日结失败（" + company.name + " / " + company.id + "）", error);
            }
        }
    }

    /**
     * 破产公告：通知（{@code ctx.notices}，由模组侧统一广播）+ 一条 bankrupt 新闻。
     *
     * <p>上市公司破产时股票还在交易所里，摘牌和 CEO 信用 -100 由 {@code IpoEngine.onTradingDay}
     * 统一处理，这里不重复扣；没上市的公司没有股票可摘，信用扣减在这里做，保证 3.9 的口径。</p>
     */
    private static void announceBankruptcy(AimcContext ctx, CompanyStore.Company company) {
        // 股票真的还在交易所里才算"有股票"，否则（没上市 / 代码异常）在这里扣 CEO 信用。
        boolean hasStock = company.code != null && !company.code.isEmpty()
                && ctx.stocks.stock(company.code) != null;
        StringBuilder body = new StringBuilder();
        body.append("公司「").append(company.name).append("」因现金为负且连续 3 个交易日亏损，已进入破产清算，员工全部清退。");
        if (hasStock) {
            body.append("股票（").append(company.code).append("）将在今天的退市检查中摘牌，股份按 0 结算。");
        } else {
            body.append("CEO 信用 -100。");
            Account ceo = ctx.accounts.get(company.ceoUuid);
            if (ceo != null) {
                ceo.adjustCredit(-100.0, 300.0, 850.0);
            }
        }
        addNotice(ctx, "【破产】公司「" + company.name + "」破产清算" + (hasStock ? "，股票即将摘牌" : "") + "。");
        publishNews(ctx, "bankrupt", "「" + company.name + "」破产清算" + (hasStock ? "，股票摘牌在即" : ""),
                body.toString());
        Log.info("公司破产：" + company.name + "（" + company.id + "）");
    }

    /** 到期财报：每只股票独立 try/catch。 */
    private static void generateScheduledReports(AimcContext ctx, long day) {
        for (StockStore.Stock stock : ctx.stocks.all()) {
            if (stock == null) {
                continue;
            }
            try {
                if (!reportDue(stock, day)) {
                    continue;
                }
                CompanyStore.Company company = resolveCompany(ctx, stock);
                if (company != null && company.bankrupt) {
                    // 今天刚破产：IpoEngine 会摘牌，不再发一份没意义的财报
                    continue;
                }
                JsonObject data = localReportData(ctx, stock.code);
                if (data == null) {
                    continue;
                }
                applyReport(ctx, stock.code, data);
            } catch (RuntimeException error) {
                Log.warn("生成财报失败（" + stock.code + "）", error);
            }
        }
    }

    /** 财报是否到期：优先按上次财报日，其次按上市日；都没有的老数据按第 7 个交易日开始。 */
    private static boolean reportDue(StockStore.Stock stock, long day) {
        if (stock.lastReportDay >= 0L) {
            return day - stock.lastReportDay >= REPORT_INTERVAL_DAYS;
        }
        if (stock.listedDay >= 0L) {
            return day - stock.listedDay >= REPORT_INTERVAL_DAYS;
        }
        return day >= REPORT_INTERVAL_DAYS;
    }

    // ══════════════════════════════════════════════════════════════
    // 财报（AI task=report 的 context / 本地模板 / 落地）
    // ══════════════════════════════════════════════════════════════

    /**
     * 组装 AI {@code task=report} 需要的市场上下文（纯数据，不发起任何 AI 调用）。
     *
     * <p>字段：代码/公司名/行业/等级/员工/口碑/最近 7 交易日营收与利润/现价与较发行价涨跌/行业景气。
     * 玩家公司用真实 7 日营收利润；NPC 公司没有账本，用股价合成一份量级合理的数字。</p>
     */
    public static JsonObject buildReportContext(AimcContext ctx, String code) {
        JsonObject json = new JsonObject();
        json.addProperty("task", "report");
        if (ctx == null || code == null) {
            return json;
        }
        try {
            StockStore.Stock stock = ctx.stocks.stock(code);
            if (stock == null) {
                return json;
            }
            CompanyStore.Company company = resolveCompany(ctx, stock);
            long day = ctx.tradingDay();
            double climate = ctx.stocks.sectorClimate(stock.sector)
                    * ctx.government.sectorStimulusMultiplier(stock.sector, day);
            long initialPrice = Math.max(1L, stock.initialPriceCents);
            double priceChange = (double) (stock.priceCents - initialPrice) / (double) initialPrice;

            long revenue7d;
            long profit7d;
            String name;
            String sector;
            int level;
            int employees;
            double reputation;
            long shares;
            long valuation;
            if (company != null) {
                revenue7d = sumRecent(company.revenueHistory, (int) REPORT_INTERVAL_DAYS);
                profit7d = sumRecent(company.profitHistory, (int) REPORT_INTERVAL_DAYS);
                name = company.name;
                sector = company.sectorName();
                level = company.level;
                employees = company.employees;
                reputation = company.reputation;
                shares = company.sharesTotal;
                valuation = company.valuationCents;
            } else {
                long price = Cents.clamp(stock.priceCents, 1L, MAX_SYNTHETIC_PRICE_CENTS);
                revenue7d = Math.multiplyExact(price, NPC_REVENUE_PER_PRICE_CENT);
                double margin = Cents.clampDouble(0.12 + priceChange + (climate - 1.0) * 3.0, 0.02, 0.35);
                profit7d = Cents.percent(revenue7d, margin);
                name = stock.name == null ? stock.code : stock.name;
                sector = stock.sector;
                level = 0;
                employees = 0;
                reputation = 1.0;
                shares = Math.max(1L, stock.sharesOutstanding);
                valuation = safeMul(Math.max(1L, stock.priceCents), shares);
            }

            json.addProperty("code", stock.code);
            json.addProperty("name", name);
            json.addProperty("sector", sector);
            json.addProperty("level", level);
            json.addProperty("employees", employees);
            json.addProperty("reputation", reputation);
            json.addProperty("sharesTotal", shares);
            json.addProperty("valuationCents", valuation);
            json.addProperty("revenue7dCents", revenue7d);
            json.addProperty("profit7dCents", profit7d);
            json.addProperty("priceCents", stock.priceCents);
            json.addProperty("initialPriceCents", stock.initialPriceCents);
            json.addProperty("priceChange", priceChange);
            json.addProperty("sectorClimate", climate);
            json.addProperty("lastReportDay", stock.lastReportDay);
            json.addProperty("lastOutlook", stock.lastOutlook == null ? "flat" : stock.lastOutlook);
        } catch (RuntimeException error) {
            Log.warn("组装财报上下文失败（" + code + "）", error);
        }
        return json;
    }

    /** 不同展望的标题模板（每条都是 "名称/营收/净利" 三个位置参数，轮换使用，避免每次一样）。 */
    private static final String[] HEADLINES_UP = {
            "「%1$s」业绩超预期：7 日营收 %2$s 币，净利 %3$s 币",
            "「%1$s」订单饱满，营收 %2$s 币，行业景气向上",
            "「%1$s」交出亮眼成绩单：营收 %2$s 币，净利 %3$s 币，上调展望",
            "「%1$s」市场需求旺盛，7 日净利 %3$s 币",
    };
    private static final String[] HEADLINES_FLAT = {
            "「%1$s」财报平稳：营收 %2$s 币，净利 %3$s 币",
            "「%1$s」业绩符合预期，7 日营收 %2$s 币",
            "「%1$s」经营稳健：净利 %3$s 币，展望持平",
            "「%1$s」营收 %2$s 币、净利 %3$s 币，维持原有指引",
    };
    private static final String[] HEADLINES_DOWN = {
            "「%1$s」财报承压：营收 %2$s 币，净利 %3$s 币，下调展望",
            "「%1$s」业绩不及预期，7 日营收仅 %2$s 币",
            "「%1$s」遭遇行业逆风，净利 %3$s 币，管理层谨慎",
            "「%1$s」成本上升，营收 %2$s 币，展望偏弱",
    };

    /**
     * 本地财报模板（AI 不可用时的兜底，也是交易日自动财报的数据源）。
     *
     * <p>outlook 由"股价较发行价涨跌 + 行业景气"决定：涨得多/景气好 → up，反之 down，中间 flat。
     * headline 从对应模板池里按上次财报日轮换，同一只股票不会每次都一样。</p>
     *
     * @return {@code {revenueCents, profitCents, outlook, headline}}；股票不存在返回 null
     */
    public static JsonObject localReportData(AimcContext ctx, String code) {
        if (ctx == null || code == null) {
            return null;
        }
        try {
            StockStore.Stock stock = ctx.stocks.stock(code);
            if (stock == null) {
                return null;
            }
            JsonObject context = buildReportContext(ctx, code);
            long rawRevenue = JsonFile.lng(context, "revenue7dCents", 0L);
            long rawProfit = JsonFile.lng(context, "profit7dCents", 0L);
            // 财报口径要求非负（applyReport 会丢弃负数），真实亏损时报 0，用 outlook=down 表达。
            long revenue = Math.max(0L, rawRevenue);
            long profit = Math.max(0L, rawProfit);

            double priceChange = JsonFile.dbl(context, "priceChange", 0.0);
            double climate = JsonFile.dbl(context, "sectorClimate", 1.0);
            double score = priceChange + (climate - 1.0) * 5.0;
            if (rawProfit > 0L) {
                score += 0.01;
            } else if (rawProfit < 0L) {
                score -= 0.02;
            }
            String outlook = score > 0.02 ? "up" : (score < -0.02 ? "down" : "flat");
            String headline = pickHeadline(stock, outlook, revenue, profit);

            JsonObject data = new JsonObject();
            data.addProperty("revenueCents", revenue);
            data.addProperty("profitCents", profit);
            data.addProperty("outlook", outlook);
            data.addProperty("headline", headline);
            return data;
        } catch (RuntimeException error) {
            Log.warn("生成本地财报失败（" + code + "）", error);
            return null;
        }
    }

    private static String pickHeadline(StockStore.Stock stock, String outlook, long revenue, long profit) {
        String[] pool;
        if ("up".equals(outlook)) {
            pool = HEADLINES_UP;
        } else if ("down".equals(outlook)) {
            pool = HEADLINES_DOWN;
        } else {
            pool = HEADLINES_FLAT;
        }
        // 按"上次财报日 × 31 + 代码 hash"轮换模板，同时保证同一只股票每期都换一条。
        int pick = (int) Math.floorMod(stock.lastReportDay * 31L + stock.code.hashCode(), (long) pool.length);
        String name = stock.name == null || stock.name.isBlank() ? stock.code : stock.name;
        return String.format(Locale.ROOT, pool[pick], name, Cents.format(revenue), Cents.format(profit));
    }

    /**
     * 落地一份财报（AI 返回或本地模板都一样走这里）。
     *
     * <p>校验：金额必须非负、outlook 只能是 up/flat/down、headline 必须是非空字符串；
     * 任何一项不合法就整份丢弃（只记日志），不写 stocks.json、不发新闻。</p>
     */
    public static void applyReport(AimcContext ctx, String code, JsonObject data) {
        if (ctx == null || code == null || data == null) {
            return;
        }
        try {
            StockStore.Stock stock = ctx.stocks.stock(code);
            if (stock == null) {
                Log.warn("财报丢弃：没有这只股票（" + code + "）");
                return;
            }
            long revenue = JsonFile.lng(data, "revenueCents", -1L);
            long profit = JsonFile.lng(data, "profitCents", -1L);
            if (revenue < 0L || profit < 0L) {
                Log.warn("财报丢弃：金额不合法（" + stock.code + "，营收 " + revenue + "，净利 " + profit + "）");
                return;
            }
            String outlook = JsonFile.str(data, "outlook", "").trim().toLowerCase(Locale.ROOT);
            if (!"up".equals(outlook) && !"flat".equals(outlook) && !"down".equals(outlook)) {
                Log.warn("财报丢弃：outlook 不合法（" + stock.code + "，" + outlook + "）");
                return;
            }
            String headline = readHeadline(data);
            if (headline == null) {
                Log.warn("财报丢弃：headline 不合法（" + stock.code + "）");
                return;
            }

            StockStore.FinancialReport report = new StockStore.FinancialReport();
            report.code = stock.code;
            report.day = ctx.tradingDay();
            report.revenueCents = revenue;
            report.profitCents = profit;
            report.outlook = outlook;
            report.headline = headline;
            ctx.stocks.addReport(report);

            String outlookName = "up".equals(outlook) ? "上调" : ("down".equals(outlook) ? "下调" : "持平");
            String body = stock.name + "（" + stock.code + "）发布财报：7 日营收 " + Cents.format(revenue)
                    + " 币，净利润 " + Cents.format(profit) + " 币，展望" + outlookName + "。";
            publishNews(ctx, "company", headline, body);
        } catch (RuntimeException error) {
            Log.warn("落地财报失败（已丢弃：" + code + "）", error);
        }
    }

    /** 从 JSON 里取标题：必须是字符串、非空；过长截断，换行压成空格。 */
    private static String readHeadline(JsonObject data) {
        if (data == null) {
            return null;
        }
        JsonElement element = data.get("headline");
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = element.getAsString().replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_HEADLINE_CHARS ? text.substring(0, MAX_HEADLINE_CHARS) : text;
    }

    // ══════════════════════════════════════════════════════════════
    // 公司子命令
    // ══════════════════════════════════════════════════════════════

    /**
     * 创建公司（{@code /ex company create <名字> <行业>}）。
     *
     * @param sector 行业：中文名（采矿/农业/制造/贸易/服务）或内部代码（mining/farming/...）都认
     */
    public static ActionResult create(AimcContext ctx, Account ceo, String name, String sector) {
        if (ctx == null || ceo == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            String sectorCode = resolveSector(sector);
            if (sectorCode == null) {
                return ActionResult.fail("没有这个行业，只能选：" + sectorChoices());
            }
            CompanyStore.Company company = ctx.companies.create(ceo, name, sectorCode, ctx.tradingDay(), ctx.settings);
            return ActionResult.ok("公司「" + company.name + "」创建成功（" + company.id + "）！行业："
                    + company.sectorName() + "，你持有 " + company.sharesTotal + " 股（100%），创建费 "
                    + Cents.format(ctx.settings.companyCreateFeeCents) + " 币。");
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ActionResult.fail(reason(error));
        } catch (RuntimeException error) {
            Log.warn("创建公司失败（" + ceo.name + "）", error);
            return ActionResult.fail("创建公司失败：" + reason(error));
        }
    }

    /** 招聘 NPC 员工（{@code /ex company hire <人数>}），只有 CEO 本人能操作。 */
    public static ActionResult hire(AimcContext ctx, Account ceo, CompanyStore.Company company, int count) {
        if (ctx == null || ceo == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        if (company == null) {
            return ActionResult.fail("找不到公司");
        }
        if (!isCeo(ceo, company)) {
            return ActionResult.fail("只有 CEO 本人能操作公司「" + company.name + "」");
        }
        if (company.bankrupt) {
            return ActionResult.fail("公司已经破产，不能再招聘");
        }
        try {
            ctx.companies.hire(company, count, ctx.settings);
            long fee = Cents.mul(ctx.settings.companyHireFeeCents, count);
            return ActionResult.ok("公司「" + company.name + "」招聘 " + count + " 名员工，花掉 "
                    + Cents.format(fee) + " 币。当前员工 " + company.employees + "/" + company.maxEmployees() + " 人。");
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ActionResult.fail(reason(error));
        } catch (RuntimeException error) {
            Log.warn("招聘失败（" + company.name + "）", error);
            return ActionResult.fail("招聘失败：" + reason(error));
        }
    }

    /** 升级公司（{@code /ex company levelup}），只有 CEO 本人能操作。 */
    public static ActionResult levelUp(AimcContext ctx, Account ceo, CompanyStore.Company company) {
        if (ctx == null || ceo == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        if (company == null) {
            return ActionResult.fail("找不到公司");
        }
        if (!isCeo(ceo, company)) {
            return ActionResult.fail("只有 CEO 本人能操作公司「" + company.name + "」");
        }
        if (company.bankrupt) {
            return ActionResult.fail("公司已经破产，不能升级");
        }
        try {
            ctx.companies.levelUp(company, ctx.settings);
            StringBuilder message = new StringBuilder();
            message.append("公司「").append(company.name).append("」升到 ").append(company.level).append(" 级！");
            if (company.level < 5) {
                long fee = CompanyStore.LEVEL_UP_FEE[company.level];
                message.append("下一级（").append(company.level + 1).append(" 级）只需升级费 ")
                        .append(Cents.format(fee)).append(" 币（公司现金够就能升，不看员工/营收）。");
            } else {
                message.append("已经是最高等级（5 级），员工上限 ").append(company.maxEmployees()).append(" 人。");
            }
            return ActionResult.ok(message.toString());
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ActionResult.fail(reason(error));
        } catch (RuntimeException error) {
            Log.warn("升级公司失败（" + company.name + "）", error);
            return ActionResult.fail("升级失败：" + reason(error));
        }
    }

    /** 改公司名（只有 CEO 本人能改）。 */
    public static ActionResult rename(AimcContext ctx, Account ceo, CompanyStore.Company company, String newName) {
        if (ctx == null || ceo == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        if (company == null) {
            return ActionResult.fail("找不到公司");
        }
        if (!isCeo(ceo, company)) {
            return ActionResult.fail("只有 CEO 本人能改公司「" + company.name + "」的名字");
        }
        try {
            String old = company.name;
            ctx.companies.rename(company, newName);
            return ActionResult.ok("公司已改名：" + old + " → " + company.name);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ActionResult.fail(reason(error));
        }
    }

    /**
     * 合伙人/投资人入股：把钱投进公司，按估值换股份。
     */
    public static ActionResult invest(AimcContext ctx, Account investor, CompanyStore.Company company, long amountCents) {
        if (ctx == null || investor == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        if (company == null) {
            return ActionResult.fail("找不到公司");
        }
        try {
            long shares = ctx.companies.invest(company, investor, amountCents, ctx.ledger);
            long mine = company.sharesOf(investor.uuid);
            double percent = company.sharesTotal <= 0L ? 0.0 : mine * 100.0 / company.sharesTotal;
            return ActionResult.ok("向「" + company.name + "」投资 " + Cents.format(amountCents)
                    + " 币，获得 " + shares + " 股；你现在持有 " + mine + " 股（约 "
                    + String.format(java.util.Locale.ROOT, "%.1f", percent) + "%），公司现金 "
                    + Cents.format(company.cashCents) + " 币");
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ActionResult.fail(reason(error));
        } catch (RuntimeException error) {
            Log.warn("公司投资失败（" + company.name + "）", error);
            return ActionResult.fail("投资失败：" + reason(error));
        }
    }

    /** 股东列表文本（CEO + 合伙人 + 公众流通股）。 */
    public static java.util.List<String> shareholders(AimcContext ctx, CompanyStore.Company company) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (ctx == null || company == null) {
            return lines;
        }
        long total = Math.max(1L, company.sharesTotal);
        for (java.util.Map.Entry<String, Long> entry : company.holders.entrySet()) {
            long shares = entry.getValue() == null ? 0L : entry.getValue();
            if (shares <= 0L) {
                continue;
            }
            String name;
            if ("public".equals(entry.getKey())) {
                name = "公众流通股";
            } else {
                Account holder = ctx.accounts.get(entry.getKey());
                name = holder == null ? entry.getKey() : holder.name;
                if (entry.getKey().equals(company.ceoUuid)) {
                    name = name + "（CEO）";
                }
            }
            lines.add(name + " 持有 " + shares + " 股（" + String.format(java.util.Locale.ROOT, "%.1f", shares * 100.0 / total) + "%）");
        }
        return lines;
    }

    // ══════════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════════

    /** 中文行业名 / 英文代码 → 内部代码；不认识的返回 null。 */
    private static String resolveSector(String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : CompanyStore.SECTORS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(text) || entry.getValue().equals(text)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String sectorChoices() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : CompanyStore.SECTORS.entrySet()) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(entry.getValue()).append('(').append(entry.getKey()).append(')');
        }
        return sb.toString();
    }

    private static boolean isCeo(Account account, CompanyStore.Company company) {
        return account != null && company != null && company.ceoUuid != null
                && company.ceoUuid.equals(account.uuid);
    }

    /** 股票 → 玩家公司；NPC 股票（companyId 为空）返回 null。 */
    private static CompanyStore.Company resolveCompany(AimcContext ctx, StockStore.Stock stock) {
        if (ctx == null || stock == null) {
            return null;
        }
        CompanyStore.Company company = null;
        if (stock.companyId != null && !stock.companyId.isEmpty()) {
            company = ctx.companies.get(stock.companyId);
        }
        if (company == null) {
            // 兼容老数据：玩家公司股票可能只记了代码
            company = ctx.companies.byCode(stock.code);
        }
        return company;
    }

    /** 最近 days 条历史求和（历史不足几天就按有的算）。 */
    private static long sumRecent(List<Long> history, int days) {
        if (history == null || history.isEmpty() || days <= 0) {
            return 0L;
        }
        long sum = 0L;
        int from = Math.max(0, history.size() - days);
        for (int i = from; i < history.size(); i++) {
            Long value = history.get(i);
            if (value != null) {
                sum = Math.addExact(sum, value);
            }
        }
        return sum;
    }

    /** 只给"NPC 市值"估算用：溢出时返回 0 而不是抛异常。 */
    private static long safeMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return 0L;
        }
    }

    /** 写一条新闻；失败只记日志（不影响结算）。 */
    private static void publishNews(AimcContext ctx, String category, String headline, String body) {
        try {
            // 统一走 NewsEngine.publish（内部写到 news.json，带长度截断），不挂价格事件。
            NewsEngine.publish(ctx, category, headline, body);
        } catch (RuntimeException error) {
            Log.warn("写新闻失败（" + headline + "）", error);
        }
    }

    private static void addNotice(AimcContext ctx, String text) {
        try {
            ctx.notices.add(text);
        } catch (RuntimeException error) {
            Log.warn("写通知失败（" + text + "）", error);
        }
    }

    private static String reason(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
