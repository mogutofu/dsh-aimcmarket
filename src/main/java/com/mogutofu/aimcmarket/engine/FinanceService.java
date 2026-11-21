package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.BalanceAccount;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.FundStore;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 金融引擎：股票交易、基金申赎、银行存款/贷款、利息与分红。
 *
 * <p>纯 Java 静态方法，不碰 Minecraft，所有可预期失败返回 {@link ActionResult#fail(String)}，
 * 意外异常也在这里吃掉（记日志 + 返回失败），绝不抛给命令层。</p>
 *
 * <h2>记账口径</h2>
 * <ul>
 *   <li><b>股票买入</b>：玩家钱包 {@code -总额}，做市商 {@code +成交额}，手续费池 {@code +手续费}，
 *       政府预算 {@code +交易税}；四腿相加为 0。</li>
 *   <li><b>股票卖出</b>：玩家钱包 {@code +净到账}，做市商 {@code -成交额}，手续费池 {@code +手续费}，
 *       政府预算 {@code +交易税}；同样四腿相加为 0。</li>
 *   <li><b>基金</b>：只排队，钱由 {@link FundStore} 在下一个 market tick 按净值真实转账
 *       （申购费进手续费池，赎回费从基金账户出）。</li>
 *   <li><b>银行</b>：活期/定期利息由系统增发（{@code ledger.issue}），贷款本金增发、还本销毁，
 *       和 {@link com.mogutofu.aimcmarket.data.BankStore} 的口径保持一致。</li>
 *   <li><b>NPC 股票分红</b>：系统增发（NPC 公司是"凭空盈利"的模拟公司，对应任务书的年化股息率），
 *       玩家公司分红则从公司现金出（{@link CompanyStore#payDividend}）。</li>
 * </ul>
 */
public final class FinanceService {
    /** 股票手续费率：成交额的 0.2%。 */
    public static final double STOCK_FEE_RATE = 0.002;
    /** 股票手续费下限：1 币 = 100 分。 */
    public static final long STOCK_FEE_MIN_CENTS = Cents.COIN;
    /** 分红周期：每 7 个交易日。 */
    public static final long DIVIDEND_PERIOD_DAYS = 7L;

    /** 1 份 = 1000 unitsMilli。 */
    private static final long UNITS_MILLI_PER_UNIT = 1_000L;
    /** 净值：1 币 = 1_000_000 navMicroCents。 */
    private static final long NAV_MICRO_PER_COIN = 1_000_000L;

    private FinanceService() {
    }

    // ══════════════════════════════════════════════════════════════
    // 股票
    // ══════════════════════════════════════════════════════════════

    /**
     * 买入股票（T+0，整数股）。
     *
     * <p>手续费 = max(1 币, 成交额 × 0.2%)；交易税 = 成交额 × 政府有效交易税率。</p>
     */
    public static ActionResult buyStock(AimcContext ctx, Account account, String code, long shares) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            StockStore.Stock stock = ctx.stocks.stock(code);
            if (stock == null) {
                return ActionResult.fail("没有这只股票：" + cleanCode(code) + "（可交易代码见 /ex stock list）");
            }
            if (shares <= 0L) {
                return ActionResult.fail("股数必须是正整数（当前 " + shares + "）");
            }
            if (shares > 100_000_000L) {
                return ActionResult.fail("单笔股数太大，请拆成多笔");
            }
            if (ctx.settings.adminFreeze) {
                return ActionResult.fail("市场已被管理员冻结，暂时不能买入股票");
            }
            long price = stock.priceCents;
            if (price <= 0L) {
                return ActionResult.fail(stock.name + " 当前价格异常，交易暂停");
            }

            long gross;
            try {
                gross = Math.multiplyExact(price, shares);
            } catch (ArithmeticException overflow) {
                return ActionResult.fail("金额超出可交易范围，请减少股数");
            }
            double taxRate = ctx.government.effectiveTradeTax();
            long fee = stockFee(gross);
            long tax = Cents.percent(gross, taxRate);
            long total = Math.addExact(Math.addExact(gross, fee), tax);

            if (account.balanceCents() < total) {
                return ActionResult.fail("余额不足：需要 " + Cents.format(total) + " 币（成交额 " + Cents.format(gross)
                        + " + 手续费 " + Cents.format(fee) + " + 交易税 " + Cents.format(tax) + "），钱包只有 "
                        + Cents.format(account.balanceCents()) + " 币");
            }
            BalanceAccount maker = ctx.ledger.find(Ledger.MAKER);
            BalanceAccount feePool = ctx.ledger.find(Ledger.FEE_POOL);
            BalanceAccount budget = ctx.ledger.find(Ledger.GOV_BUDGET);
            if (maker == null || feePool == null || budget == null) {
                return ActionResult.fail("记账账户还没就绪，交易失败（请联系管理员）");
            }

            List<Ledger.Leg> legs = new ArrayList<>(4);
            legs.add(Ledger.leg(account, -total));
            legs.add(Ledger.leg(maker, gross));
            if (fee > 0L) {
                legs.add(Ledger.leg(feePool, fee));
            }
            if (tax > 0L) {
                legs.add(Ledger.leg(budget, tax));
            }
            ctx.ledger.post("stock.buy", actorName(account),
                    "买入 " + stock.name + "（" + stock.code + "）" + shares + " 股", legs.toArray(new Ledger.Leg[0]));

            // 成本按"实际现金流出"记（含手续费和税），卖出时按比例回冲。
            account.addStock(stock.code, shares, total, ctx.tick());
            account.lastTradeAtMs = ctx.nowMs();

            ctx.market.addStockImpact(stock.code, gross);
            ctx.market.recordVolume(ctx.tradingDay(), gross);

            Account.StockHolding holding = account.stocks.get(stock.code);
            long nowShares = holding == null ? shares : holding.shares;
            long nowCost = holding == null ? total : holding.costCents;
            long avgCost = nowShares > 0L ? Cents.mulDiv(nowCost, 1L, nowShares) : 0L;

            return ActionResult.ok("买入 " + stock.name + "（" + stock.code + "）" + formatShares(shares)
                    + " 股，成交价 " + Cents.format(price) + " 币/股，成交额 " + Cents.format(gross)
                    + " 币；手续费 " + Cents.format(fee) + " 币，交易税 " + Cents.format(tax)
                    + " 币（" + Cents.formatPercent(taxRate) + "），共扣 " + Cents.format(total)
                    + " 币。余额 " + Cents.format(account.balanceCents()) + " 币，持仓 " + formatShares(nowShares)
                    + " 股（成本均价 " + Cents.format(avgCost) + " 币）。");
        } catch (RuntimeException error) {
            Log.warn("买入股票失败（" + actorName(account) + " " + cleanCode(code) + " " + shares + " 股）", error);
            return ActionResult.fail("买入失败：" + brief(error));
        }
    }

    /** 卖出股票（T+0，整数股），成交额扣手续费和交易税后到账。 */
    public static ActionResult sellStock(AimcContext ctx, Account account, String code, long shares) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            StockStore.Stock stock = ctx.stocks.stock(code);
            if (stock == null) {
                return ActionResult.fail("没有这只股票：" + cleanCode(code) + "（可交易代码见 /ex stock list）");
            }
            if (shares <= 0L) {
                return ActionResult.fail("股数必须是正整数（当前 " + shares + "）");
            }
            long price = stock.priceCents;
            if (price <= 0L) {
                return ActionResult.fail(stock.name + " 当前价格异常，交易暂停");
            }
            Account.StockHolding holding = account.stocks.get(stock.code);
            long hasShares = holding == null ? 0L : holding.shares;
            if (hasShares < shares) {
                return ActionResult.fail("持仓不足：" + stock.name + " 只有 " + formatShares(hasShares) + " 股，卖不了 "
                        + formatShares(shares) + " 股");
            }

            long gross;
            try {
                gross = Math.multiplyExact(price, shares);
            } catch (ArithmeticException overflow) {
                return ActionResult.fail("金额超出可交易范围，请减少股数");
            }
            double taxRate = ctx.government.effectiveTradeTax();
            long fee = stockFee(gross);
            long tax = Cents.percent(gross, taxRate);
            long net = gross - fee - tax;
            if (net <= 0L) {
                return ActionResult.fail("成交额 " + Cents.format(gross) + " 币不够付手续费（" + Cents.format(fee)
                        + " 币）和交易税（" + Cents.format(tax) + " 币），卖出去还要倒贴，已取消");
            }
            BalanceAccount maker = ctx.ledger.find(Ledger.MAKER);
            BalanceAccount feePool = ctx.ledger.find(Ledger.FEE_POOL);
            BalanceAccount budget = ctx.ledger.find(Ledger.GOV_BUDGET);
            if (maker == null || feePool == null || budget == null) {
                return ActionResult.fail("记账账户还没就绪，交易失败（请联系管理员）");
            }

            // 成本按比例回冲：卖出部分对应的历史成本
            long soldCost = Cents.mulDiv(holding.costCents, shares, holding.shares);

            List<Ledger.Leg> legs = new ArrayList<>(4);
            legs.add(Ledger.leg(account, net));
            legs.add(Ledger.leg(maker, -gross));
            if (fee > 0L) {
                legs.add(Ledger.leg(feePool, fee));
            }
            if (tax > 0L) {
                legs.add(Ledger.leg(budget, tax));
            }
            ctx.ledger.post("stock.sell", actorName(account),
                    "卖出 " + stock.name + "（" + stock.code + "）" + shares + " 股", legs.toArray(new Ledger.Leg[0]));

            account.addStock(stock.code, -shares, -soldCost, ctx.tick());
            account.lastTradeAtMs = ctx.nowMs();

            ctx.market.addStockImpact(stock.code, -gross);
            ctx.market.recordVolume(ctx.tradingDay(), gross);

            Account.StockHolding after = account.stocks.get(stock.code);
            long leftShares = after == null ? 0L : after.shares;
            long leftCost = after == null ? 0L : after.costCents;
            long avgCost = leftShares > 0L ? Cents.mulDiv(leftCost, 1L, leftShares) : 0L;

            return ActionResult.ok("卖出 " + stock.name + "（" + stock.code + "）" + formatShares(shares)
                    + " 股，成交价 " + Cents.format(price) + " 币/股，成交额 " + Cents.format(gross)
                    + " 币；手续费 " + Cents.format(fee) + " 币，交易税 " + Cents.format(tax)
                    + " 币（" + Cents.formatPercent(taxRate) + "），到账 " + Cents.format(net)
                    + " 币。余额 " + Cents.format(account.balanceCents()) + " 币，剩余持仓 "
                    + formatShares(leftShares) + " 股"
                    + (leftShares > 0L ? "（成本均价 " + Cents.format(avgCost) + " 币）" : "") + "。");
        } catch (RuntimeException error) {
            Log.warn("卖出股票失败（" + actorName(account) + " " + cleanCode(code) + " " + shares + " 股）", error);
            return ActionResult.fail("卖出失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 基金（申赎都按下一个 market tick 的净值成交）
    // ══════════════════════════════════════════════════════════════

    /**
     * 申购基金：只排队，不立即扣钱；下一个 market tick 由 {@link FundStore} 按当期净值成交并转账。
     *
     * @param amountCents 申购金额（分）
     */
    public static ActionResult buyFund(AimcContext ctx, Account account, String code, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            FundStore.Fund fund = ctx.funds.fund(code);
            if (fund == null) {
                return ActionResult.fail("没有这只基金：" + cleanCode(code) + "（可看看 /ex fund list）");
            }
            if (amountCents <= 0L) {
                return ActionResult.fail("申购金额必须大于 0（单位：分）");
            }
            if (ctx.settings.adminFreeze) {
                return ActionResult.fail("市场已被管理员冻结，暂时不能申购基金");
            }
            if (account.balanceCents() < amountCents) {
                return ActionResult.fail("余额不足：申购 " + Cents.format(amountCents) + " 币，钱包只有 "
                        + Cents.format(account.balanceCents()) + " 币");
            }
            long fee = Cents.percent(amountCents, ctx.settings.fundSubscribeFee);
            long net = amountCents - fee;
            long estimateUnits = ctx.funds.unitsForAmount(fund.code, net);

            ctx.funds.enqueueBuy(nextOrderId("fb", ctx), account, fund.code, amountCents, ctx.tick());

            return ActionResult.ok("已提交申购：" + fund.name + "（" + fund.code + "）" + Cents.format(amountCents)
                    + " 币，下一个市场 tick 按净值成交（约 " + ctx.settings.marketTickMinutes
                    + " 分钟）；当前净值 " + ctx.funds.formatNav(fund.code) + "，扣 "
                    + Cents.formatPercent(ctx.settings.fundSubscribeFee) + " 申购费 "
                    + Cents.format(fee) + " 币后预计买入 " + formatUnits(estimateUnits)
                    + " 份。提交时不扣款，成交时才从钱包划走，可在 /ex fund info " + fund.code + " 查看待成交。");
        } catch (RuntimeException error) {
            Log.warn("申购基金失败（" + actorName(account) + " " + cleanCode(code) + "）", error);
            return ActionResult.fail("申购失败：" + brief(error));
        }
    }

    /**
     * 赎回基金：只排队；下一个 market tick 按当期净值成交，扣赎回费（持有不足 1 交易日另加赎回费）。
     *
     * @param unitsMilli 份额 × 1000
     */
    public static ActionResult sellFund(AimcContext ctx, Account account, String code, long unitsMilli) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            FundStore.Fund fund = ctx.funds.fund(code);
            if (fund == null) {
                return ActionResult.fail("没有这只基金：" + cleanCode(code) + "（可看看 /ex fund list）");
            }
            if (unitsMilli <= 0L) {
                return ActionResult.fail("赎回份额必须大于 0");
            }
            long held = account.fundUnitsMilli(fund.code);
            if (held < unitsMilli) {
                return ActionResult.fail("份额不足：" + fund.name + " 只有 " + formatUnits(held) + " 份，赎不了 "
                        + formatUnits(unitsMilli) + " 份");
            }

            long gross = ctx.funds.valueOfUnits(fund.code, unitsMilli);
            long fee = Cents.percent(gross, ctx.settings.fundSubscribeFee);
            Account.FundHolding holding = account.funds.get(fund.code);
            long sinceTick = holding == null ? ctx.tick() : holding.sinceTick;
            boolean early = ctx.tick() - sinceTick < ticksPerDay(ctx);
            long earlyFee = early ? Cents.percent(gross, ctx.settings.fundRedeemEarlyFee) : 0L;
            long net = gross - fee - earlyFee;
            if (net <= 0L) {
                return ActionResult.fail("赎回金额太小（市值 " + Cents.format(gross) + " 币），还不够付手续费，已取消");
            }

            ctx.funds.enqueueSell(nextOrderId("fs", ctx), account, fund.code, unitsMilli, ctx.tick());

            StringBuilder message = new StringBuilder();
            message.append("已提交赎回：").append(fund.name).append("（").append(fund.code).append("）")
                    .append(formatUnits(unitsMilli)).append(" 份，下一个市场 tick 按净值成交；当前净值 ")
                    .append(ctx.funds.formatNav(fund.code)).append("，市值约 ").append(Cents.format(gross))
                    .append(" 币，扣赎回费 ").append(Cents.format(fee)).append(" 币");
            if (early) {
                message.append(" + 持有不足 1 交易日的赎回费 ").append(Cents.format(earlyFee)).append(" 币");
            }
            message.append("，预计到账约 ").append(Cents.format(net)).append(" 币。");
            return ActionResult.ok(message.toString());
        } catch (RuntimeException error) {
            Log.warn("赎回基金失败（" + actorName(account) + " " + cleanCode(code) + "）", error);
            return ActionResult.fail("赎回失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 银行（薄封装 BankStore，只做参数校验 + 中文消息）
    // ══════════════════════════════════════════════════════════════

    /** 活期存入：钱包 → 银行的活期账户。 */
    public static ActionResult deposit(AimcContext ctx, Account account, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            if (amountCents <= 0L) {
                return ActionResult.fail("存入金额必须大于 0（单位：分）");
            }
            if (account.balanceCents() < amountCents) {
                return ActionResult.fail("钱包余额不足（当前 " + Cents.format(account.balanceCents()) + " 币）");
            }
            ctx.bank.deposit(account, amountCents);
            double rate = ctx.bank.currentRate(ctx.government.policyRate, ctx.settings);
            return ActionResult.ok("活期存入 " + Cents.format(amountCents) + " 币，活期余额 "
                    + Cents.format(account.bank.currentCents) + " 币（年化 " + Cents.formatPercent(rate)
                    + "，按分钟计息）；钱包余额 " + Cents.format(account.balanceCents()) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("活期存入失败（" + actorName(account) + "）", error);
            return ActionResult.fail("存入失败：" + brief(error));
        }
    }

    /** 活期取出：银行的活期账户 → 钱包。 */
    public static ActionResult withdraw(AimcContext ctx, Account account, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            if (amountCents <= 0L) {
                return ActionResult.fail("取出金额必须大于 0（单位：分）");
            }
            if (account.bank.currentCents < amountCents) {
                return ActionResult.fail("活期余额不足（活期 " + Cents.format(account.bank.currentCents) + " 币）");
            }
            ctx.bank.withdraw(account, amountCents);
            return ActionResult.ok("活期取出 " + Cents.format(amountCents) + " 币，活期余额 "
                    + Cents.format(account.bank.currentCents) + " 币；钱包余额 "
                    + Cents.format(account.balanceCents()) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("活期取出失败（" + actorName(account) + "）", error);
            return ActionResult.fail("取出失败：" + brief(error));
        }
    }

    /**
     * 新开定期：活期 → 定期。
     *
     * <p>提前支取不在这个引擎里做（AdminCommand 直接调 {@code BankStore.withdrawTerm}）。</p>
     *
     * @param days        7 或 30 交易日
     * @param amountCents 金额（分）
     */
    public static ActionResult termDeposit(AimcContext ctx, Account account, int days, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            if (days != 7 && days != 30) {
                return ActionResult.fail("定期期限只能是 7 或 30 交易日（当前 " + days + "）");
            }
            if (amountCents <= 0L) {
                return ActionResult.fail("定期金额必须大于 0（单位：分）");
            }
            if (account.bank.currentCents < amountCents) {
                return ActionResult.fail("活期余额不足（活期 " + Cents.format(account.bank.currentCents)
                        + " 币），请先 /ex bank deposit 把钱存进活期");
            }
            Account.TermDeposit term = ctx.bank.openTerm(account, amountCents, days, ctx.tradingDay(),
                    ctx.government.policyRate, ctx.settings);
            long estimateInterest = Cents.percent(term.amountCents, term.apr * days / 365.0);
            long daysLeft = Math.max(0L, term.endDay - ctx.tradingDay());
            return ActionResult.ok("定期开立：" + days + " 交易日 " + Cents.format(term.amountCents)
                    + " 币，年化 " + Cents.formatPercent(term.apr) + "，第 " + term.endDay + " 交易日到期（还剩 "
                    + daysLeft + " 交易日），到期利息约 " + Cents.format(estimateInterest)
                    + " 币，本息自动回活期；提前支取按活期利率重算。活期余额 "
                    + Cents.format(account.bank.currentCents) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("开立定期失败（" + actorName(account) + "）", error);
            return ActionResult.fail("开立定期失败：" + brief(error));
        }
    }

    /** 贷款：按信用分和 30 天平均收入算额度，本金由系统增发进钱包。 */
    public static ActionResult takeLoan(AimcContext ctx, Account account, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            if (amountCents <= 0L) {
                return ActionResult.fail("贷款金额必须大于 0（单位：分）");
            }
            long limit = ctx.bank.creditLimit(account, ctx.settings, ctx.nowMs());
            if (limit <= 0L) {
                return ActionResult.fail("当前贷款额度为 0：先多赚点钱（工资/打工/分红都算收入），额度按"
                        + " 30 天平均收入 ×3 计算，上限 5000 币；信用分低于 500 时额度减半。");
            }
            if (amountCents > limit) {
                return ActionResult.fail("超出贷款额度（可借 " + Cents.format(limit) + " 币）");
            }
            Account.Loan loan = ctx.bank.takeLoan(account, amountCents, ctx.tradingDay(),
                    ctx.government.policyRate, ctx.settings, ctx.nowMs());
            long borrowed = account.bank.totalLoanCents();
            return ActionResult.ok("贷款到账：" + Cents.format(amountCents) + " 币已进钱包，年化 "
                    + Cents.formatPercent(loan.apr) + "（政策利率 " + Cents.formatPercent(ctx.government.policyRate)
                    + " + " + Cents.formatPercent(ctx.settings.loanRateSpread) + "），按交易日计息；贷款额度 "
                    + Cents.format(limit) + " 币，已借 " + Cents.format(borrowed) + " 币，信用分 "
                    + formatCredit(account.creditScore) + "。钱包余额 " + Cents.format(account.balanceCents())
                    + " 币。");
        } catch (RuntimeException error) {
            Log.warn("贷款失败（" + actorName(account) + "）", error);
            return ActionResult.fail("贷款失败：" + brief(error));
        }
    }

    /** 手动还贷：钱包扣款销毁，按贷款先后顺序冲抵。 */
    public static ActionResult repayLoan(AimcContext ctx, Account account, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("账户还没加载好，稍后再试");
        }
        try {
            if (amountCents <= 0L) {
                return ActionResult.fail("还款金额必须大于 0（单位：分）");
            }
            if (account.bank.totalLoanCents() <= 0L) {
                return ActionResult.fail("没有未还的贷款");
            }
            long pay = ctx.bank.repayLoan(account, amountCents, ctx.settings);
            return ActionResult.ok("还款 " + Cents.format(pay) + " 币，剩余未还 "
                    + Cents.format(account.bank.totalLoanCents()) + " 币；钱包余额 "
                    + Cents.format(account.balanceCents()) + " 币，信用分 "
                    + formatCredit(account.creditScore) + "。"
                    + (account.bank.totalLoanCents() <= 0L ? "贷款已结清。" : ""));
        } catch (RuntimeException error) {
            Log.warn("还款失败（" + actorName(account) + "）", error);
            return ActionResult.fail("还款失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 定期结算
    // ══════════════════════════════════════════════════════════════

    /**
     * 每个 market tick：活期按分钟计息。
     *
     * @param minutes 这一 tick 经过的现实分钟
     */
    public static void accrueInterest(AimcContext ctx, long minutes) {
        if (ctx == null || minutes <= 0L) {
            return;
        }
        try {
            ctx.bank.accrueCurrentInterest(ctx.accounts, minutes, ctx.government.policyRate, ctx.settings);
        } catch (RuntimeException error) {
            Log.warn("活期计息失败（已跳过本 tick）", error);
        }
    }

    /** 每个交易日：定期到期回活期、贷款计息/自动扣款/逾期。 */
    public static void onTradingDay(AimcContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.bank.onTradingDay(ctx.accounts, ctx.tradingDay(), ctx.government.policyRate,
                    ctx.settings, ctx.nowMs());
        } catch (RuntimeException error) {
            Log.warn("银行日结失败（已跳过本日）", error);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 分红（每 7 交易日）
    // ══════════════════════════════════════════════════════════════

    /**
     * 每 7 个交易日分红一次。
     *
     * <ul>
     *   <li><b>NPC 股票</b>：每股分红 = round(股价 × 年化股息率 × 7/365)（分），
     *       由系统增发（NPC 是模拟公司，对应任务书的年化股息率）；持股不足 1 交易日不发。</li>
     *   <li><b>玩家公司股票</b>：调 {@link CompanyStore#payDividend}，从公司现金里出钱。</li>
     * </ul>
     */
    public static void payDividends(AimcContext ctx) {
        if (ctx == null) {
            return;
        }
        long day = ctx.tradingDay();
        long tick = ctx.tick();
        long ticksPerDay = ticksPerDay(ctx);

        // NPC 上市公司：系统增发
        for (StockStore.Stock stock : ctx.stocks.all()) {
            if (!stock.npc) {
                continue;
            }
            if (!dividendDue(stock.lastDividendDay, day)) {
                continue;
            }
            try {
                payNpcDividend(ctx, stock, day, tick, ticksPerDay);
            } catch (RuntimeException error) {
                // 出错就不推进 lastDividendDay，下个交易日重试
                Log.warn("NPC 股票分红失败（" + stock.code + "）", error);
            }
        }

        // 玩家公司（IPO 上市）：从公司现金出钱
        for (CompanyStore.Company company : ctx.companies.listed()) {
            if (!dividendDue(company.lastDividendDay, day)) {
                continue;
            }
            try {
                long paid = ctx.companies.payDividend(company, day, ctx.accounts);
                if (paid > 0L) {
                    Log.info(company.name + " 分红发放 " + Cents.format(paid) + " 币（第 " + day + " 交易日）");
                }
            } catch (RuntimeException error) {
                Log.warn("玩家公司分红失败（" + company.name + "）", error);
            }
        }
    }

    private static void payNpcDividend(AimcContext ctx, StockStore.Stock stock, long day, long tick, long ticksPerDay) {
        long perShare = Math.round(stock.priceCents * stock.annualDividendRate * (double) DIVIDEND_PERIOD_DAYS / 365.0);
        if (perShare <= 0L) {
            // 股息率为 0 或股价太低：本期算发过，避免每天重复检查
            stock.lastDividendDay = day;
            return;
        }
        long totalPaid = 0L;
        int payees = 0;
        for (Account account : ctx.accounts.all()) {
            Account.StockHolding holding = account.stocks.get(stock.code);
            if (holding == null || holding.shares <= 0L) {
                continue;
            }
            // 持股不足 1 交易日不发
            if (tick - holding.sinceTick < ticksPerDay) {
                continue;
            }
            try {
                long dividend = Math.multiplyExact(perShare, holding.shares);
                if (dividend <= 0L) {
                    continue;
                }
                ctx.ledger.issue(account, dividend, "stock.dividend", "系统",
                        stock.name + " 分红（每股 " + Cents.format(perShare) + " 币）");
                account.recordIncome(dividend);
                totalPaid = Math.addExact(totalPaid, dividend);
                payees++;
            } catch (RuntimeException error) {
                // 单个玩家发不出去不影响其他人
                Log.warn("分红发放失败（" + stock.code + " → " + actorName(account) + "）", error);
            }
        }
        stock.lastDividendDay = day;
        if (totalPaid > 0L) {
            Log.info(stock.name + " 分红：每股 " + Cents.format(perShare) + " 币，"
                    + payees + " 人共 " + Cents.format(totalPaid) + " 币（第 " + day + " 交易日）");
        }
    }

    /** 分红周期判断：从没发过，或者已经满 7 个交易日。 */
    private static boolean dividendDue(long lastDay, long today) {
        return lastDay < 0L || today - lastDay >= DIVIDEND_PERIOD_DAYS;
    }

    // ══════════════════════════════════════════════════════════════
    // 查询辅助（命令层用；多行文本不带 [市场] 前缀）
    // ══════════════════════════════════════════════════════════════

    /** 股票单价（分）；没有这只股票返回 0。 */
    public static long stockUnitPrice(AimcContext ctx, String code) {
        if (ctx == null) {
            return 0L;
        }
        long price = ctx.stocks.priceOf(code);
        return Math.max(0L, price);
    }

    /** {@code /ex stock info <代码>} 的多行文本；viewer 可以为 null。 */
    public static String stockInfo(AimcContext ctx, Account viewer, String code) {
        return safe(() -> {
            StockStore.Stock stock = ctx == null ? null : ctx.stocks.stock(code);
            if (stock == null) {
                return "没有这只股票：" + cleanCode(code) + "\n用 /ex stock list 看可交易的代码";
            }
            List<String> lines = new ArrayList<>(8);
            lines.add(stock.name + "（" + stock.code + "）｜" + stock.sector + "｜"
                    + (stock.npc ? "NPC 上市公司" : "玩家公司上市公司"));
            double vsInitial = stock.initialPriceCents <= 0L
                    ? 0.0 : (double) stock.priceCents / (double) stock.initialPriceCents - 1.0;
            lines.add("价格 " + Cents.format(stock.priceCents) + " 币/股｜较发行价 " + Cents.formatPercent(vsInitial)
                    + "｜波动率 " + Cents.formatPercent(stock.volatility)
                    + "｜年化股息率 " + Cents.formatPercent(stock.annualDividendRate));
            long day = ctx.tradingDay();
            long nextDay = stock.lastDividendDay < 0L ? day : stock.lastDividendDay + DIVIDEND_PERIOD_DAYS;
            String lastPaid = stock.lastDividendDay < 0L ? "还没发过" : "第 " + stock.lastDividendDay + " 交易日";
            lines.add("分红：每 " + DIVIDEND_PERIOD_DAYS + " 交易日一次｜上次 " + lastPaid
                    + "｜预计第 " + nextDay + " 交易日｜每股约 "
                    + Cents.format(Math.round(stock.priceCents * stock.annualDividendRate
                    * (double) DIVIDEND_PERIOD_DAYS / 365.0)) + " 币");
            lines.add("流通股本 " + formatWan(stock.sharesOutstanding) + " 万股｜大盘指数 "
                    + ctx.stocks.formatIndex() + "（7 交易日 "
                    + Cents.formatPercent(ctx.stocks.indexChange7d(day)) + "）");
            StockStore.FinancialReport report = ctx.stocks.latestReport(stock.code);
            if (report == null) {
                lines.add("最近财报：暂无");
            } else {
                StringBuilder reportLine = new StringBuilder();
                reportLine.append("最近财报（第 ").append(report.day).append(" 交易日）：营收 ")
                        .append(Cents.format(report.revenueCents)).append(" 币｜净利 ")
                        .append(Cents.format(report.profitCents)).append(" 币｜展望 ")
                        .append(outlookName(report.outlook));
                if (report.headline != null && !report.headline.isBlank()) {
                    reportLine.append("｜").append(report.headline);
                }
                lines.add(reportLine.toString());
            }
            if (viewer != null) {
                Account.StockHolding holding = viewer.stocks.get(stock.code);
                if (holding == null || holding.shares <= 0L) {
                    lines.add("你的持仓：无");
                } else {
                    long value = safeMul(stock.priceCents, holding.shares);
                    long cost = holding.costCents;
                    long profit = value - cost;
                    double profitRate = cost > 0L ? (double) profit / (double) cost : 0.0;
                    long avgCost = Cents.mulDiv(cost, 1L, holding.shares);
                    lines.add("你的持仓：" + formatShares(holding.shares) + " 股｜成本 " + Cents.format(cost)
                            + " 币｜均价 " + Cents.format(avgCost) + " 币｜市值 " + Cents.format(value)
                            + " 币｜浮动 " + Cents.formatSigned(profit) + " 币（"
                            + Cents.formatPercent(profitRate) + "）");
                }
            }
            return String.join("\n", lines);
        }, "股票信息读取失败");
    }

    /** {@code /ex fund info <代码>} 的多行文本；viewer 可以为 null。 */
    public static String fundInfo(AimcContext ctx, Account viewer, String code) {
        return safe(() -> {
            FundStore.Fund fund = ctx == null ? null : ctx.funds.fund(code);
            if (fund == null) {
                return "没有这只基金：" + cleanCode(code) + "\n用 /ex fund list 看可申购的代码";
            }
            MarketSettings settings = ctx.settings;
            List<String> lines = new ArrayList<>(7);
            lines.add(fund.name + "（" + fund.code + "）｜" + fund.description);
            lines.add("净值 " + ctx.funds.formatNav(fund.code) + " 币/份｜日波动 "
                    + Cents.formatPercent(fund.dailyVol) + "｜目标年化 " + Cents.formatPercent(fund.targetAnnual)
                    + "｜规模 " + Cents.format(fund.cashCents) + " 币");
            lines.add("组合：债券 " + Cents.formatPercent(fund.bondWeight) + " + 股票指数 "
                    + Cents.formatPercent(fund.stockWeight));
            lines.add("申购费 " + Cents.formatPercent(settings.fundSubscribeFee) + "｜赎回费 "
                    + Cents.formatPercent(settings.fundSubscribeFee) + "（持有不足 1 交易日另收 "
                    + Cents.formatPercent(settings.fundRedeemEarlyFee) + "）｜下个 tick 按净值成交");
            if (viewer != null) {
                long units = viewer.fundUnitsMilli(fund.code);
                if (units <= 0L) {
                    lines.add("你的份额：无");
                } else {
                    lines.add("你的份额：" + formatUnits(units) + " 份｜市值 "
                            + Cents.format(ctx.funds.valueOfUnits(fund.code, units)) + " 币");
                }
                long pendingBuy = 0L;
                long pendingSell = 0L;
                int buyCount = 0;
                int sellCount = 0;
                for (FundStore.PendingOrder order : ctx.funds.pending()) {
                    if (order.fundCode == null || !order.fundCode.equalsIgnoreCase(fund.code)) {
                        continue;
                    }
                    if (order.uuid == null || !order.uuid.equals(viewer.uuid)) {
                        continue;
                    }
                    if ("buy".equals(order.type)) {
                        pendingBuy = Math.addExact(pendingBuy, order.amountCents);
                        buyCount++;
                    } else {
                        pendingSell = Math.addExact(pendingSell, order.unitsMilli);
                        sellCount++;
                    }
                }
                if (buyCount > 0 || sellCount > 0) {
                    StringBuilder pendingLine = new StringBuilder("待成交：");
                    if (buyCount > 0) {
                        pendingLine.append(buyCount).append(" 笔申购 ").append(Cents.format(pendingBuy)).append(" 币");
                    }
                    if (buyCount > 0 && sellCount > 0) {
                        pendingLine.append("｜");
                    }
                    if (sellCount > 0) {
                        pendingLine.append(sellCount).append(" 笔赎回 ").append(formatUnits(pendingSell)).append(" 份");
                    }
                    lines.add(pendingLine.toString());
                }
            }
            return String.join("\n", lines);
        }, "基金信息读取失败");
    }

    /** {@code /ex bank info} 的多行文本。 */
    public static String bankInfo(AimcContext ctx, Account account) {
        return safe(() -> {
            if (ctx == null || account == null) {
                return "银行信息不可用（账户还没加载好）";
            }
            MarketSettings settings = ctx.settings;
            double policyRate = ctx.government.policyRate;
            long day = ctx.tradingDay();
            List<String> lines = new ArrayList<>(8);

            lines.add("活期 " + Cents.format(account.bank.currentCents) + " 币｜年化 "
                    + Cents.formatPercent(ctx.bank.currentRate(policyRate, settings)) + "（按分钟计息）");

            if (account.bank.terms.isEmpty()) {
                lines.add("定期：无");
            } else {
                long termTotal = 0L;
                for (Account.TermDeposit term : account.bank.terms) {
                    termTotal = Math.addExact(termTotal, term.amountCents);
                }
                lines.add("定期 " + account.bank.terms.size() + " 笔，共 " + Cents.format(termTotal) + " 币：");
                for (Account.TermDeposit term : account.bank.terms) {
                    long leftDays = term.endDay - day;
                    lines.add("  · " + Cents.format(term.amountCents) + " 币｜年化 "
                            + Cents.formatPercent(term.apr) + "｜第 " + term.startDay + " 交易日开，第 "
                            + term.endDay + " 交易日到期（"
                            + (leftDays <= 0L ? "今天到期" : "还剩 " + leftDays + " 交易日") + "）");
                }
            }

            long loanTotal = account.bank.totalLoanCents();
            if (loanTotal <= 0L) {
                lines.add("贷款：无未还贷款");
            } else {
                lines.add("贷款 " + account.bank.loans.size() + " 笔，剩余 " + Cents.format(loanTotal)
                        + " 币｜年化 " + Cents.formatPercent(ctx.bank.loanRate(policyRate, settings)) + "：");
                for (Account.Loan loan : account.bank.loans) {
                    lines.add("  · 本金 " + Cents.format(loan.principalCents) + " 币｜剩余 "
                            + Cents.format(loan.remainingCents) + " 币｜年化 "
                            + Cents.formatPercent(loan.apr) + "｜逾期 " + loan.overdueDays + " 天");
                }
            }

            long limit = ctx.bank.creditLimit(account, settings, ctx.nowMs());
            long available = Math.max(0L, limit - loanTotal);
            lines.add("信用分 " + formatCredit(account.creditScore) + "｜贷款额度 " + Cents.format(limit)
                    + " 币（已借 " + Cents.format(loanTotal) + "，可借 " + Cents.format(available) + "）");
            lines.add("存款合计 " + Cents.format(account.bank.totalDepositsCents()) + " 币（活期 + 定期）");
            return String.join("\n", lines);
        }, "银行信息读取失败");
    }

    /**
     * 按金额估算赎回份额（unitsMilli，1 份 = 1000）。
     *
     * <p>只是按当前净值的线性换算，不含赎回费；实际成交以成交 tick 的净值为准。</p>
     */
    public static long fundUnitsToSellByAmount(AimcContext ctx, String code, long amountCents) {
        if (ctx == null || amountCents <= 0L) {
            return 0L;
        }
        FundStore.Fund fund = ctx.funds.fund(code);
        if (fund == null || fund.navMicroCents <= 0L) {
            return 0L;
        }
        return Cents.mulDiv(amountCents, UNITS_MILLI_PER_UNIT * NAV_MICRO_PER_COIN, fund.navMicroCents);
    }

    // ══════════════════════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════════════════════

    /** 一个交易日 = 几个 market tick（至少 1）。 */
    public static long ticksPerDay(AimcContext ctx) {
        return ticksPerDay(ctx == null ? null : ctx.settings);
    }

    /** 一个交易日 = 几个 market tick（至少 1）。 */
    public static long ticksPerDay(MarketSettings settings) {
        if (settings == null) {
            return 1L;
        }
        long tickMinutes = Math.max(1, settings.marketTickMinutes);
        long dayMinutes = Math.max(1, settings.tradingDayMinutes);
        return Math.max(1L, dayMinutes / tickMinutes);
    }

    /** 股票手续费：max(1 币, 成交额 × 0.2%)。 */
    public static long stockFee(long grossCents) {
        if (grossCents <= 0L) {
            return STOCK_FEE_MIN_CENTS;
        }
        return Math.max(STOCK_FEE_MIN_CENTS, Cents.percent(grossCents, STOCK_FEE_RATE));
    }

    private static String nextOrderId(String prefix, AimcContext ctx) {
        return prefix + "-" + ctx.tick() + "-" + Long.toHexString(System.nanoTime());
    }

    private static String cleanCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String actorName(Account account) {
        if (account == null) {
            return "系统";
        }
        return account.name == null || account.name.isBlank() ? account.uuid : account.name;
    }

    private static String brief(RuntimeException error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "内部错误，请稍后再试" : message;
    }

    private static String formatShares(long shares) {
        return String.format(Locale.ROOT, "%,d", shares);
    }

    /** 份额显示：1000 unitsMilli → "1.000" 份。 */
    private static String formatUnits(long unitsMilli) {
        long whole = unitsMilli / UNITS_MILLI_PER_UNIT;
        long fraction = unitsMilli % UNITS_MILLI_PER_UNIT;
        return String.format(Locale.ROOT, "%,d.%03d", whole, fraction);
    }

    private static String formatWan(long shares) {
        return String.format(Locale.ROOT, "%.2f", shares / 10_000.0);
    }

    private static String formatCredit(double creditScore) {
        return String.format(Locale.ROOT, "%.0f", creditScore);
    }

    private static String outlookName(String outlook) {
        if (outlook == null) {
            return "持平";
        }
        return switch (outlook) {
            case "up" -> "上升";
            case "down" -> "下滑";
            default -> "持平";
        };
    }

    /** 乘法溢出时饱和，查询文本不能因为极端数据把命令搞崩。 */
    private static long safeMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return (a >= 0L) == (b >= 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** 查询文本的兜底：任何 RuntimeException 都只返回一句失败提示，不抛给命令层。 */
    private static String safe(java.util.function.Supplier<String> supplier, String fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException error) {
            Log.warn(fallback, error);
            return fallback;
        }
    }
}
