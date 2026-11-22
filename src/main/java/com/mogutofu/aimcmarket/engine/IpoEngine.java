package com.mogutofu.aimcmarket.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

/**
 * IPO 引擎：上市条件校验、承销费、招股书、1 个交易日后的挂牌、锁定期与退市。
 *
 * <p>纯 Java 静态方法，不 import 任何 Minecraft 类，也不起线程；可预期失败一律
 * {@link ActionResult#fail(String)}，意外异常在这里吃掉（记日志），绝不抛给命令层或 tick 循环。</p>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>这里的"天"全部是<b>交易日</b>；IPO 递交后要等 1 个交易日才能 confirm。</li>
 *   <li>发行价 = 公司估值 / 发行后总股本（等价于任务书的 {@code valuation / (原股本 / (1 - 流通比例))}），
 *       真正的股本扩张/募资由 {@link CompanyStore#listCompany} 落地。</li>
 *   <li>退市判定：玩家公司股票连续 7 个交易日收盘价低于 1.00 币，或公司破产。</li>
 * </ul>
 */
public final class IpoEngine {
    /** 流通比例下限：10%。 */
    public static final double MIN_FLOAT_RATIO = 0.10;
    /** 流通比例上限：49%。 */
    public static final double MAX_FLOAT_RATIO = 0.49;
    /** 没指定流通比例时，展示"发行价预估"用的默认比例。 */
    public static final double DEFAULT_FLOAT_RATIO = 0.25;
    /** 上市要求的最低公司等级。 */
    public static final int MIN_LEVEL = 10;
    /** 上市要求的公司成立时间（交易日）：默认 1 个交易日 = 1 小时。 */
    public static final long MIN_TRADING_DAYS = 1L;
    /** 上市要求的估值下限：50000 币 = 5000000 分。 */
    public static final long MIN_VALUATION_CENTS = 5_000_000L;
    /** CEO 最低持股比例：51%。 */
    public static final double MIN_CEO_STAKE = 0.51;
    /** 上市后 CEO 股份锁定的交易日数。 */
    public static final long LOCK_DAYS = 30L;
    /** 承销费率：估值 × 3%。 */
    public static final double UNDERWRITING_RATE = 0.03;
    /** 承销费下限：100 币 = 10000 分。 */
    public static final long MIN_UNDERWRITING_FEE_CENTS = 10_000L;
    /** 退市股价线：1.00 币 = 100 分。 */
    public static final long LOW_PRICE_CENTS = 100L;
    /** 连续多少个交易日低于退市线才退市。 */
    public static final int LOW_PRICE_DELIST_DAYS = 7;
    /** 玩家公司股票的日波动率。 */
    public static final double PLAYER_STOCK_VOLATILITY = 0.025;
    /** 招股书各字段截断长度。 */
    private static final int MAX_TITLE_CHARS = 60;
    private static final int MAX_BODY_CHARS = 500;

    private IpoEngine() {
    }

    // ══════════════════════════════════════════════════════════════
    // 上市条件
    // ══════════════════════════════════════════════════════════════

