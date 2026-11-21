package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.FundStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.FinanceService;
import com.mogutofu.aimcmarket.engine.MarketTicker;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Cents;
import net.minecraft.commands.CommandSourceStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 代执行的「股票 / 基金 / 银行」命令：直接调模组内部引擎，不经过 Brigadier 命令树。
 *
 * <p>入口是 {@link DirectCommandRunner#run}：它把 AI 生成的 {@code /ex stock ...} 之类
 * 拆成 token 后交给这里。行为尽量和 {@link MarketCommand} 里对应的子命令一致：
 * 同样的参数校验、同样的中文回执（全部走 {@link Messages}），区别只是不依赖命令树，
 * 也不会出现"命令执行了但玩家没看到输出"的情况。</p>
 *
 * <p>返回值约定：认识的子命令（含参数错误，提示已经发给玩家）返回 {@code true}；
 * 不认识的主题返回 {@code false}，交给别的处理器。</p>
 */
public final class DirectFinanceCommands {
    /** 股票列表每页显示多少只。 */
    private static final int STOCK_PER_PAGE = 9;
    /** 基金列表每页显示多少只。 */
    private static final int FUND_PER_PAGE = 9;
    /** 单笔股数上限，和手动命令 /ex stock buy 保持一致。 */
    private static final long MAX_SHARES = 10_000_000L;
    /** 不写金额时自动借款的默认上限：1000 币（= 100000 分）。 */
    private static final long DEFAULT_LOAN_CENTS = 100_000L;
    /** 定期存款允许写多少天（引擎目前只支持 7 / 30 个交易日，其余天数由引擎给出提示）。 */
    private static final int MIN_TERM_DAYS = 7;
    private static final int MAX_TERM_DAYS = 30;

    private DirectFinanceCommands() {
    }

    /**
     * 处理 "stock" / "fund" / "bank" 子命令。
     *
     * @param source  命令来源（用来回消息，也用来排政府 tick）
     * @param account 当前玩家账户；查询类命令允许为 null
     * @param parts   {@code /ex} 去掉之后按空格拆开的 token；parts[0] 是 stock/fund/bank
     * @return true = 已经处理（成功或失败提示都已发给玩家）；false = 不是这三个主题
     */
    public static boolean handle(CommandSourceStack source, Account account, String[] parts) {
        if (source == null || parts == null || parts.length == 0) {
            return false;
        }
        String topic = lower(parts[0]);
        int from = 1;
        // 容错：万一调用方把 "/ex stock ..." 整体传进来，跳过命令前缀
        if (isRootAlias(topic) && parts.length > 1) {
            topic = lower(parts[1]);
            from = 2;
        }
        if (!"stock".equals(topic) && !"fund".equals(topic) && !"bank".equals(topic)) {
            return false; // 不是股票/基金/银行，交给别的处理器
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        if (!ctx.settings.enabled) {
            DirectSupport.fail(source, "市场维护中（配置 general.enabled=false）");
            return true;
        }
        return switch (topic) {
            case "stock" -> stock(source, account, parts, from);
            case "fund" -> fund(source, account, parts, from);
            default -> bank(source, account, parts, from);
        };
    }

    // ══════════════════════════════════════════════════════════════
    // 股票
    // ══════════════════════════════════════════════════════════════

    /** stock 分发：不带子命令时默认列全部股票。 */
    private static boolean stock(CommandSourceStack source, Account account, String[] parts, int from) {
        if (from >= parts.length) {
            return stockList(source, account, 1);
        }
        String sub = lower(parts[from]);
        return switch (sub) {
            case "list" -> stockList(source, account, pageOf(parts, from + 1));
            case "info" -> stockInfo(source, account, parts, from + 1);
            case "buy" -> stockTrade(source, account, parts, from + 1, true);
            case "sell" -> stockTrade(source, account, parts, from + 1, false);
            default -> {
                DirectSupport.fail(source, "用法：/ex stock list [页码]｜/ex stock info <代码或名字>｜"
                        + "/ex stock buy|sell <代码或名字> <股数>");
                yield true;
            }
        };
    }

    /** stock list：代码 名称 价格 行业 涨跌 现实标记 持有股数，分页发。 */
    private static boolean stockList(CommandSourceStack source, Account account, int page) {
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        List<String> lines = new ArrayList<>();
        for (StockStore.Stock stock : ctx.stocks.all()) {
            if (stock == null || stock.code == null) {
                continue;
            }
            long held = account == null ? 0L : account.stockShares(stock.code);
            // 涨跌 = 现价相对发行价（和手动命令口径一致）
            double change = stock.initialPriceCents <= 0L ? 0.0
                    : (double) stock.priceCents / (double) stock.initialPriceCents - 1.0;
            StringBuilder line = new StringBuilder();
            line.append(stock.code).append(' ');
            line.append(stock.name == null ? stock.code : stock.name).append(' ');
            line.append(Messages.money(stock.priceCents)).append('（');
            line.append(stock.sector == null ? "未知行业" : stock.sector).append(' ');
            line.append(Messages.changeText(change));
            if (stock.realSymbol != null && !stock.realSymbol.isBlank()) {
                line.append(" ·现实"); // 有现实行情同步的票打个标记
            }
            line.append('）');
            if (held > 0L) {
                line.append(" 持有 ").append(held).append(" 股");
            }
            lines.add(line.toString());
        }
        Messages.sendPage(source, lines, page, STOCK_PER_PAGE, "/ex stock list");
        DirectSupport.info(source, "大盘 " + ctx.stocks.formatIndex() + "（7 日 "
                + Messages.changeText(ctx.stocks.indexChange7d(ctx.tradingDay())) + "）");
        return true;
    }

    /** stock info：代码或中文名都认，额外显示现实行情同步信息。 */
    private static boolean stockInfo(CommandSourceStack source, Account account, String[] parts, int nameFrom) {
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        String keyword = DirectSupport.join(parts, nameFrom, parts.length);
        if (keyword.isEmpty()) {
            DirectSupport.fail(source, "用法：/ex stock info <代码或名字>，例：/ex stock info 宁德时代");
            return true;
        }
        StockStore.Stock stock = DirectSupport.stock(keyword);
        if (stock == null) {
            DirectSupport.fail(source, "没有这只股票：" + keyword + "（/ex stock list 看代码和名字）");
            return true;
        }
        String text = FinanceService.stockInfo(ctx, account, stock.code);
        if (text == null || text.isBlank()) {
            DirectSupport.fail(source, "没有这个股票代码：" + keyword);
            return true;
        }
        Messages.sendLines(source, text);
        // 现实行情同步信息：realSymbol / realMarket / realUpdatedMs
        if (stock.realSymbol != null && !stock.realSymbol.isBlank()) {
            String market = stock.realMarket == null || stock.realMarket.isBlank() ? "未知市场" : stock.realMarket;
            String updated = stock.realUpdatedMs <= 0L
                    ? "还没同步过"
                    : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(stock.realUpdatedMs));
            DirectSupport.info(source, "现实行情：" + stock.realSymbol + "（" + market + "），当前股价按 "
                    + Cents.format(stock.priceCents) + " 币映射，更新于 " + updated);
        }
        return true;
    }

    /** stock buy|sell：最后一个 token 是股数，前面的都当股票代码/名字（支持中文名）。 */
    private static boolean stockTrade(CommandSourceStack source, Account account, String[] parts,
                                      int nameFrom, boolean buying) {
        String action = buying ? "buy" : "sell";
        if (parts.length <= nameFrom + 1) {
            DirectSupport.fail(source, "用法：/ex stock " + action + " <代码或名字> <股数>，例：/ex stock "
                    + action + " 宁德时代 100");
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        String name = DirectSupport.join(parts, nameFrom, parts.length - 1);
        long shares = parseLong(parts[parts.length - 1], -1L);
        if (shares < 1L) {
            DirectSupport.fail(source, "股数要写整数，例：/ex stock " + action + " " + name + " 10");
            return true;
        }
        if (shares > MAX_SHARES) {
            DirectSupport.fail(source, "单笔股数太大（上限 " + MAX_SHARES + " 股），请拆成多笔");
            return true;
        }
        StockStore.Stock stock = DirectSupport.stock(name);
        if (stock == null) {
            DirectSupport.fail(source, "没有这只股票：" + name + "（/ex stock list 看代码和名字）");
            return true;
        }
        send(source, buying
                ? FinanceService.buyStock(ctx, loaded, stock.code, shares)
                : FinanceService.sellStock(ctx, loaded, stock.code, shares));
        tickGovernment(source);
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // 基金
    // ══════════════════════════════════════════════════════════════

    /** fund 分发：不带子命令时默认列全部基金。 */
    private static boolean fund(CommandSourceStack source, Account account, String[] parts, int from) {
        if (from >= parts.length) {
            return fundList(source, account, 1);
        }
        String sub = lower(parts[from]);
        return switch (sub) {
            case "list" -> fundList(source, account, pageOf(parts, from + 1));
            case "info" -> fundInfo(source, account, parts, from + 1);
            case "buy" -> fundBuy(source, account, parts, from + 1);
            case "sell" -> fundSell(source, account, parts, from + 1);
            default -> {
                DirectSupport.fail(source, "用法：/ex fund list [页码]｜/ex fund info <代码>｜"
                        + "/ex fund buy <代码> <金额>｜/ex fund sell <代码> <份额>");
                yield true;
            }
        };
    }

    /** fund list：代码 名称 净值 持有份额。 */
    private static boolean fundList(CommandSourceStack source, Account account, int page) {
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        List<String> lines = new ArrayList<>();
        for (FundStore.Fund fund : ctx.funds.all()) {
            if (fund == null || fund.code == null) {
                continue;
            }
            long unitsMilli = account == null ? 0L : account.fundUnitsMilli(fund.code);
            StringBuilder line = new StringBuilder();
            line.append(fund.code).append(' ');
            line.append(fund.name == null ? fund.code : fund.name);
            line.append(" 净值 ").append(ctx.funds.formatNav(fund.code));
            if (unitsMilli > 0L) {
                line.append(" 持有 ").append(formatUnits(unitsMilli)).append(" 份");
            }
            lines.add(line.toString());
        }
        Messages.sendPage(source, lines, page, FUND_PER_PAGE, "/ex fund list");
        return true;
    }

    /** fund info：净值、规模、组合、你的份额/市值/待成交（直接读 FundStore 字段）。 */
    private static boolean fundInfo(CommandSourceStack source, Account account, String[] parts, int codeIndex) {
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        if (codeIndex >= parts.length) {
            DirectSupport.fail(source, "用法：/ex fund info <代码>，例：/ex fund info FND01");
            return true;
        }
        FundStore.Fund fund = resolveFund(ctx, parts[codeIndex]);
        if (fund == null) {
            DirectSupport.fail(source, "没有这只基金：" + parts[codeIndex] + "（/ex fund list 看代码）");
            return true;
        }
        fundInfoLines(source, ctx, account, fund);
        return true;
    }

    /** 拼基金详情并发送（account 可以为 null：控制台/离线查询）。 */
    private static void fundInfoLines(CommandSourceStack source, AimcContext ctx, Account account, FundStore.Fund fund) {
        String description = fund.description == null || fund.description.isBlank() ? "（无说明）" : fund.description;
        DirectSupport.send(source, fund.name + "（" + fund.code + "）｜" + description);
        // 净值 / 规模 / 波动 / 目标年化
        DirectSupport.send(source, "净值 " + ctx.funds.formatNav(fund.code) + " 币/份｜规模 "
                + Messages.money(fund.cashCents) + "｜日波动 " + Messages.percent(fund.dailyVol)
                + "｜目标年化 " + Messages.percent(fund.targetAnnual));
        // 组合权重 + 申赎费
        DirectSupport.info(source, "组合：债券 " + Messages.percent(fund.bondWeight) + " + 股票指数 "
                + Messages.percent(fund.stockWeight)
                + "｜申购费 " + Messages.percent(ctx.settings.fundSubscribeFee)
                + "｜赎回费 " + Messages.percent(ctx.settings.fundSubscribeFee)
                + "（持有不足 1 交易日另收 " + Messages.percent(ctx.settings.fundRedeemEarlyFee) + "）");
        if (account == null) {
            DirectSupport.info(source, "份额：当前没有玩家账户（控制台查询）");
            return;
        }
        long unitsMilli = account.fundUnitsMilli(fund.code);
        if (unitsMilli <= 0L) {
            DirectSupport.info(source, "你的份额：无");
            return;
        }
        DirectSupport.ok(source, "你的份额：" + formatUnits(unitsMilli) + " 份｜市值 "
                + Messages.money(ctx.funds.valueOfUnits(fund.code, unitsMilli)) + " 币");
        // 待成交的申赎单：下一个 market tick 按净值成交，AI 需要知道这些钱还没到账
        long pendingBuy = 0L;
        long pendingSell = 0L;
        int buyCount = 0;
        int sellCount = 0;
        for (FundStore.PendingOrder order : ctx.funds.pending()) {
            if (order == null || order.fundCode == null || !order.fundCode.equalsIgnoreCase(fund.code)) {
                continue;
            }
            if (order.uuid == null || !order.uuid.equals(account.uuid)) {
                continue;
            }
            if ("buy".equals(order.type)) {
                pendingBuy += order.amountCents;
                buyCount++;
            } else {
                pendingSell += order.unitsMilli;
                sellCount++;
            }
        }
        if (buyCount > 0 || sellCount > 0) {
            StringBuilder pending = new StringBuilder("待成交（下一个市场 tick 按净值成交）：");
            if (buyCount > 0) {
                pending.append(buyCount).append(" 笔申购 ").append(Messages.money(pendingBuy));
            }
            if (buyCount > 0 && sellCount > 0) {
                pending.append("｜");
            }
            if (sellCount > 0) {
                pending.append(sellCount).append(" 笔赎回 ").append(formatUnits(pendingSell)).append(" 份");
            }
            DirectSupport.info(source, pending.toString());
        }
    }

    /** fund buy：金额走 Cents.parse（"100" / "12.5" 都行），实际扣款在下一个 market tick。 */
    private static boolean fundBuy(CommandSourceStack source, Account account, String[] parts, int argFrom) {
        if (parts.length < argFrom + 2) {
            DirectSupport.fail(source, "用法：/ex fund buy <代码> <金额>，例：/ex fund buy FND01 100");
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        FundStore.Fund fund = resolveFund(ctx, parts[argFrom]);
        if (fund == null) {
            DirectSupport.fail(source, "没有这只基金：" + parts[argFrom] + "（/ex fund list 看代码）");
            return true;
        }
        long cents = DirectSupport.amountCents(parts[argFrom + 1]);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex fund buy " + fund.code + " 100");
            return true;
        }
        send(source, FinanceService.buyFund(ctx, loaded, fund.code, cents));
        tickGovernment(source);
        return true;
    }

    /** fund sell：份额（可带小数），内部换算成 unitsMilli 交给引擎排队赎回。 */
    private static boolean fundSell(CommandSourceStack source, Account account, String[] parts, int argFrom) {
        if (parts.length < argFrom + 2) {
            DirectSupport.fail(source, "用法：/ex fund sell <代码> <份额>，例：/ex fund sell FND01 100");
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        FundStore.Fund fund = resolveFund(ctx, parts[argFrom]);
        if (fund == null) {
            DirectSupport.fail(source, "没有这只基金：" + parts[argFrom] + "（/ex fund list 看代码）");
            return true;
        }
        long unitsMilli = parseUnitsMilli(parts[argFrom + 1]);
        if (unitsMilli <= 0L) {
            DirectSupport.fail(source, "份额格式不对，例：/ex fund sell " + fund.code + " 100");
            return true;
        }
        send(source, FinanceService.sellFund(ctx, loaded, fund.code, unitsMilli));
        tickGovernment(source);
        return true;
    }

    /** 基金代码或中文名 → Fund（"FND01" / "稳健基金" 都认）。 */
    private static FundStore.Fund resolveFund(AimcContext ctx, String text) {
        if (ctx == null || text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        FundStore.Fund direct = ctx.funds.fund(value);
        if (direct != null) {
            return direct;
        }
        FundStore.Fund best = null;
        int bestLength = 0;
        for (FundStore.Fund fund : ctx.funds.all()) {
            if (fund == null || fund.name == null) {
                continue;
            }
            if (fund.name.equalsIgnoreCase(value)) {
                return fund;
            }
            // 名字包含：取最长匹配，避免"基金"命中多只
            if (fund.name.length() >= 2 && (value.contains(fund.name) || fund.name.contains(value))
                    && fund.name.length() > bestLength) {
                best = fund;
                bestLength = fund.name.length();
            }
        }
        return best;
    }

    // ══════════════════════════════════════════════════════════════
    // 银行
    // ══════════════════════════════════════════════════════════════

    /** bank 分发：不带子命令时默认看概况。 */
    private static boolean bank(CommandSourceStack source, Account account, String[] parts, int from) {
        if (from >= parts.length) {
            return bankInfo(source, account);
        }
        String sub = lower(parts[from]);
        return switch (sub) {
            case "info", "balance" -> bankInfo(source, account);
            case "deposit" -> bankDeposit(source, account, parts, from + 1);
            case "withdraw" -> bankWithdraw(source, account, parts, from + 1);
            case "term" -> bankTerm(source, account, parts, from + 1);
            case "loan" -> bankLoan(source, account, parts, from + 1);
            default -> {
                DirectSupport.fail(source, "用法：/ex bank info｜/ex bank deposit|withdraw <金额>｜"
                        + "/ex bank term <天数7-30> <金额>｜/ex bank loan take [金额]|repay <金额>");
                yield true;
            }
        };
    }

    /** bank info：活期/定期/贷款/利率/信用分（引擎拼好的多行文本）。 */
    private static boolean bankInfo(CommandSourceStack source, Account account) {
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        Messages.sendLines(source, FinanceService.bankInfo(ctx, loaded));
        return true;
    }

    /** bank deposit：钱包 → 活期。 */
    private static boolean bankDeposit(CommandSourceStack source, Account account, String[] parts, int amountIndex) {
        if (parts.length <= amountIndex) {
            DirectSupport.fail(source, "用法：/ex bank deposit <金额>，例：/ex bank deposit 100");
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        long cents = DirectSupport.amountCents(parts[amountIndex]);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex bank deposit 100");
            return true;
        }
        send(source, FinanceService.deposit(ctx, loaded, cents));
        tickGovernment(source);
        return true;
    }

    /** bank withdraw：活期 → 钱包。 */
    private static boolean bankWithdraw(CommandSourceStack source, Account account, String[] parts, int amountIndex) {
        if (parts.length <= amountIndex) {
            DirectSupport.fail(source, "用法：/ex bank withdraw <金额>，例：/ex bank withdraw 100");
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        long cents = DirectSupport.amountCents(parts[amountIndex]);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex bank withdraw 100");
            return true;
        }
        send(source, FinanceService.withdraw(ctx, loaded, cents));
        tickGovernment(source);
        return true;
    }

    /** bank term：<天数7-30> <金额>，钱从活期转成定期（引擎只接受 7 / 30 个交易日）。 */
    private static boolean bankTerm(CommandSourceStack source, Account account, String[] parts, int daysIndex) {
        if (parts.length < daysIndex + 2) {
            DirectSupport.fail(source, "用法：/ex bank term <天数7-30> <金额>，例：/ex bank term 7 100");
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        // 先按 long 校验再转 int，避免超大数字被截断后恰好落进合法区间
        long parsedDays = parseLong(parts[daysIndex], -1L);
        if (parsedDays < MIN_TERM_DAYS || parsedDays > MAX_TERM_DAYS) {
            DirectSupport.fail(source, "定期天数要在 " + MIN_TERM_DAYS + "~" + MAX_TERM_DAYS
                    + " 之间（目前只支持 7 或 30 个交易日）");
            return true;
        }
        int days = (int) parsedDays;
        long cents = DirectSupport.amountCents(parts[daysIndex + 1]);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex bank term " + days + " 100");
            return true;
        }
        send(source, FinanceService.termDeposit(ctx, loaded, days, cents));
        tickGovernment(source);
        return true;
    }

    /** bank loan：take [金额] / repay <金额>。 */
    private static boolean bankLoan(CommandSourceStack source, Account account, String[] parts, int actionIndex) {
        String usage = "用法：/ex bank loan take [金额]｜/ex bank loan repay <金额>，例：/ex bank loan take 100";
        if (parts.length <= actionIndex) {
            DirectSupport.fail(source, usage);
            return true;
        }
        AimcContext ctx = requireContext(source);
        if (ctx == null) {
            return true;
        }
        Account loaded = requireAccount(source, account);
        if (loaded == null) {
            return true;
        }
        String action = lower(parts[actionIndex]);
        return switch (action) {
            case "take" -> bankLoanTake(source, ctx, loaded, parts, actionIndex + 1);
            case "repay" -> bankRepay(source, ctx, loaded, parts, actionIndex + 1);
            default -> {
                DirectSupport.fail(source, usage);
                yield true;
            }
        };
    }

    /** bank loan take：不写金额时和手动命令一样，借 min(可借额度, 1000 币)。 */
    private static boolean bankLoanTake(CommandSourceStack source, AimcContext ctx, Account account,
                                        String[] parts, int amountIndex) {
        long cents;
        if (parts.length > amountIndex) {
            // 写了金额就必须写对，不能因为解析失败就默默按默认额度借出去
            cents = DirectSupport.amountCents(parts[amountIndex]);
            if (cents <= 0L) {
                DirectSupport.fail(source, "金额格式不对，例：/ex bank loan take 100");
                return true;
            }
        } else {
            long limit = ctx.bank.creditLimit(account, ctx.settings, ctx.nowMs());
            cents = Math.min(limit, DEFAULT_LOAN_CENTS);
            if (cents <= 0L) {
                DirectSupport.fail(source, "当前没有可借额度（多赚点钱、按时还款能提高额度）");
                return true;
            }
        }
        send(source, FinanceService.takeLoan(ctx, account, cents));
        tickGovernment(source);
        return true;
    }

    /** bank loan repay：手动还贷，钱包扣款销毁。 */
    private static boolean bankRepay(CommandSourceStack source, AimcContext ctx, Account account,
                                     String[] parts, int amountIndex) {
        if (parts.length <= amountIndex) {
            DirectSupport.fail(source, "用法：/ex bank loan repay <金额>，例：/ex bank loan repay 100");
            return true;
        }
        long cents = DirectSupport.amountCents(parts[amountIndex]);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex bank loan repay 100");
            return true;
        }
        send(source, FinanceService.repayLoan(ctx, account, cents));
        tickGovernment(source);
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // 公共小工具
    // ══════════════════════════════════════════════════════════════

    /** 取市场上下文；没初始化好就提示玩家并返回 null。 */
    private static AimcContext requireContext(CommandSourceStack source) {
        AimcContext ctx = DirectSupport.ctx();
        if (ctx == null) {
            DirectSupport.fail(source, "市场还没初始化（等服务器启动完成再试）");
        }
        return ctx;
    }

    /** 需要账户的动作（交易/存取/借贷）；没有账户就提示并返回 null。 */
    private static Account requireAccount(CommandSourceStack source, Account account) {
        if (account != null) {
            return account;
        }
        DirectSupport.fail(source, "账户还没建好，重进一下服务器");
        return null;
    }

    /** 把引擎返回的 ActionResult 翻译成玩家消息（成功绿/失败红，公告广播）。 */
    private static void send(CommandSourceStack source, ActionResult result) {
        if (result == null) {
            DirectSupport.fail(source, "操作失败（引擎没有返回结果）");
            return;
        }
        if (result.ok) {
            DirectSupport.ok(source, result.message);
        } else {
            DirectSupport.fail(source, result.message);
        }
        if (result.ok && result.broadcast != null && !result.broadcast.isEmpty()) {
            Messages.broadcast(source, result.broadcast, true);
        }
    }

    /**
     * 成交后让政府引擎重新算一次宏观指标（和手动 /ex 命令保持同样的节奏）。
     *
     * <p>拿不到服务器（单测/已停服）就直接跳过，绝不影响已经完成的交易。</p>
     */
    private static void tickGovernment(CommandSourceStack source) {
        AimcContext ctx = DirectSupport.ctx();
        if (ctx == null || source == null) {
            return;
        }
        try {
            source.getServer().execute(() -> MarketTicker.triggerGovernment(ctx, System.currentTimeMillis()));
        } catch (RuntimeException ignored) {
            // 不是真实的服务器命令源，忽略即可
        }
    }

    private static boolean isRootAlias(String topic) {
        return "ex".equals(topic) || "exchange".equals(topic) || "market".equals(topic);
    }

    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /** 页码：没写/写坏都回第 1 页，范围夹在 1~99（和命令参数一致）。 */
    private static int pageOf(String[] parts, int index) {
        if (parts == null || index < 0 || index >= parts.length) {
            return 1;
        }
        long parsed = parseLong(parts[index], -1L);
        if (parsed < 1L || parsed > 99L) {
            return 1;
        }
        return (int) parsed;
    }

    /** 解析整数（容忍千分位逗号）；失败返回 fallback。 */
    private static long parseLong(String text, long fallback) {
        if (text == null) {
            return fallback;
        }
        String value = text.trim().replace(",", "");
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    /** 份额（可带小数）→ unitsMilli；非法或非正返回 -1。 */
    private static long parseUnitsMilli(String text) {
        if (text == null || text.isBlank()) {
            return -1L;
        }
        try {
            double units = Double.parseDouble(text.trim());
            if (Double.isNaN(units) || Double.isInfinite(units) || units <= 0.0 || units > 1.0e12) {
                return -1L;
            }
            long unitsMilli = Math.round(units * 1000.0);
            return unitsMilli > 0L ? unitsMilli : -1L;
        } catch (NumberFormatException error) {
            return -1L;
        }
    }

    /** 份额显示：123.456 份（unitsMilli ÷ 1000，保留三位小数）。 */
    private static String formatUnits(long unitsMilli) {
        boolean negative = unitsMilli < 0L;
        long abs = negative ? Math.abs(unitsMilli) : unitsMilli;
        long whole = abs / 1000L;
        long milli = abs % 1000L;
        StringBuilder text = new StringBuilder();
        if (negative) {
            text.append('-');
        }
        text.append(whole).append('.');
        if (milli < 100L) {
            text.append('0');
        }
        if (milli < 10L) {
            text.append('0');
        }
        text.append(milli);
        return text.toString();
    }
}
