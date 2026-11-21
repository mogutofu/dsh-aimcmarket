package com.mogutofu.aimcmarket.data;

/**
 * 有余额的账户。
 *
 * <p>复式记账的所有腿都落在实现这个接口的账户上。系统账户也算账户：
 * {@code sys:issued}（净发行，负数）、{@code market:maker}（做市商）、
 * {@code fee:pool}（手续费池）、{@code gov:budget}（政府预算）。</p>
 */
public interface BalanceAccount {
    /** 账户 id，例如 {@code player:<uuid>}、{@code company:c-0001}、{@code gov:budget}。 */
    String accountId();

    long balanceCents();

    /** 只允许 Ledger 调用。 */
    void setBalanceCents(long cents);
}
