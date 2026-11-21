package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个玩家的账户：钱包余额、信用分、持仓、基金份额、银行存款、公司。
 *
 * <p>余额单位是分（long）。钱包和银行存款是两个 {@link BalanceAccount}：
 * {@code player:<uuid>} 和 {@code bank:<uuid>}，都注册进 {@link Ledger}。</p>
 */
public final class Account implements BalanceAccount {
    /** 现实日（UTC）序号，签到/打工/工资的"每天"用它。 */
    public static final long MILLIS_PER_DAY = 86_400_000L;

    public final String uuid;
    public String name;

    private long balanceCents;
    public double creditScore = 600.0;

    // ── 现实时间的活动记录 ──
    public long createdAtMs;
    public long lastSeenMs;
    public long lastSalaryAtMs;
    public int salaryCount;
    public long salaryDayEpoch = Long.MIN_VALUE;
    public long lastWorkAtMs;
    public int workCount;
    public long workDayEpoch = Long.MIN_VALUE;
    public long lastDailyDayEpoch = Long.MIN_VALUE;
    public int dailyStreak;
    public long lastTradeAtMs;
    public long lastAiAtMs;
    /** 累计收入（分），贷款额度按"日均收入 ×3"估算用。 */
    public long totalIncomeCents;

    // ── 持仓 ──
    public final Map<String, StockHolding> stocks = new LinkedHashMap<>();
    public final Map<String, FundHolding> funds = new LinkedHashMap<>();
    public final BankState bank = new BankState();
    /** 玩家拥有的公司 id（CEO 身份）。 */
    public final List<String> companies = new ArrayList<>();

    private final BankAccount bankAccount = new BankAccount();

    public Account(String uuid) {
        this.uuid = uuid == null ? "" : uuid;
    }

    @Override
    public String accountId() {
        return "player:" + uuid;
    }

    public String bankAccountId() {
        return "bank:" + uuid;
    }

    @Override
    public long balanceCents() {
        return balanceCents;
    }

    @Override
    public void addBalanceCents(long delta) {
        balanceCents = Math.addExact(balanceCents, delta);
    }

    /** 银行存款账户（活期 + 定期），给 Ledger 记账用。 */
    public BalanceAccount bankAccount() {
        return bankAccount;
    }

    /** 现实日序号（UTC 日）。 */
    public static long dayEpoch(long nowMs) {
        return Math.floorDiv(nowMs, MILLIS_PER_DAY);
    }

    public long stockShares(String code) {
        StockHolding holding = stocks.get(code);
        return holding == null ? 0L : holding.shares;
    }

    public void addStock(String code, long shares, long costCents, long tick) {
        StockHolding holding = stocks.computeIfAbsent(code, key -> new StockHolding());
        if (holding.shares <= 0L) {
            holding.sinceTick = tick;
        }
        holding.shares = Math.addExact(holding.shares, shares);
        holding.costCents = Math.addExact(holding.costCents, costCents);
        if (holding.shares <= 0L) {
            stocks.remove(code);
        }
    }

    public long fundUnitsMilli(String code) {
        FundHolding holding = funds.get(code);
        return holding == null ? 0L : holding.unitsMilli;
    }

    public void addFundUnits(String code, long unitsMilli, long tick) {
        FundHolding holding = funds.computeIfAbsent(code, key -> new FundHolding());
        if (holding.unitsMilli <= 0L) {
            holding.sinceTick = tick;
        }
        holding.unitsMilli = Math.addExact(holding.unitsMilli, unitsMilli);
        if (holding.unitsMilli <= 0L) {
            funds.remove(code);
        }
    }

    /** 银行存款账户：余额 = 活期 + 所有定期本金。 */
    private final class BankAccount implements BalanceAccount {
        @Override
        public String accountId() {
            return bankAccountId();
        }

        @Override
        public long balanceCents() {
            return bank.totalDepositsCents();
        }

        @Override
        public void addBalanceCents(long delta) {
            bank.currentCents = Math.addExact(bank.currentCents, delta);
        }
    }

    // ── JSON ──

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("name", name == null ? "" : name);
        json.addProperty("balanceCents", balanceCents);
        json.addProperty("credit", creditScore);
        json.addProperty("createdAt", createdAtMs);
        json.addProperty("lastSeen", lastSeenMs);
        json.addProperty("lastSalaryAt", lastSalaryAtMs);
        json.addProperty("salaryCount", salaryCount);
        json.addProperty("salaryDay", salaryDayEpoch);
        json.addProperty("lastWorkAt", lastWorkAtMs);
        json.addProperty("workCount", workCount);
        json.addProperty("workDay", workDayEpoch);
        json.addProperty("lastDailyDay", lastDailyDayEpoch);
        json.addProperty("dailyStreak", dailyStreak);
        json.addProperty("lastTradeAt", lastTradeAtMs);
        json.addProperty("lastAiAt", lastAiAtMs);
        json.addProperty("totalIncomeCents", totalIncomeCents);