    /**
     * 逐条判断上市条件（任务书 3.9，全部满足才算）。
     *
     * <ol>
     *   <li>公司等级 ≥ 3；</li>
     *   <li>创建 ≥ 7 个交易日；</li>
     *   <li>最近 7 交易日平均日净利 &gt; 0；</li>
     *   <li>估值 ≥ 50000 币；</li>
     *   <li>CEO 持股 ≥ 51%；</li>
     *   <li>公司现金 ≥ 0。</li>
     * </ol>
     *
     * @param whyNot 不满足时写入<b>第一条</b>不满足的中文原因（可以为 null）
     */
    public static boolean canApply(AimcContext ctx, CompanyStore.Company company, StringBuilder whyNot) {
        if (ctx == null || company == null) {
            appendWhyNot(whyNot, "找不到公司");
            return false;
        }
        try {
            if (company.bankrupt) {
                appendWhyNot(whyNot, "公司已经破产，不能上市");
                return false;
            }
            if (company.listed) {
                appendWhyNot(whyNot, "公司已经上市（代码 " + company.code + "）");
                return false;
            }
            long day = ctx.tradingDay();
            if (company.level < MIN_LEVEL) {
                appendWhyNot(whyNot, "公司等级不够：当前 " + company.level + " 级，上市需要 "
                        + MIN_LEVEL + " 级");
                return false;
            }
            long age = day - company.createdDay;
            boolean oldEnough;
            if (company.createdMs > 0L) {
                // 现实时间口径：满 1 小时即可（默认 1 交易日 = 60 分钟）
                long needMs = Math.max(1L, MIN_TRADING_DAYS) * 60L * 60_000L;
                oldEnough = System.currentTimeMillis() - company.createdMs >= needMs;
            } else {
                oldEnough = age >= MIN_TRADING_DAYS;
            }
            if (!oldEnough) {
                appendWhyNot(whyNot, "公司成立不足 1 小时（当前 "
                        + Math.max(0L, age) + " 个交易日）");
                return false;
            }
            long ceoShares = company.sharesOf(company.ceoUuid);
            if (!stakeAtLeast(ceoShares, company.sharesTotal, MIN_CEO_STAKE)) {
                appendWhyNot(whyNot, "CEO 持股不足 " + percentOf(MIN_CEO_STAKE)
                        + "（当前 " + percentOf(stakeRatio(ceoShares, company.sharesTotal)) + "）");
                return false;
            }
            if (company.cashCents < 0L) {
                appendWhyNot(whyNot, "公司现金为负（" + Cents.format(company.cashCents)
                        + " 币），先把现金补到 0 以上");
                return false;
            }
            return true;
        } catch (RuntimeException error) {
            Log.warn("IPO 条件校验失败（" + company.name + "）", error);
            appendWhyNot(whyNot, "公司数据异常，暂时不能申请上市");
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 招股书
    // ══════════════════════════════════════════════════════════════

    /**
     * 组装 AI {@code task=prospectus} 的上下文（纯数据，不发起 AI 调用）。
     *
     * @param floatRatio 计划流通比例（不在 10%-49% 内时按默认 25% 展示）
     */
    public static JsonObject buildProspectusContext(AimcContext ctx, CompanyStore.Company company, double floatRatio) {
        JsonObject json = new JsonObject();
        json.addProperty("task", "prospectus");
        if (ctx == null || company == null) {
            return json;
        }
        try {
            double ratio = validFloatRatio(floatRatio) ? floatRatio : DEFAULT_FLOAT_RATIO;
            long day = ctx.tradingDay();
            long ceoShares = company.sharesOf(company.ceoUuid);
            json.addProperty("name", company.name);
            json.addProperty("sector", company.sectorName());
            json.addProperty("level", company.level);
            json.addProperty("employees", company.employees);
            json.addProperty("reputation", company.reputation);
            json.addProperty("valuationCents", company.valuationCents);
            json.addProperty("sharesTotal", company.sharesTotal);
            json.addProperty("revenue7dCents", sumRecent(company.revenueHistory, (int) MIN_TRADING_DAYS));
            json.addProperty("profit7dCents", sumRecent(company.profitHistory, (int) MIN_TRADING_DAYS));
            json.addProperty("floatRatio", ratio);
            json.addProperty("estimatedPriceCents", estimateIpoPrice(company, ratio));
            json.addProperty("ceoStakeRatio", stakeRatio(ceoShares, company.sharesTotal));
            json.addProperty("dividendRatio", company.dividendRatio);
            json.addProperty("createdDay", company.createdDay);
            json.addProperty("currentDay", day);
        } catch (RuntimeException error) {
            Log.warn("组装招股书上下文失败（" + company.name + "）", error);
        }
        return json;
    }

    /** 发行价预估（分/股）= 估值 / 发行后总股本；比例为 0 或非法时夹到 10%-49%。 */
    public static long estimateIpoPrice(CompanyStore.Company company, double floatRatio) {
        if (company == null) {
            return 0L;
        }
        double ratio = Cents.clampDouble(floatRatio, MIN_FLOAT_RATIO, MAX_FLOAT_RATIO);
        long total = Math.max(1L, company.sharesTotal);
        long newTotal = Math.max(total, Math.round(total / (1.0 - ratio)));
        return Math.max(1L, company.valuationCents / newTotal);
    }

    /** 承销费 = max(100 币, 估值 × 3%)。 */
    public static long underwritingFee(long valuationCents) {
        long fee = Cents.percent(Math.max(0L, valuationCents), UNDERWRITING_RATE);
        return Math.max(MIN_UNDERWRITING_FEE_CENTS, fee);
    }

    // ══════════════════════════════════════════════════════════════
    // 申请上市 / 确认上市
    // ══════════════════════════════════════════════════════════════

    /**
     * 递交招股书（{@code /ex ipo apply <流通比例>}）。
     *
     * <p>校验 CEO、流通比例、是否已有 IPO 在流程中、上市条件；收承销费（从公司现金销毁），
     * 记下 ipoPending / ipoApplyDay / ipoFloatRatio / 招股书文本，返回带消息、全服公告和新闻标题的结果。</p>
     *
     * <p>一个 CEO 最多 3 家公司，引擎没有"公司"参数：优先挑名下第一家满足上市条件的公司；
     * 都不满足时用第一家的原因报错。</p>
     *
     * @param prospectus AI 生成的招股书 JSON（可以为 null / 不合法，此时用本地模板）
     */
    public static ActionResult apply(AimcContext ctx, Account ceo, double floatRatio, JsonObject prospectus) {
        if (ctx == null || ceo == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            if (!validFloatRatio(floatRatio)) {
                return ActionResult.fail("流通比例要在 10%-49% 之间（当前 " + percentOf(floatRatio) + "）");
            }
            java.util.List<CompanyStore.Company> owned = ctx.companies.byCeo(ceo.uuid);
            if (owned.isEmpty()) {
                return ActionResult.fail("你还没有公司，先 /ex company create <名字> <行业> 开一家");
            }
            // 已经有招股书在流程中：不允许重复递交
            for (CompanyStore.Company candidate : owned) {
                if (candidate.ipoPending) {
                    return ActionResult.fail("公司「" + candidate.name + "」的招股书已经受理，第 "
                            + (candidate.ipoApplyDay + 1L) + " 个交易日用 /ex ipo confirm 上市");
                }
            }
            // 优先挑第一家满足全部条件的公司；都不满足时保留第一家的原因
            CompanyStore.Company target = null;
            String firstReason = "";
            for (CompanyStore.Company candidate : owned) {
                if (candidate.bankrupt || candidate.listed) {
                    continue;
                }
                StringBuilder whyNot = new StringBuilder();
                if (canApply(ctx, candidate, whyNot)) {
                    target = candidate;
                    break;
                }
                if (firstReason.isEmpty()) {
                    firstReason = whyNot.toString();
                }
            }
            if (target == null) {
                if (firstReason.isEmpty()) {
                    return ActionResult.fail("你名下没有可以申请上市的公司（都已经上市或破产）");
                }
                return ActionResult.fail("还不满足上市条件：" + firstReason);
            }

            long fee = underwritingFee(target.valuationCents);
            if (target.cashCents < fee) {
                return ActionResult.fail("公司现金不足：承销费要 " + Cents.format(fee) + " 币（公司现金 "
                        + Cents.format(target.cashCents) + " 币），先 /ex company deposit 注资");
            }
            long day = ctx.tradingDay();
            // 承销费：从公司现金销毁（失败会抛异常，此时还没改 ipoPending，可以重试）
            ctx.ledger.burn(target, fee, "company.ipoFee", ceo.name, target.name + " IPO 承销费");

            target.ipoPending = true;
            target.ipoApplyDay = day;
            target.ipoFloatRatio = floatRatio;
            target.ipoProspectus = normalizeProspectus(target, floatRatio, prospectus).toString();

            long price = estimateIpoPrice(target, floatRatio);
            long newTotal = Math.max(target.sharesTotal,
                    Math.round(target.sharesTotal / (1.0 - floatRatio)));
            String message = "招股书已受理！公司「" + target.name + "」拟发行 " + percentOf(floatRatio)
                    + " 股份（发行后总股本 " + newTotal + " 股），发行价预估 " + Cents.format(price)
                    + " 币/股，承销费 " + Cents.format(fee) + " 币已从公司现金扣除。1 个交易日后用 /ex ipo confirm 正式上市。";
            String broadcast = target.name + " 公司递交招股书，1 个交易日后上市";
            String newsHeadline = "「" + target.name + "」递交招股书，发行价预估 " + Cents.format(price) + " 币";
            return ActionResult.ok(message, broadcast, newsHeadline);
        } catch (RuntimeException error) {
            Log.warn("IPO 申请失败（" + ceo.name + "）", error);
            return ActionResult.fail("IPO 申请失败：" + reason(error));
        }
    }

    /**
     * 确认上市（{@code /ex ipo confirm}）：必须已递交且至少过了 1 个交易日。
     *
     * <p>挂牌：新建玩家公司股票（代码 P001…，初始价 = 发行价，波动 2.5%，无固定股息），
     * 公司代码回写、ipoPending 清零，写新闻并返回全服广播。</p>
     */
    public static ActionResult confirm(AimcContext ctx, Account ceo) {
        if (ctx == null || ceo == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            CompanyStore.Company company = null;
            for (CompanyStore.Company candidate : ctx.companies.byCeo(ceo.uuid)) {
                if (candidate.ipoPending) {
                    company = candidate;
                    break;
                }
            }
            if (company == null) {
                for (CompanyStore.Company candidate : ctx.companies.byCeo(ceo.uuid)) {
                    if (candidate.listed) {
                        return ActionResult.fail("公司「" + candidate.name + "」已经上市了（代码 "
                                + candidate.code + "）");
                    }
                }
                return ActionResult.fail("没有待确认的招股书，先 /ex ipo apply <流通比例> 申请上市");
            }
            long day = ctx.tradingDay();
            long readyDay = company.ipoApplyDay;
            if (company.listed) {
                company.ipoPending = false;
                return ActionResult.fail("公司已经上市了（代码 " + company.code + "）");
            }
            if (company.bankrupt) {
                company.ipoPending = false;
                return ActionResult.fail("公司已经破产，IPO 自动取消");
            }
            if (!validFloatRatio(company.ipoFloatRatio)) {
                return ActionResult.fail("招股书里的流通比例不合法，请重新 /ex ipo apply 一次");
            }

            // 先占一个代码：万一代码用完（P001-P999）就在这里失败，不留下已稀释的股本。
            String code = ctx.stocks.nextPlayerCode();
            long oldTotal = company.sharesTotal;
            long ipoPrice = ctx.companies.listCompany(company, ceo, company.ipoFloatRatio, day, LOCK_DAYS);

            StockStore.Stock stock = new StockStore.Stock();
            stock.code = code;
            stock.name = company.name;
            stock.sector = company.sectorName();
            stock.initialPriceCents = ipoPrice;
            stock.priceCents = ipoPrice;
            stock.volatility = PLAYER_STOCK_VOLATILITY;
            stock.annualDividendRate = 0.0;
            stock.npc = false;
            stock.companyId = company.id;
            stock.sharesOutstanding = Math.max(1L, company.sharesTotal);
            stock.listedDay = day;
            stock.lockUntilDay = day + LOCK_DAYS;
            stock.industryClimateKey = stock.sector;
            ctx.stocks.addStock(stock);

            company.code = code;
            company.ipoPending = false;

            long issuedShares = Math.max(0L, company.sharesTotal - oldTotal);
            long raised = Cents.mul(ipoPrice, issuedShares);
            String message = "上市成功！公司「" + company.name + "」以代码 " + code + " 登陆交易所，发行价 "
                    + Cents.format(ipoPrice) + " 币/股，募资 " + Cents.format(raised)
                    + " 币已进入公司现金（当前 " + Cents.format(company.cashCents) + " 币）。CEO 股份锁定 "
                    + LOCK_DAYS + " 个交易日（到第 " + (day + LOCK_DAYS) + " 交易日）。";
            String broadcast = company.name + "（" + code + "）今日上市，发行价 " + Cents.format(ipoPrice) + " 币";
            String headline = "「" + company.name + "」（" + code + "）今日上市，发行价 "
                    + Cents.format(ipoPrice) + " 币";
            String body = "公司「" + company.name + "」" + company.sectorName() + "行业，本次发行 "
                    + percentOf(company.ipoFloatRatio) + " 股份（" + issuedShares + " 股），发行后总股本 "
                    + company.sharesTotal + " 股，募资 " + Cents.format(raised) + " 币。";
            publishNews(ctx, "ipo", headline, body);
            return ActionResult.ok(message, broadcast, "");
        } catch (RuntimeException error) {
            Log.warn("IPO confirm 失败（" + ceo.name + "）", error);
            return ActionResult.fail("上市失败：" + reason(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 状态
    // ══════════════════════════════════════════════════════════════

    /** {@code /ex ipo status} 的多行文本：流程状态、六条上市条件、发行价预估、锁定期。 */
    public static ActionResult status(AimcContext ctx, CompanyStore.Company company) {
        if (ctx == null) {
            return ActionResult.fail("市场还没加载好，稍后再试");
        }
        if (company == null) {
            return ActionResult.fail("找不到公司，先用 /ex company info 看看");
        }
        try {
            long day = ctx.tradingDay();
            long total = Math.max(1L, company.sharesTotal);
            long ceoShares = company.sharesOf(company.ceoUuid);
            StringBuilder sb = new StringBuilder(512);
            sb.append("【").append(company.name).append("】").append(company.sectorName())
                    .append("｜").append(company.level).append(" 级｜CEO 持股 ")
                    .append(percentOf(stakeRatio(ceoShares, total))).append('\n');

            if (company.bankrupt) {
                sb.append("状态：已破产，不能上市\n");
                return ActionResult.ok(sb.toString());
            }
            if (company.listed) {
                sb.append("状态：已上市（代码 ").append(company.code).append("）\n");
                if (company.lockUntilDay >= 0L) {
                    long remaining = Math.max(0L, company.lockUntilDay - day);
                    sb.append("锁定期：CEO 股份锁定到第 ").append(company.lockUntilDay).append(" 交易日");
                    sb.append(remaining > 0L ? "（还剩 " + remaining + " 个交易日）" : "（已解锁）").append('\n');
                }
                return ActionResult.ok(sb.toString());
            }
            if (company.ipoPending) {
                long readyDay = company.ipoApplyDay + 1L;
                sb.append("状态：招股书已受理（第 ").append(company.ipoApplyDay).append(" 交易日递交，流通比例 ")
                        .append(percentOf(company.ipoFloatRatio)).append("）\n");
                if (day < readyDay) {
                    sb.append("上市确认：第 ").append(readyDay).append(" 个交易日起可用 /ex ipo confirm（还要 ")
                            .append(readyDay - day).append(" 个交易日）\n");
                } else {
                    sb.append("上市确认：现在就可以 /ex ipo confirm\n");
                }
            } else {
                sb.append("状态：未申请上市（/ex ipo apply <流通比例 10%-49%>）\n");
            }

            sb.append("上市条件：\n");
            appendCondition(sb, company.level >= MIN_LEVEL,
                    "等级 ≥ " + MIN_LEVEL + "（当前 " + company.level + " 级）");
            long age = Math.max(0L, day - company.createdDay);
            boolean oldEnough = company.createdMs > 0L
                    ? System.currentTimeMillis() - company.createdMs >= Math.max(1L, MIN_TRADING_DAYS) * 60L * 60_000L
                    : age >= MIN_TRADING_DAYS;
            appendCondition(sb, oldEnough,
                    "成立 ≥ 1 小时（当前 " + Math.max(0L, age) + " 个交易日）");
            appendCondition(sb, stakeAtLeast(ceoShares, total, MIN_CEO_STAKE),
                    "CEO 持股 ≥ " + percentOf(MIN_CEO_STAKE) + "（当前 "
                            + percentOf(stakeRatio(ceoShares, total)) + "）");
            appendCondition(sb, company.cashCents >= 0L,
                    "公司现金 ≥ 0（当前 " + Cents.format(company.cashCents) + " 币）");

            double ratio = validFloatRatio(company.ipoFloatRatio) ? company.ipoFloatRatio : DEFAULT_FLOAT_RATIO;
            sb.append("发行价预估：流通 ").append(percentOf(ratio)).append(" 约 ")
                    .append(Cents.format(estimateIpoPrice(company, ratio))).append(" 币/股｜承销费约 ")
                    .append(Cents.format(underwritingFee(company.valuationCents))).append(" 币");
            return ActionResult.ok(sb.toString());
        } catch (RuntimeException error) {
            Log.warn("IPO 状态查询失败（" + company.name + "）", error);
            return ActionResult.fail("查询 IPO 状态失败：" + reason(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 交易日：锁定期提醒 / 退市
    // ══════════════════════════════════════════════════════════════

    /**
     * 每个交易日：检查玩家公司股票的退市条件，并提醒锁定期结束。
     *
     * <ul>
     *   <li>股价 &lt; 1.00 币连续 7 个交易日（{@code stock.lowPriceDays} 累加，回到线上清零）→ 退市；</li>
     *   <li>公司破产但股票还挂在交易所 → 一并摘牌；</li>
     *   <li>退市：{@code CompanyStore.delist} + {@code StockStore.removeStock} + CEO 信用 -100 + 通知 + 新闻。</li>
     * </ul>
     *
     * <p>IPO 流程中的公司（{@code ipoPending}）不需要在这里处理。</p>
     */
    public static void onTradingDay(AimcContext ctx) {
        if (ctx == null) {
            return;
        }
        long day = ctx.tradingDay();
        for (StockStore.Stock stock : ctx.stocks.playerStocks()) {
            if (stock == null) {
                continue;
            }
            try {
                checkPlayerStock(ctx, stock, day);
            } catch (RuntimeException error) {
                Log.warn("退市检查失败（" + stock.code + "）", error);
            }
        }
    }

    private static void checkPlayerStock(AimcContext ctx, StockStore.Stock stock, long day) {
        CompanyStore.Company company = null;
        if (stock.companyId != null && !stock.companyId.isEmpty()) {
            company = ctx.companies.get(stock.companyId);
        }
        if (company == null) {
            company = ctx.companies.byCode(stock.code);
        }
        if (company == null) {
            // 公司数据已经不存在：摘掉孤儿股票，避免一只永远没有财报/分红的幽灵股继续交易
            ctx.stocks.removeStock(stock.code);
            addNotice(ctx, "【退市】「" + safeStockName(stock) + "」（" + stock.code
                    + "）找不到对应公司，已停止交易。");
            Log.warn("孤儿股票已摘牌：" + stock.code);
            return;
        }

        if (!company.bankrupt) {
            if (stock.priceCents < LOW_PRICE_CENTS) {
                stock.lowPriceDays = stock.lowPriceDays + 1;
            } else if (stock.lowPriceDays != 0) {
                stock.lowPriceDays = 0;
            }
        }

        boolean bankrupt = company.bankrupt;
        boolean stale = !company.listed;
        boolean lowPrice = stock.lowPriceDays >= LOW_PRICE_DELIST_DAYS;
        if (!bankrupt && !stale && !lowPrice) {
            // 锁定期结束的第一个交易日提醒一次
            if (stock.lockUntilDay >= 0L && day == stock.lockUntilDay + 1L) {
                addNotice(ctx, "【上市】「" + company.name + "」（" + stock.code
                        + "）CEO 股份锁定期已结束，可以正常交易。");
            }
            return;
        }
        delist(ctx, stock, company, bankrupt, lowPrice);
    }

    /** 退市：摘牌 + 股份按 0 结算 + CEO 信用 -100 + 通知 + 新闻。 */
    private static void delist(AimcContext ctx, StockStore.Stock stock, CompanyStore.Company company,
                               boolean bankrupt, boolean lowPrice) {
        String code = stock.code;
        String name = company.name;
        long lastPrice = stock.priceCents;
        long day = ctx.tradingDay();

        ctx.companies.delist(company, day);
        ctx.stocks.removeStock(code);

        Account ceo = ctx.accounts.get(company.ceoUuid);
        String creditText = "";
        if (ceo != null) {
            ceo.adjustCredit(-100.0, 300.0, 850.0);
            String ceoName = ceo.name == null || ceo.name.isBlank() ? "CEO" : ceo.name;
            creditText = "，CEO " + ceoName + " 信用 -100";
        }
        String reason = bankrupt ? "公司破产"
                : (lowPrice ? ("股价连续 " + LOW_PRICE_DELIST_DAYS + " 个交易日低于 1.00 币") : "公司已退出上市状态");

        addNotice(ctx, "【退市】「" + name + "」（" + code + "）因" + reason + "退市，股份按 0 结算"
                + (creditText.isEmpty() ? "" : "，CEO 信用 -100") + "。");
        publishNews(ctx, bankrupt ? "bankrupt" : "company",
                "「" + name + "」（" + code + "）退市：" + reason,
                "该股最后价 " + Cents.format(lastPrice) + " 币，退市后股份按 0 结算" + creditText + "。");
        Log.info("公司退市：" + name + "（" + code + "，原因：" + reason + "）");
    }

    // ══════════════════════════════════════════════════════════════
    // 招股书 / 小工具
    // ══════════════════════════════════════════════════════════════

    /** AI 招股书校验：title/summary 是字符串；不合法就用本地模板。 */
    private static JsonObject normalizeProspectus(CompanyStore.Company company, double floatRatio,
                                                  JsonObject prospectus) {
        String title = readText(prospectus, "title");
        String summary = readText(prospectus, "summary");
        if (title != null && summary != null) {
            JsonObject doc = new JsonObject();
            doc.addProperty("source", "ai");
            doc.addProperty("title", cut(title, MAX_TITLE_CHARS));
            doc.addProperty("summary", cut(summary, MAX_BODY_CHARS));
            doc.addProperty("risk", cut(readText(prospectus, "risk"), MAX_BODY_CHARS));
            doc.addProperty("useOfFunds", cut(readText(prospectus, "useOfFunds"), MAX_BODY_CHARS));
            return doc;
        }
        return localProspectus(company, floatRatio);
    }

    /** 本地招股书模板：数字全部来自公司真实估值/股本。 */
    private static JsonObject localProspectus(CompanyStore.Company company, double floatRatio) {
        long total = Math.max(1L, company.sharesTotal);
        long newTotal = Math.max(total, Math.round(total / (1.0 - floatRatio)));
        long newShares = Math.max(0L, newTotal - total);
        long price = estimateIpoPrice(company, floatRatio);
        long raise = Cents.mul(price, newShares);
        JsonObject doc = new JsonObject();
        doc.addProperty("source", "local");
        doc.addProperty("title", company.name + " 招股说明书");
        doc.addProperty("summary", "「" + company.name + "」为" + company.sectorName() + "行业 " + company.level
                + " 级公司，当前估值 " + Cents.format(company.valuationCents) + " 币；本次拟发行 "
                + percentOf(floatRatio) + " 股份（" + newShares + " 股，发行后总股本 " + newTotal
                + " 股），发行价预估 " + Cents.format(price) + " 币/股，预计募资 " + Cents.format(raise) + " 币。");
        doc.addProperty("risk", "行业景气波动、维护费与员工成本上升、上市后 " + LOCK_DAYS
                + " 个交易日内 CEO 股份锁定带来的流动性风险。");
        doc.addProperty("useOfFunds", "募资用于扩建产能、招聘员工与补充公司流动资金。");
        doc.addProperty("ipoPriceCents", price);
        doc.addProperty("floatRatio", floatRatio);
        doc.addProperty("sharesTotal", newTotal);
        return doc;
    }

    private static String readText(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = element.getAsString().replace('\n', ' ').replace('\r', ' ').trim();
        return text.isEmpty() ? null : text;
    }

    private static String cut(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static boolean validFloatRatio(double ratio) {
        if (Double.isNaN(ratio)) {
            return false;
        }
        return ratio >= MIN_FLOAT_RATIO && ratio <= MAX_FLOAT_RATIO;
    }

    /** 持股比例（0-1）；总股本为 0 时返回 0。 */
    private static double stakeRatio(long shares, long total) {
        if (total <= 0L) {
            return 0.0;
        }
        return (double) Math.max(0L, shares) / (double) total;
    }

    /** 持股比例是否不低于 ratio（用 basis point 整数比较，避免浮点边界问题）。 */
    private static boolean stakeAtLeast(long shares, long total, double ratio) {
        if (total <= 0L) {
            return false;
        }
        long requiredBp = Math.round(ratio * 10_000.0);
        try {
            return Math.multiplyExact(Math.max(0L, shares), 10_000L) >= Math.multiplyExact(total, requiredBp);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static String percentOf(double ratio) {
        return Cents.formatPercent(Double.isNaN(ratio) ? 0.0 : ratio);
    }

    private static void appendCondition(StringBuilder sb, boolean ok, String text) {
        sb.append(ok ? "√ " : "× ").append(text).append('\n');
    }

    private static void appendWhyNot(StringBuilder whyNot, String reason) {
        if (whyNot != null && whyNot.length() == 0) {
            whyNot.append(reason);
        }
    }

    private static long sumRecent(java.util.List<Long> history, int days) {
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

    private static String safeStockName(StockStore.Stock stock) {
        return stock.name == null || stock.name.isBlank() ? stock.code : stock.name;
    }

    /** 写一条新闻；失败只记日志。 */
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
