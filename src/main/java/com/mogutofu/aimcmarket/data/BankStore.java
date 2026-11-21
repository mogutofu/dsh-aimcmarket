package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 银行：活期、定期、贷款、信用分。
 *
 * <p>活期和定期都算在玩家的"银行存款账户"({@code bank:<uuid>})里，只是内部记成活期/定期两笔；
 * 利息由系统增发（{@code ledger.issue}），贷款利息销毁（{@code ledger.burn}），
 * 和 DESIGN.md 的记账口径一致。</p>
 *
 * <p>利息口径：活期"按分钟计息"（每个 market tick 按经过的分钟数累计），
 * 定期和贷款按<b>交易日</b>计息（apr × 天数 / 365）。</p>
 */
public final class BankStore {
    private final Path file;
    private final Ledger ledger;
    private long totalDepositsCents;
    private long totalLoansCents;
    private long interestPaidCents;
    private long badDebtCents;

    public BankStore(Path file, Ledger ledger) {
        this.file = file;
        this.ledger = ledger;
    }

    public void load() {
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            totalDepositsCents = JsonFile.lng(root, "totalDepositsCents", 0L);
            totalLoansCents = JsonFile.lng(root, "totalLoansCents", 0L);
            interestPaidCents = JsonFile.lng(root, "interestPaidCents", 0L);
            badDebtCents = JsonFile.lng(root, "badDebtCents", 0L);
        }
        Log.info("银行加载完成");
    }

    public void save(Accounts accounts) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        totalDepositsCents = 0L;
        totalLoansCents = 0L;
        for (Account account : accounts.all()) {
            totalDepositsCents = Math.addExact(totalDepositsCents, account.bank.totalDepositsCents());
            totalLoansCents = Math.addExact(totalLoansCents, account.bank.totalLoanCents());
        }
        root.addProperty("totalDepositsCents", totalDepositsCents);
        root.addProperty("totalLoansCents", totalLoansCents);
        root.addProperty("interestPaidCents", interestPaidCents);
        root.addProperty("badDebtCents", badDebtCents);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 bank.json 失败（下个 tick 重试）", error);
        }
    }

    // ── 利率 ──

    public double currentRate(double policyRate, MarketSettings settings) {
        return Math.max(Cents.clampDouble(policyRate + settings.currentRateSpread, 0.0, 0.2), settings.currentRateFloor);
    }

    public double termRate(double policyRate, int days, MarketSettings settings) {
        double spread = days >= 30 ? settings.term30Spread : settings.term7Spread;
        return Cents.clampDouble(policyRate + spread, 0.0, 0.3);
    }

    public double loanRate(double policyRate, MarketSettings settings) {
        return Cents.clampDouble(policyRate + settings.loanRateSpread, 0.0, 0.5);
    }

    /** 贷款额度：min(5000 币, 30 天平均日收入 ×3)。账户越老，日均收入越准。 */
    public long creditLimit(Account account, MarketSettings settings, long nowMs) {
        long ageDays = account.ageDays(nowMs);
        long avgDailyIncome = account.totalIncomeCents / ageDays;
        long limit = Cents.mul(avgDailyIncome, 3L);
        limit = Math.min(limit, 500_000L);
        if (account.creditScore < 500.0) {
            limit = limit / 2L;
        }
        return Math.max(0L, limit);
    }

    // ── 存取 ──

    public void deposit(Account account, long amountCents) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("存款金额必须为正");
        }
        ledger.transfer(account, account.bankAccount(), amountCents, "bank.deposit", account.name, "活期存入");
        account.bank.currentCents = Math.addExact(account.bank.currentCents, 0L); // 余额已由 Ledger 加到活期
    }

    public void withdraw(Account account, long amountCents) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("取款金额必须为正");
        }
        if (account.bank.currentCents < amountCents) {
            throw new IllegalStateException("活期余额不足（活期 " + Cents.format(account.bank.currentCents) + " 币）");
        }
        ledger.transfer(account.bankAccount(), account, amountCents, "bank.withdraw", account.name, "活期取出");
    }

    public Account.TermDeposit openTerm(Account account, long amountCents, int days, long currentDay,
                                        double policyRate, MarketSettings settings) {
        if (days != 7 && days != 30) {
            throw new IllegalArgumentException("定期只能是 7 或 30 交易日");
        }
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("定期金额必须为正");
        }
        if (account.bank.currentCents < amountCents) {
            throw new IllegalStateException("活期余额不足（活期 " + Cents.format(account.bank.currentCents) + " 币）");
        }
        // 活期 → 定期：总额不变，内部挪一下，不用记账
        account.bank.currentCents = Math.subtractExact(account.bank.currentCents, amountCents);
        Account.TermDeposit term = new Account.TermDeposit();
        term.id = System.nanoTime();
        term.amountCents = amountCents;
        term.apr = termRate(policyRate, days, settings);
        term.startDay = currentDay;
        term.endDay = currentDay + days;
        account.bank.terms.add(term);
        return term;
    }

    /**
     * 活期按分钟计息：每个 market tick 调用，{@code minutes} 是这一 tick 经过的现实分钟。
     */
    public void accrueCurrentInterest(Accounts accounts, long minutes, double policyRate, MarketSettings settings) {
        if (minutes <= 0L) {
            return;
        }
        double rate = currentRate(policyRate, settings);
        // 年化利率换算成"每 525600 分钟"的利息，用微分做四舍五入，避免小余额永远算不出利息
        long rateBp = Math.round(rate * 10_000.0);
        for (Account account : accounts.all()) {
            long balance = account.bank.currentCents;
            if (balance <= 0L || rateBp <= 0L) {
                continue;
            }
            long micro = Cents.mulDiv(balance, Math.multiplyExact(rateBp, minutes) * 1_000_000L, 10_000L * 525_600L);
            micro = Math.addExact(micro, account.bank.interestCarryMicro);
            long cents = micro / 1_000_000L;
            account.bank.interestCarryMicro = micro % 1_000_000L;
            if (cents > 0L) {
                ledger.issue(account.bankAccount(), cents, "bank.interest", account.name, "活期利息");
                interestPaidCents = Math.addExact(interestPaidCents, cents);
            }
        }
    }

    /** 交易日推进时调用：定期到期、贷款计息/催收。 */
    public void onTradingDay(Accounts accounts, long currentDay, double policyRate, MarketSettings settings, long nowMs) {
        for (Account account : accounts.all()) {
            // 定期到期：本金回到活期，利息增发
            for (Account.TermDeposit term : new java.util.ArrayList<>(account.bank.terms)) {
                if (currentDay < term.endDay) {
                    continue;
                }
                long days = Math.max(1L, term.endDay - term.startDay);
                long interest = Cents.percent(term.amountCents, term.apr * days / 365.0);
                account.bank.terms.remove(term);
                account.bank.currentCents = Math.addExact(account.bank.currentCents, term.amountCents);
                if (interest > 0L) {
                    ledger.issue(account.bankAccount(), interest, "bank.termInterest", account.name, "定期利息");
                    interestPaidCents = Math.addExact(interestPaidCents, interest);
                }
            }
            // 贷款：计息 + 自动扣款 + 逾期
            for (Account.Loan loan : new java.util.ArrayList<>(account.bank.loans)) {
                long days = Math.max(0L, currentDay - loan.lastAccrualDay);
                if (days > 0L) {
                    long interest = Cents.percent(loan.remainingCents, loan.apr * days / 365.0);
                    loan.remainingCents = Math.addExact(loan.remainingCents, interest);
                    loan.lastAccrualDay = currentDay;
                }
                long due = Math.min(loan.remainingCents, Math.max(100L, loan.remainingCents / 30L + 100L));
                boolean paid = false;
                if (account.balanceCents() >= due) {
                    ledger.burn(account, due, "bank.loanRepay", account.name, "贷款自动还款");
                    paid = true;
                } else if (account.bank.currentCents >= due) {
                    ledger.burn(account.bankAccount(), due, "bank.loanRepay", account.name, "贷款自动还款（存款）");
                    account.bank.currentCents = Math.subtractExact(account.bank.currentCents, due);
                    paid = true;
                }
                if (paid) {
                    loan.remainingCents = Math.subtractExact(loan.remainingCents, due);
                    if (loan.remainingCents <= 0L) {
                        account.bank.loans.remove(loan);
                        account.adjustCredit(5.0, settings.creditMin, settings.creditMax);
                    }
                } else {
                    loan.overdueDays++;
                    account.adjustCredit(-30.0, settings.creditMin, settings.creditMax);
                }
            }
        }
    }

    // ── 贷款 ──

    public Account.Loan takeLoan(Account account, long amountCents, long currentDay, double policyRate, MarketSettings settings, long nowMs) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("贷款金额必须为正");
        }
        if (amountCents > 500_000L) {
            throw new IllegalStateException("单笔贷款上限 5000 币");
        }
        long limit = creditLimit(account, settings, nowMs);
        if (amountCents > limit) {
            throw new IllegalStateException("超出贷款额度（当前额度 " + Cents.format(limit) + " 币）");
        }
        Account.Loan loan = new Account.Loan();
        loan.id = System.nanoTime();
        loan.principalCents = amountCents;
        loan.remainingCents = amountCents;
        loan.apr = loanRate(policyRate, settings);
        loan.startDay = currentDay;
        loan.lastAccrualDay = currentDay;
        account.bank.loans.add(loan);
        // 贷款本金：从系统增发到玩家钱包
        ledger.issue(account, amountCents, "bank.loan", account.name, "贷款发放");
        return loan;
    }

    /** 手动还款，返回实际还掉的钱。 */
    public long repayLoan(Account account, long amountCents, MarketSettings settings) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("还款金额必须为正");
        }
        long remaining = account.bank.totalLoanCents();
        if (remaining <= 0L) {
            throw new IllegalStateException("没有未还的贷款");
        }
        long pay = Math.min(amountCents, remaining);
        if (account.balanceCents() < pay) {
            throw new IllegalStateException("余额不足");
        }
        ledger.burn(account, pay, "bank.loanRepay", account.name, "手动还款");
        long left = pay;
        for (Account.Loan loan : new java.util.ArrayList<>(account.bank.loans)) {
            long take = Math.min(left, loan.remainingCents);
            loan.remainingCents -= take;
            left -= take;
            if (loan.remainingCents <= 0L) {
                account.bank.loans.remove(loan);
                account.adjustCredit(5.0, settings.creditMin, settings.creditMax);
            }
            if (left <= 0L) {
                break;
            }
        }
        return pay;
    }

    /** 提前支取定期：按活期利率重算，返回取出的现金。 */
    public long withdrawTerm(Account account, Account.TermDeposit term, long currentDay, double policyRate, MarketSettings settings) {
        long days = Math.max(1L, Math.min(currentDay - term.startDay, term.endDay - term.startDay));
        long interest = Cents.percent(term.amountCents, currentRate(policyRate, settings) * days / 365.0);
        account.bank.terms.remove(term);
        account.bank.currentCents = Math.addExact(account.bank.currentCents, term.amountCents);
        if (interest > 0L) {
            ledger.issue(account.bankAccount(), interest, "bank.earlyInterest", account.name, "定期提前支取按活期重算");
            interestPaidCents = Math.addExact(interestPaidCents, interest);
        }
        return term.amountCents + interest;
    }

    public long totalDepositsCents() {
        return totalDepositsCents;
    }

    public long totalLoansCents() {
        return totalLoansCents;
    }

    public long interestPaidCents() {
        return interestPaidCents;
    }

    public long badDebtCents() {
        return badDebtCents;
    }

    public Path file() {
        return file;
    }
}