        JsonObject stockJson = new JsonObject();
        for (Map.Entry<String, StockHolding> entry : stocks.entrySet()) {
            JsonObject one = new JsonObject();
            one.addProperty("shares", entry.getValue().shares);
            one.addProperty("sinceTick", entry.getValue().sinceTick);
            one.addProperty("costCents", entry.getValue().costCents);
            stockJson.add(entry.getKey(), one);
        }
        json.add("stocks", stockJson);

        JsonObject fundJson = new JsonObject();
        for (Map.Entry<String, FundHolding> entry : funds.entrySet()) {
            JsonObject one = new JsonObject();
            one.addProperty("units", entry.getValue().unitsMilli);
            one.addProperty("sinceTick", entry.getValue().sinceTick);
            fundJson.add(entry.getKey(), one);
        }
        json.add("funds", fundJson);

        json.add("bank", bank.toJson());

        JsonArray companyArray = new JsonArray();
        for (String companyId : companies) {
            companyArray.add(companyId);
        }
        json.add("companies", companyArray);
        return json;
    }

    public static Account fromJson(JsonObject json) {
        Account account = new Account(JsonFile.str(json, "uuid", ""));
        account.name = JsonFile.str(json, "name", "");
        account.balanceCents = JsonFile.lng(json, "balanceCents", 0L);
        account.creditScore = JsonFile.dbl(json, "credit", 600.0);
        account.createdAtMs = JsonFile.lng(json, "createdAt", 0L);
        account.lastSeenMs = JsonFile.lng(json, "lastSeen", 0L);
        account.lastSalaryAtMs = JsonFile.lng(json, "lastSalaryAt", 0L);
        account.salaryCount = JsonFile.intVal(json, "salaryCount", 0);
        account.salaryDayEpoch = JsonFile.lng(json, "salaryDay", Long.MIN_VALUE);
        account.lastWorkAtMs = JsonFile.lng(json, "lastWorkAt", 0L);
        account.workCount = JsonFile.intVal(json, "workCount", 0);
        account.workDayEpoch = JsonFile.lng(json, "workDay", Long.MIN_VALUE);
        account.lastDailyDayEpoch = JsonFile.lng(json, "lastDailyDay", Long.MIN_VALUE);
        account.dailyStreak = JsonFile.intVal(json, "dailyStreak", 0);
        account.lastTradeAtMs = JsonFile.lng(json, "lastTradeAt", 0L);
        account.lastAiAtMs = JsonFile.lng(json, "lastAiAt", 0L);
        account.totalIncomeCents = JsonFile.lng(json, "totalIncomeCents", 0L);

        for (Map.Entry<String, JsonElement> entry : JsonFile.object(json, "stocks").entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject one = entry.getValue().getAsJsonObject();
            StockHolding holding = new StockHolding();
            holding.shares = JsonFile.lng(one, "shares", 0L);
            holding.sinceTick = JsonFile.lng(one, "sinceTick", 0L);
            holding.costCents = JsonFile.lng(one, "costCents", 0L);
            if (holding.shares > 0L) {
                account.stocks.put(entry.getKey(), holding);
            }
        }
        for (Map.Entry<String, JsonElement> entry : JsonFile.object(json, "funds").entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject one = entry.getValue().getAsJsonObject();
            FundHolding holding = new FundHolding();
            holding.unitsMilli = JsonFile.lng(one, "units", 0L);
            holding.sinceTick = JsonFile.lng(one, "sinceTick", 0L);
            if (holding.unitsMilli > 0L) {
                account.funds.put(entry.getKey(), holding);
            }
        }
        account.bank.load(JsonFile.object(json, "bank"));
        for (JsonElement element : JsonFile.array(json, "companies")) {
            try {
                account.companies.add(element.getAsString());
            } catch (RuntimeException ignored) {
                // 跳过坏数据
            }
        }
        return account;
    }

    // ── 子结构 ──

    public static final class StockHolding {
        public long shares;
        /** 首次买入时的市场 tick，用来判断持股是否满 1 交易日（分红条件）。 */
        public long sinceTick;
        public long costCents;
    }

    public static final class FundHolding {
        /** 份额 × 1000（1 份 = 1000）。 */
        public long unitsMilli;
        public long sinceTick;
    }

    /** 银行状态：活期 + 定期 + 贷款。 */
    public static final class BankState {
        public long currentCents;
        /** 活期利息的"微分之零头"，避免每 tick 四舍五入把利息算没了。 */
        public long interestCarryMicro;
        public final List<TermDeposit> terms = new ArrayList<>();
        public final List<Loan> loans = new ArrayList<>();

        public long totalDepositsCents() {
            long sum = currentCents;
            for (TermDeposit term : terms) {
                sum = Math.addExact(sum, term.amountCents);
            }
            return sum;
        }

        public long totalLoanCents() {
            long sum = 0L;
            for (Loan loan : loans) {
                sum = Math.addExact(sum, loan.remainingCents);
            }
            return sum;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("currentCents", currentCents);
            json.addProperty("interestCarryMicro", interestCarryMicro);
            JsonArray termArray = new JsonArray();
            for (TermDeposit term : terms) {
                JsonObject one = new JsonObject();
                one.addProperty("id", term.id);
                one.addProperty("amountCents", term.amountCents);
                one.addProperty("apr", term.apr);
                one.addProperty("startDay", term.startDay);
                one.addProperty("endDay", term.endDay);
                termArray.add(one);
            }
            json.add("terms", termArray);
            JsonArray loanArray = new JsonArray();
            for (Loan loan : loans) {
                JsonObject one = new JsonObject();
                one.addProperty("id", loan.id);
                one.addProperty("principalCents", loan.principalCents);
                one.addProperty("remainingCents", loan.remainingCents);
                one.addProperty("apr", loan.apr);
                one.addProperty("startDay", loan.startDay);
                one.addProperty("lastAccrualDay", loan.lastAccrualDay);
                one.addProperty("overdueDays", loan.overdueDays);
                loanArray.add(one);
            }
            json.add("loans", loanArray);
            return json;
        }

        public void load(JsonObject json) {
            currentCents = JsonFile.lng(json, "currentCents", 0L);
            interestCarryMicro = JsonFile.lng(json, "interestCarryMicro", 0L);
            for (JsonElement element : JsonFile.array(json, "terms")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject one = element.getAsJsonObject();
                TermDeposit term = new TermDeposit();
                term.id = JsonFile.lng(one, "id", 0L);
                term.amountCents = JsonFile.lng(one, "amountCents", 0L);
                term.apr = JsonFile.dbl(one, "apr", 0.0);
                term.startDay = JsonFile.lng(one, "startDay", 0L);
                term.endDay = JsonFile.lng(one, "endDay", 0L);
                if (term.amountCents > 0L) {
                    terms.add(term);
                }
            }
            for (JsonElement element : JsonFile.array(json, "loans")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject one = element.getAsJsonObject();
                Loan loan = new Loan();
                loan.id = JsonFile.lng(one, "id", 0L);
                loan.principalCents = JsonFile.lng(one, "principalCents", 0L);
                loan.remainingCents = JsonFile.lng(one, "remainingCents", 0L);
                loan.apr = JsonFile.dbl(one, "apr", 0.0);
                loan.startDay = JsonFile.lng(one, "startDay", 0L);
                loan.lastAccrualDay = JsonFile.lng(one, "lastAccrualDay", 0L);
                loan.overdueDays = JsonFile.intVal(one, "overdueDays", 0);
                if (loan.remainingCents > 0L) {
                    loans.add(loan);
                }
            }
        }
    }

    public static final class TermDeposit {
        public long id;
        public long amountCents;
        public double apr;
        public long startDay;
        public long endDay;
    }

    public static final class Loan {
        public long id;
        public long principalCents;
        public long remainingCents;
        public double apr;
        public long startDay;
        public long lastAccrualDay;
        public long overdueDays;
        public long accruedCents;
    }

    /** 玩家名下所有资产的粗略估值（钱包 + 存款 - 贷款 + 股票市值 + 基金市值）。 */
    public long netWorthCents(java.util.function.ToLongFunction<String> stockPrice,
                              java.util.function.ToLongFunction<String> fundValue) {
        long sum = balanceCents + bank.totalDepositsCents() - bank.totalLoanCents();
        for (Map.Entry<String, StockHolding> entry : stocks.entrySet()) {
            sum = Math.addExact(sum, Math.multiplyExact(stockPrice.applyAsLong(entry.getKey()), entry.getValue().shares));
        }
        for (Map.Entry<String, FundHolding> entry : funds.entrySet()) {
            sum = Math.addExact(sum, fundValue.applyAsLong(entry.getKey()));
        }
        return sum;
    }

    /** 信用分夹取。 */
    public void adjustCredit(double delta, double min, double max) {
        creditScore = Cents.clampDouble(creditScore + delta, min, max);
    }

    /** 记一笔收入（工资/打工/签到/分红等），用于贷款额度估算。 */
    public void recordIncome(long cents) {
        if (cents > 0L) {
            totalIncomeCents = Math.addExact(totalIncomeCents, cents);
        }
    }

    /** 账户存在多少现实日（至少按 1 天算）。 */
    public long ageDays(long nowMs) {
        long span = Math.max(0L, nowMs - createdAtMs);
        return Math.max(1L, span / MILLIS_PER_DAY);
    }
}
