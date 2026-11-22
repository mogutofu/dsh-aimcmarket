package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.GovernmentStore;
import com.mogutofu.aimcmarket.engine.ActionResult;
import com.mogutofu.aimcmarket.engine.CompanyEngine;
import com.mogutofu.aimcmarket.engine.GovernmentEngine;
import com.mogutofu.aimcmarket.engine.IpoEngine;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Log;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 代执行：公司 / IPO / 政府分支。
 *
 * <p>把 AI 生成的 {@code /ex company ...}、{@code /ex ipo ...}、{@code /ex gov ...}
 * 直接翻译成模组内部调用，<b>不经过 Brigadier 命令树</b>，所以中文公司名、
 * 参数格式错、引擎拒绝之类的情况都能由模组自己用 {@link Messages} 回给玩家，
 * 不会出现"执行成功但玩家看不到反馈"。</p>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>{@code parts} 是去掉 {@code /ex} 之后按空格拆的 token（见 {@link DirectSupport#tokens}）；
 *       中文公司名可能被拆成多段，所以统一用 {@link DirectSupport#join} 拼回来。</li>
 *   <li>金额都是"币"文本，交给 {@link DirectSupport#amountCents} 解析成"分"；分币口径见 Cents。</li>
 *   <li>CEO 专属操作（注资/取现/招聘/裁员/分红/换行业/升级/增发/关停/改名）只解析<b>自己名下</b>的公司，
 *       并且引擎或本层会再校验一次 CEO 身份；{@code invest}、{@code info}、{@code shareholders}
 *       允许看/投别人公司。</li>
 *   <li>所有可预期失败都发提示并返回 true（表示"这条命令我处理了"），不认识的根命令才返回 false。
 *       意外异常在这里吃掉并记日志，绝不抛给 AI 调用方。</li>
 * </ul>
 */
public final class DirectCompanyCommands {
    /** 列表类输出每页行数（和 /ex 命令层保持一致）。 */
    private static final int PER_PAGE = 8;
    /** 股东名单每页行数。 */
    private static final int HOLDER_PER_PAGE = 10;
    /** 一次最多招聘/裁员人数（和命令层一致）。 */
    private static final int MAX_HIRE_COUNT = 100;
    /** 换行业冷却（交易日），和命令层一致。 */
    private static final long SECTOR_COOLDOWN_DAYS = 7L;
    /** 增发冷却（交易日），和命令层一致。 */
    private static final long RAISE_COOLDOWN_DAYS = 7L;

    private DirectCompanyCommands() {
    }

    /**
     * 处理 "company / ipo / gov" 子命令。
     *
     * @param source  命令来源（用来给玩家发消息）
     * @param account 当前玩家账户（控制台/未登录时为 null）
     * @param parts   已经按空格拆好的 token，parts[0] 是根命令
     * @return true = 已处理（成功或失败提示都已发出）；false = 不是本类的根命令
     */
    public static boolean handle(CommandSourceStack source, Account account, String[] parts) {
        if (source == null || parts == null || parts.length == 0 || parts[0] == null) {
            return false;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        try {
            switch (root) {
                case "company":
                    companyRoot(source, account, parts);
                    return true;
                case "ipo":
                    ipoRoot(source, account, parts);
                    return true;
                case "gov":
                    govRoot(source, parts);
                    return true;
                default:
                    return false;
            }
        } catch (RuntimeException error) {
            // 兜底：引擎层大多自己吞异常，这里再保一层，避免把异常抛回 AI 调用方
            DirectSupport.fail(source, "执行失败：" + reason(error));
            Log.warn("AI 代执行公司命令失败：" + String.join(" ", parts), error);
            return true;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // company 分支
    // ══════════════════════════════════════════════════════════════

    private static void companyRoot(CommandSourceStack source, Account account, String[] parts) {
        String sub = parts.length > 1 && parts[1] != null ? parts[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "":
            case "list":
                companyList(source, account, parts);
                return;
            case "info":
                companyInfo(source, account, parts);
                return;
            case "create":
                companyCreate(source, account, parts);
                return;
            case "invest":
                companyInvest(source, account, parts);
                return;
            case "deposit":
                companyMoney(source, account, parts, true);
                return;
            case "withdraw":
                companyMoney(source, account, parts, false);
                return;
            case "hire":
                companyHireFire(source, account, parts, true);
                return;
            case "fire":
                companyHireFire(source, account, parts, false);
                return;
            case "dividend":
                companyDividend(source, account, parts);
                return;
            case "sector":
                companySector(source, account, parts);
                return;
            case "levelup":
            case "level-up":
            case "upgrade":
                companyLevelUp(source, account, parts);
                return;
            case "raise":
                companyRaise(source, account, parts);
                return;
            case "close":
                companyClose(source, account, parts);
                return;
            case "shareholders":
            case "holder":
            case "holders":
                companyShareholders(source, account, parts);
                return;
            case "rename":
                companyRename(source, account, parts);
                return;
            default:
                DirectSupport.fail(source, "不认识的 company 子命令：「" + parts[1] + "」。可用："
                        + "list/info/create/invest/deposit/withdraw/hire/fire/dividend/sector/levelup/raise/close/shareholders/rename");
        }
    }

    /** 所有公司列表：名字/代码/上市/估值/行业等级/公司现金/CEO 是否自己。 */
    private static void companyList(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        int page = parts.length > 2 ? pageOf(parts[2]) : 1;
        List<String> lines = new ArrayList<>();
        for (CompanyStore.Company company : ctx.companies.all()) {
            if (company == null) {
                continue;
            }
            boolean self = account != null && company.ceoUuid != null && company.ceoUuid.equals(account.uuid);
            StringBuilder line = new StringBuilder();
            line.append(company.name).append("（").append(company.id).append("）");
            line.append(company.code == null || company.code.isEmpty() ? "｜未上市" : "｜已上市 " + company.code);
            line.append("｜").append(company.sectorName()).append(" Lv.").append(company.level);
            line.append("｜估值 ").append(Messages.money(company.valuationCents));
            line.append("｜公司现金 ").append(Messages.money(company.cashCents));
            line.append("｜").append(self ? "你的公司" : "CEO：" + ceoName(ctx, company));
            if (company.bankrupt) {
                line.append("｜已破产");
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            Messages.sendInfo(source, "还没有人开公司。开一家：/ex company create <名字> <采矿|农业|制造|贸易|服务|医疗|航天>");
            return;
        }
        Messages.send(source, "共 " + lines.size() + " 家公司：");
        sendPaged(source, lines, page, PER_PAGE);
        if (!ctx.companies.bankruptHistory().isEmpty()) {
            Messages.sendInfo(source, "破产历史：" + String.join("、", ctx.companies.bankruptHistory()));
        }
    }

    /** 单家公司详情；不写公司名时看自己的公司。 */
    private static void companyInfo(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        String nameText = parts.length > 2 ? DirectSupport.join(parts, 2, parts.length) : "";
        CompanyStore.Company company;
        if (nameText.isEmpty()) {
            company = mine(source, account, "");
            if (company == null) {
                return;
            }
        } else {
            company = DirectSupport.company(nameText);
            if (company == null) {
                DirectSupport.fail(source, "找不到公司：" + nameText + "（/ex company list 看列表）");
                return;
            }
        }
        boolean self = account != null && company.ceoUuid != null && company.ceoUuid.equals(account.uuid);
        Messages.send(source, company.name + "（" + company.id + "）" + company.sectorName()
                + " Lv." + company.level
                + (company.listed ? "｜已上市 " + company.code : "｜未上市")
                + (company.bankrupt ? "｜已破产" : ""));
        Messages.sendInfo(source, "现金 " + Messages.money(company.cashCents)
                + "｜估值 " + Messages.money(company.valuationCents)
                + "｜员工 " + company.employees + "/" + company.maxEmployees()
                + "｜口碑 " + String.format(Locale.ROOT, "%.2f", company.reputation));
        Messages.sendInfo(source, "累计营收 " + Messages.money(company.cumulativeRevenueCents)
                + "｜近 7 交易日均营收 " + Messages.money(company.averageDailyRevenue(7))
                + "｜近 7 交易日均净利 " + Messages.money(company.averageDailyProfit(7))
                + "｜分红比例 " + Messages.percent(company.dividendRatio));
        double stake = company.sharesTotal <= 0L
                ? 0.0
                : Math.max(0L, company.sharesOf(company.ceoUuid)) * 100.0 / company.sharesTotal;
        Messages.sendInfo(source, "股本 " + company.sharesTotal + " 股"
                + "｜CEO 持股 " + String.format(Locale.ROOT, "%.1f%%", stake)
                + "｜CEO：" + (self ? "你" : ceoName(ctx, company)));
        if (company.listed) {
            long remaining = Math.max(0L, company.lockUntilDay - ctx.tradingDay());
            Messages.sendInfo(source, "上市代码 " + company.code + "｜CEO 股份锁定到第 " + company.lockUntilDay
                    + " 交易日" + (company.lockUntilDay < 0L ? ""
                    : (remaining > 0L ? "（还剩 " + remaining + " 个交易日）" : "（已解锁）")));
        }
        if (company.ipoPending) {
            long readyDay = company.ipoApplyDay + 1L;
            long day = ctx.tradingDay();
            Messages.sendInfo(source, "IPO 已递交（第 " + company.ipoApplyDay + " 交易日，流通比例 "
                    + Messages.percent(company.ipoFloatRatio) + "），第 " + readyDay + " 交易日起可以 ipo confirm"
                    + (day >= readyDay ? "（现在就可以）" : "（还要 " + (readyDay - day) + " 个交易日）"));
        }
    }

    /** 创建公司：company create <名字> <行业>。 */
    private static void companyCreate(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        if (parts.length < 4) {
            DirectSupport.fail(source, "用法：/ex company create <公司名> <采矿|农业|制造|贸易|服务|医疗|航天>");
            return;
        }
        String sectorText = parts[parts.length - 1];
        String name = DirectSupport.join(parts, 2, parts.length - 1);
        if (name.isEmpty()) {
            DirectSupport.fail(source, "用法：/ex company create <公司名> <采矿|农业|制造|贸易|服务|医疗|航天>");
            return;
        }
        String sector = sectorCode(sectorText);
        if (sector == null) {
            DirectSupport.fail(source, "行业只能选：" + sectorChoices());
            return;
        }
        ActionResult result = CompanyEngine.create(ctx(), account, name, sector);
        sendResult(source, result);
        if (result != null && result.ok) {
            afterMutation(account);
        }
    }

    /** 入股：company invest <公司> <金额>（可以投别人的公司）。 */
    private static void companyInvest(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        if (parts.length < 4) {
            DirectSupport.fail(source, "用法：/ex company invest <公司> <金额>，例：/ex company invest 我的工厂 100");
            return;
        }
        String amountText = parts[parts.length - 1];
        String nameText = DirectSupport.join(parts, 2, parts.length - 1);
        CompanyStore.Company company = DirectSupport.company(nameText);
        if (company == null) {
            DirectSupport.fail(source, "找不到公司：" + nameText + "（/ex company list 看列表）");
            return;
        }
        long cents = DirectSupport.amountCents(amountText);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex company invest " + company.name + " 100");
            return;
        }
        ActionResult result = CompanyEngine.invest(ctx(), account, company, cents);
        sendResult(source, result);
        if (result != null && result.ok) {
            afterMutation(account);
        }
    }

    /** 注资/取现：company deposit|withdraw <公司> <金额>（只有 CEO 能对自己公司操作）。 */
    private static void companyMoney(CommandSourceStack source, Account account, String[] parts, boolean deposit) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String amountText;
        String nameText;
        if (parts.length == 3) {
            // 省略公司名：默认自己唯一的那家公司
            amountText = parts[2];
            nameText = "";
        } else if (parts.length >= 4) {
            amountText = parts[parts.length - 1];
            nameText = DirectSupport.join(parts, 2, parts.length - 1);
        } else {
            DirectSupport.fail(source, "用法：/ex company " + (deposit ? "deposit" : "withdraw") + " <公司> <金额>");
            return;
        }
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        long cents = DirectSupport.amountCents(amountText);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex company " + (deposit ? "deposit" : "withdraw")
                    + " " + company.name + " 100");
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        try {
            if (deposit) {
                ctx.companies.deposit(account, company, cents);
                Messages.sendOk(source, "向 " + company.name + " 注资 " + Messages.money(cents)
                        + "，公司现金 " + Messages.money(company.cashCents));
            } else {
                ctx.companies.withdraw(account, company, cents);
                Messages.sendOk(source, "从 " + company.name + " 取出 " + Messages.money(cents)
                        + "，公司现金 " + Messages.money(company.cashCents));
            }
            afterMutation(account);
        } catch (RuntimeException error) {
            DirectSupport.fail(source, reason(error));
        }
    }

    /** 招聘/裁员：company hire|fire <公司> <人数>。 */
    private static void companyHireFire(CommandSourceStack source, Account account, String[] parts, boolean hire) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String countText;
        String nameText;
        if (parts.length == 3) {
            countText = parts[2];
            nameText = "";
        } else if (parts.length >= 4) {
            countText = parts[parts.length - 1];
            nameText = DirectSupport.join(parts, 2, parts.length - 1);
        } else {
            DirectSupport.fail(source, "用法：/ex company " + (hire ? "hire" : "fire") + " <公司> <人数>");
            return;
        }
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        int count = parsePositiveInt(countText, MAX_HIRE_COUNT);
        if (count <= 0) {
            DirectSupport.fail(source, "人数要写 1-" + MAX_HIRE_COUNT + " 的整数，例：/ex company "
                    + (hire ? "hire" : "fire") + " " + company.name + " 2");
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        if (hire) {
            ActionResult result = CompanyEngine.hire(ctx, account, company, count);
            sendResult(source, result);
            if (result != null && result.ok) {
                afterMutation(account);
            }
            return;
        }
        try {
            ctx.companies.fire(company, count);
            Messages.sendOk(source, company.name + " 裁员 " + count + " 人，现有员工 " + company.employees
                    + "／" + company.maxEmployees() + "，口碑 "
                    + String.format(Locale.ROOT, "%.2f", company.reputation));
            afterMutation(account);
        } catch (RuntimeException error) {
            DirectSupport.fail(source, reason(error));
        }
    }

    /** 分红比例：company dividend <公司> <0-80>（百分数，每 7 交易日发一次）。 */
    private static void companyDividend(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String ratioText;
        String nameText;
        if (parts.length == 3) {
            ratioText = parts[2];
            nameText = "";
        } else if (parts.length >= 4) {
            ratioText = parts[parts.length - 1];
            nameText = DirectSupport.join(parts, 2, parts.length - 1);
        } else {
            DirectSupport.fail(source, "用法：/ex company dividend <公司> <0-80>");
            return;
        }
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        double percentValue;
        try {
            percentValue = Double.parseDouble(ratioText.replace("%", "").replace("％", "").trim());
        } catch (NumberFormatException error) {
            percentValue = Double.NaN;
        }
        if (Double.isNaN(percentValue) || Double.isInfinite(percentValue)
                || percentValue < 0.0 || percentValue > 80.0) {
            DirectSupport.fail(source, "分红比例要写 0-80（百分比），例：/ex company dividend " + company.name + " 30");
            return;
        }
        // CompanyStore 没有 setDividendRatio；命令层也是直接改这个 public 字段（save 时会落盘）
        long basisPoints = Math.round(percentValue * 100.0);
        company.dividendRatio = basisPoints / 10_000.0;
        Messages.sendOk(source, company.name + " 分红比例设为 " + Messages.percent(company.dividendRatio)
                + "（每 7 交易日把当期净利润的这部分按持股比例发给股东）");
        afterMutation(account);
    }

    /** 换行业：company sector <公司> <行业>。 */
    private static void companySector(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String sectorText;
        String nameText;
        if (parts.length == 3) {
            sectorText = parts[2];
            nameText = "";
        } else if (parts.length >= 4) {
            sectorText = parts[parts.length - 1];
            nameText = DirectSupport.join(parts, 2, parts.length - 1);
        } else {
            DirectSupport.fail(source, "用法：/ex company sector <公司> <采矿|农业|制造|贸易|服务|医疗|航天>");
            return;
        }
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        String code = sectorCode(sectorText);
        if (code == null) {
            DirectSupport.fail(source, "行业只能选：" + sectorChoices());
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        try {
            ctx.companies.switchSector(company, code, ctx.tradingDay(), SECTOR_COOLDOWN_DAYS, ctx.settings);
            Messages.sendOk(source, company.name + " 转到" + CompanyStore.SECTORS.get(code) + "行业");
            afterMutation(account);
        } catch (RuntimeException error) {
            DirectSupport.fail(source, reason(error));
        }
    }

    /** 升级：company levelup <公司>。 */
    private static void companyLevelUp(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String nameText = parts.length > 2 ? DirectSupport.join(parts, 2, parts.length) : "";
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        ActionResult result = CompanyEngine.levelUp(ctx(), account, company);
        sendResult(source, result);
        if (result != null && result.ok) {
            afterMutation(account);
        }
    }

    /** 上市后增发：company raise <公司> <金额>（按当前股价 9 折向机构发行，单次稀释 ≤20%）。 */
    private static void companyRaise(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String amountText;
        String nameText;
        if (parts.length == 3) {
            amountText = parts[2];
            nameText = "";
        } else if (parts.length >= 4) {
            amountText = parts[parts.length - 1];
            nameText = DirectSupport.join(parts, 2, parts.length - 1);
        } else {
            DirectSupport.fail(source, "用法：/ex company raise <公司> <金额>");
            return;
        }
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        long cents = DirectSupport.amountCents(amountText);
        if (cents <= 0L) {
            DirectSupport.fail(source, "金额格式不对，例：/ex company raise " + company.name + " 1000");
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        long price = company.code == null || company.code.isEmpty() ? 0L : ctx.stocks.priceOf(company.code);
        try {
            long newShares = ctx.companies.raise(company, cents, ctx.tradingDay(), RAISE_COOLDOWN_DAYS,
                    ctx.settings, price, ctx.ledger, account);
            Messages.sendOk(source, company.name + " 增发 " + newShares + " 股，募资 " + Messages.money(cents)
                    + " 进入公司现金（当前 " + Messages.money(company.cashCents) + "）");
            afterMutation(account);
        } catch (RuntimeException error) {
            DirectSupport.fail(source, reason(error));
        }
    }

    /** 关停公司：company close <公司>（上市/破产的不能直接关）。 */
    private static void companyClose(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String nameText = parts.length > 2 ? DirectSupport.join(parts, 2, parts.length) : "";
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        try {
            ctx.companies.close(company, account);
            Messages.sendOk(source, "已关闭公司 " + company.name + "，剩余现金已退回个人账户");
            afterMutation(account);
        } catch (RuntimeException error) {
            DirectSupport.fail(source, reason(error));
        }
    }

    /** 股东名单：company shareholders <公司>（省略公司名时看自己的公司）。 */
    private static void companyShareholders(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        String nameText = parts.length > 2 ? DirectSupport.join(parts, 2, parts.length) : "";
        CompanyStore.Company company;
        if (nameText.isEmpty()) {
            company = mine(source, account, "");
            if (company == null) {
                return;
            }
        } else {
            company = DirectSupport.company(nameText);
            if (company == null) {
                DirectSupport.fail(source, "找不到公司：" + nameText + "（/ex company list 看列表）");
                return;
            }
        }
        List<String> lines = CompanyEngine.shareholders(ctx, company);
        Messages.send(source, "公司「" + company.name + "」股东（共 " + company.sharesTotal + " 股，估值 "
                + Messages.money(company.valuationCents) + "）：");
        if (lines == null || lines.isEmpty()) {
            Messages.sendInfo(source, "还没有股东记录");
            return;
        }
        List<String> formatted = new ArrayList<>();
        for (String line : lines) {
            formatted.add("· " + line);
        }
        sendPaged(source, formatted, 1, HOLDER_PER_PAGE);
    }

    /** 改名：company rename <公司> <新名字>（新名字取最后一段，不能带空格）。 */
    private static void companyRename(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String nameText;
        String newName;
        if (parts.length == 3) {
            nameText = "";
            newName = parts[2];
        } else if (parts.length >= 4) {
            newName = parts[parts.length - 1];
            nameText = DirectSupport.join(parts, 2, parts.length - 1);
        } else {
            DirectSupport.fail(source, "用法：/ex company rename <公司> <新名字>");
            return;
        }
        if (newName == null || newName.isEmpty()) {
            DirectSupport.fail(source, "用法：/ex company rename <公司> <新名字>");
            return;
        }
        CompanyStore.Company company = mine(source, account, nameText);
        if (company == null) {
            return;
        }
        ActionResult result = CompanyEngine.rename(ctx(), account, company, newName);
        sendResult(source, result);
        if (result != null && result.ok) {
            afterMutation(account);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ipo 分支
    // ══════════════════════════════════════════════════════════════

    private static void ipoRoot(CommandSourceStack source, Account account, String[] parts) {
        String sub = parts.length > 1 && parts[1] != null ? parts[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "":
            case "status":
                ipoStatus(source, account, parts);
                return;
            case "apply":
                ipoApply(source, account, parts);
                return;
            case "confirm":
                ipoConfirm(source, account);
                return;
            default:
                DirectSupport.fail(source, "不认识的 ipo 子命令：「" + parts[1] + "」。可用：status/apply/confirm");
        }
    }

    /** IPO 状态：ipo status [公司]（不写公司名时看自己的公司）。 */
    private static void ipoStatus(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        String nameText = parts.length > 2 ? DirectSupport.join(parts, 2, parts.length) : "";
        CompanyStore.Company company;
        if (nameText.isEmpty()) {
            company = mine(source, account, "");
            if (company == null) {
                return;
            }
        } else {
            company = DirectSupport.company(nameText);
            if (company == null) {
                DirectSupport.fail(source, "找不到公司：" + nameText + "（/ex company list 看列表）");
                return;
            }
        }
        ActionResult result = IpoEngine.status(ctx, company);
        if (result == null) {
            DirectSupport.fail(source, "查询 IPO 状态失败");
            return;
        }
        if (result.ok) {
            Messages.sendLines(source, result.message);
        } else {
            DirectSupport.fail(source, result.message);
        }
    }

    /** 递交招股书：ipo apply <0.10-0.49>（也接受 25 / 25% 这种写法）。 */
    private static void ipoApply(CommandSourceStack source, Account account, String[] parts) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        String ratioText = parts.length > 2 ? DirectSupport.join(parts, 2, parts.length) : "";
        if (ratioText.isEmpty()) {
            DirectSupport.fail(source, "用法：/ex ipo apply <流通比例 0.10-0.49>，例：/ex ipo apply 0.25");
            return;
        }
        double ratio = parseRatio(ratioText);
        if (Double.isNaN(ratio)) {
            DirectSupport.fail(source, "流通比例格式不对，例：/ex ipo apply 0.25");
            return;
        }
        if (ratio < IpoEngine.MIN_FLOAT_RATIO || ratio > IpoEngine.MAX_FLOAT_RATIO) {
            DirectSupport.fail(source, "流通比例要在 10%-49% 之间（当前 " + Messages.percent(ratio) + "）");
            return;
        }
        // IpoEngine.apply 不接受公司参数：它会自己挑名下第一家满足全部上市条件的公司
        ActionResult result = IpoEngine.apply(ctx(), account, ratio, null);
        sendResult(source, result);
        if (result != null && result.ok) {
            afterMutation(account);
        }
    }

    /** 确认上市：ipo confirm（递交后要过 1 个交易日）。 */
    private static void ipoConfirm(CommandSourceStack source, Account account) {
        if (!ready(source) || !needAccount(source, account)) {
            return;
        }
        ActionResult result = IpoEngine.confirm(ctx(), account);
        sendResult(source, result);
        if (result != null && result.ok) {
            afterMutation(account);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // gov 分支（只读）
    // ══════════════════════════════════════════════════════════════

    private static void govRoot(CommandSourceStack source, String[] parts) {
        String sub = parts.length > 1 && parts[1] != null ? parts[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "":
            case "status":
                govStatus(source);
                return;
            case "budget":
                govBudget(source);
                return;
            case "policies":
            case "policy":
                govPolicies(source);
                return;
            case "history":
                govHistory(source);
                return;
            default:
                DirectSupport.fail(source, "不认识的 gov 子命令：「" + parts[1] + "」。可用：status/budget/policies/history");
        }
    }

    /** 政府概览：利率/税率/预算/宏观指标。 */
    private static void govStatus(CommandSourceStack source) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        try {
            GovernmentEngine.updateIndicators(ctx, ctx.onlineAccounts);
        } catch (RuntimeException error) {
            // 指标刷新失败不影响这次查询，用旧快照
            Log.warn("政府指标刷新失败", error);
        }
        Messages.sendLines(source, govStatusText(ctx));
    }

    /** 政府预算与税率。 */
    private static void govBudget(CommandSourceStack source) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        GovernmentStore gov = ctx.government;
        Messages.send(source, "政府预算 " + Messages.money(gov.budgetCents())
                + "｜政策利率 " + Messages.percent(gov.policyRate)
                + "｜交易税 " + Messages.percent(gov.effectiveTradeTax())
                + "｜公司税 " + Messages.percent(gov.effectiveCompanyTax()));
        Messages.sendInfo(source, "税收进预算，福利/刺激等政策从预算出；预算不足时政策会被拒绝");
    }

    /** 生效中的政策。 */
    private static void govPolicies(CommandSourceStack source) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        long day = ctx.tradingDay();
        List<String> lines = new ArrayList<>();
        for (GovernmentStore.Policy policy : ctx.government.policies()) {
            if (policy == null || !ctx.government.isActive(policy, day)) {
                continue;
            }
            if (policy.params != null && policy.params.has("rejected")) {
                continue;
            }
            lines.add(actionName(policy.action) + " " + policy.paramsString()
                    + (policy.expiresDay >= 0L ? "（到第 " + policy.expiresDay + " 交易日为止）" : ""));
        }
        if (lines.isEmpty()) {
            Messages.sendInfo(source, "当前没有生效中的政策");
            return;
        }
        Messages.send(source, "生效中的政策（" + lines.size() + " 条）：");
        sendPaged(source, lines, 1, PER_PAGE);
    }

    /** 最近政策历史（新的在前）。 */
    private static void govHistory(CommandSourceStack source) {
        if (!ready(source)) {
            return;
        }
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (GovernmentStore.Policy policy : ctx.government.recentPolicies(20)) {
            if (policy == null) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append('#').append(policy.id).append(' ').append(actionName(policy.action));
            String params = policy.paramsString();
            if (params != null && !params.isBlank() && !"{}".equals(params)) {
                line.append(' ').append(params);
            }
            if (policy.reason != null && !policy.reason.isBlank()) {
                line.append(" — ").append(policy.reason);
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            Messages.sendInfo(source, "还没有政策记录");
            return;
        }
        Messages.send(source, "最近政策（新的在前）：");
        sendPaged(source, lines, 1, HOLDER_PER_PAGE);
    }

    /** 政府概览文本（和 /ex gov status 口径一致；引擎不提供现成文本，命令层那份是私有的）。 */
    private static String govStatusText(AimcContext ctx) {
        GovernmentStore gov = ctx.government;
        GovernmentStore.Indicators ind = gov.indicators;
        int active = 0;
        long day = ctx.tradingDay();
        for (GovernmentStore.Policy policy : gov.policies()) {
            if (policy != null && gov.isActive(policy, day)) {
                active++;
            }
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("政策利率 ").append(Messages.percent(gov.policyRate))
                .append("｜交易税 ").append(Messages.percent(gov.effectiveTradeTax()))
                .append("｜公司税 ").append(Messages.percent(gov.effectiveCompanyTax()))
                .append("｜预算 ").append(Messages.money(gov.budgetCents()))
                .append("｜生效政策 ").append(active).append(" 条\n");
        sb.append("通胀 7 日 ").append(Messages.changeText(ind.inflation7d))
                .append("｜大盘 7 日 ").append(Messages.changeText(ind.indexChange7d))
                .append("｜就业 ").append(Messages.percent(ind.employment))
                .append("｜压力分 ").append(ind.stress).append('\n');
        sb.append("货币总量 ").append(Messages.money(ind.moneyCents))
                .append("｜中位财富 ").append(Messages.money(ind.medianWealthCents))
                .append("｜7 日成交额 ").append(Messages.money(ind.volume7dCents));
        return sb.toString();
    }

    /** 政策代码 → 中文名（给玩家看的）。 */
    private static String actionName(String action) {
        if (action == null || action.isBlank()) {
            return "未知政策";
        }
        return switch (action) {
            case "rate_cut" -> "降息";
            case "rate_hike" -> "加息";
            case "welfare" -> "福利";
            case "stimulus" -> "行业刺激";
            case "austerity" -> "财政紧缩";
            case "subsidy" -> "物价补贴";
            case "tariff" -> "关税";
            case "liquidity" -> "流动性支持";
            case "min_wage" -> "最低工资";
            case "none" -> "无动作";
            default -> action;
        };
    }

    // ══════════════════════════════════════════════════════════════
    // 公共小工具
    // ══════════════════════════════════════════════════════════════

    private static AimcContext ctx() {
        return DirectSupport.ctx();
    }

    /** 市场是否加载好/是否维护中（和 /ex 命令层的 ready 口径一致）。 */
    private static boolean ready(CommandSourceStack source) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            DirectSupport.fail(source, "市场还没初始化（等服务器启动完成再试）");
            return false;
        }
        if (!ctx.settings.enabled) {
            DirectSupport.fail(source, "市场维护中（配置 general.enabled=false）");
            return false;
        }
        return true;
    }

    /** 需要游戏内玩家账户。 */
    private static boolean needAccount(CommandSourceStack source, Account account) {
        if (account != null) {
            return true;
        }
        if (source != null && source.getPlayer() == null) {
            DirectSupport.fail(source, "这条命令只能游戏内玩家使用");
        } else {
            DirectSupport.fail(source, "账户还没建好，重进一下服务器");
        }
        return false;
    }

    /**
     * 解析"玩家自己名下的公司"：text 为空时用唯一那家（有多家会提示写名字）。
     *
     * <p>注资/取现/招聘/裁员/分红/换行业/升级/增发/关停/改名都走这里，
     * 保证 AI 不会误操作别人公司（引擎里 fire/withdraw 之类本身不校验 CEO）。</p>
     */
    private static CompanyStore.Company mine(CommandSourceStack source, Account account, String text) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            DirectSupport.fail(source, "市场还没初始化（等服务器启动完成再试）");
            return null;
        }
        if (account == null) {
            needAccount(source, null);
            return null;
        }
        List<CompanyStore.Company> owned = ctx.companies.byCeo(account.uuid);
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            if (owned.isEmpty()) {
                DirectSupport.fail(source, "你还没有公司，先 /ex company create <名字> <采矿|农业|制造|贸易|服务|医疗|航天> 开一家");
                return null;
            }
            if (owned.size() > 1) {
                DirectSupport.fail(source, "你名下有 " + owned.size() + " 家公司，请在命令里写公司名（例：" + owned.get(0).name + "）");
                return null;
            }
            return owned.get(0);
        }
        for (CompanyStore.Company company : owned) {
            if (company.id.equalsIgnoreCase(value) || company.name.equalsIgnoreCase(value)
                    || (company.code != null && company.code.equalsIgnoreCase(value))) {
                return company;
            }
        }
        CompanyStore.Company byName = ctx.companies.findByName(value);
        if (byName != null && byName.ceoUuid != null && byName.ceoUuid.equals(account.uuid)) {
            return byName;
        }
        for (CompanyStore.Company company : owned) {
            if (company.name.contains(value) || value.contains(company.name)) {
                return company;
            }
        }
        DirectSupport.fail(source, "找不到你名下的公司：" + value + "（/ex company list 看列表）");
        return null;
    }

    /** 中文行业名 / 代码 → 内部代码（对应 MarketCommand.sectorCode，那份是私有的）。 */
    private static String sectorCode(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : CompanyStore.SECTORS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(value) || entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** "采矿(mining)、农业(farming)…" 的提示文案（对应 CompanyEngine.sectorChoices，那份是私有的）。 */
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

    /** 玩家名（CEO 不在线/查不到时给个兜底）。 */
    private static String ceoName(AimcContext ctx, CompanyStore.Company company) {
        if (ctx == null || company == null || company.ceoUuid == null || company.ceoUuid.isBlank()) {
            return "未知";
        }
        Account ceo = ctx.accounts.get(company.ceoUuid);
        if (ceo == null || ceo.name == null || ceo.name.isBlank()) {
            return "已离开的玩家";
        }
        return ceo.name;
    }

    /** 把引擎结果发给玩家；成功且带全服公告时一起广播（公司/IPO 用 [市场] 前缀）。 */
    private static void sendResult(CommandSourceStack source, ActionResult result) {
        if (result == null) {
            DirectSupport.fail(source, "操作失败");
            return;
        }
        if (result.ok) {
            DirectSupport.ok(source, result.message);
        } else {
            DirectSupport.fail(source, result.message);
        }
        if (result.ok && result.broadcast != null && !result.broadcast.isEmpty()) {
            Messages.broadcast(source, result.broadcast, false);
        }
    }

    /**
     * 逐行分页发送（不带可点击翻页按钮）。
     *
     * <p>{@code Messages.sendPage} 的按钮会执行 {@code "<commandBase> <页码>"}，但
     * {@code company list}、{@code company shareholders}、{@code gov policies}、{@code gov history}
     * 这些命令树都没有页码参数，点了会报错，所以这里自己切片、只在多页时提示页码。</p>
     */
    private static void sendPaged(CommandSourceStack source, List<String> lines, int page, int perPage) {
        if (lines == null || lines.isEmpty()) {
            Messages.sendInfo(source, "（没有内容）");
            return;
        }
        int size = Math.max(1, perPage);
        int pages = Math.max(1, (lines.size() + size - 1) / size);
        int current = Math.max(1, Math.min(page, pages));
        int from = (current - 1) * size;
        int to = Math.min(lines.size(), from + size);
        for (int i = from; i < to; i++) {
            Messages.send(source, lines.get(i));
        }
        if (pages > 1) {
            Messages.sendInfo(source, "第 " + current + "/" + pages + " 页");
        }
    }

    /** 页码文本：非法/小于 1 都按第 1 页。 */
    private static int pageOf(String text) {
        if (text == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(text.trim()));
        } catch (NumberFormatException error) {
            return 1;
        }
    }

    /** 正整数（招聘/裁员人数），超出范围或非法返回 -1。 */
    private static int parsePositiveInt(String text, int max) {
        if (text == null) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(text.trim());
            return parsed >= 1 && parsed <= max ? parsed : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** 流通比例：认 "0.25" / "25" / "25%" 三种写法，非法返回 NaN。 */
    private static double parseRatio(String text) {
        if (text == null) {
            return Double.NaN;
        }
        String value = text.trim().replace("%", "").replace("％", "");
        if (value.isEmpty()) {
            return Double.NaN;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return Double.NaN;
            }
            // 25 → 0.25；只对 1~100 的百分数写法做换算
            if (parsed > 1.0 && parsed <= 100.0) {
                parsed /= 100.0;
            }
            return parsed;
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    /** 操作成功后的政府宏观体检（和 /ex 命令层 player() 的副作用保持一致）。 */
    private static void afterMutation(Account account) {
        AimcContext ctx = ctx();
        if (ctx == null) {
            return;
        }
        try {
            GovernmentEngine.onPlayerMarketAction(ctx, account);
        } catch (RuntimeException error) {
            Log.warn("公司操作后的政府指标刷新失败", error);
        }
    }

    /** 异常文案（引擎层同样口径）。 */
    private static String reason(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
