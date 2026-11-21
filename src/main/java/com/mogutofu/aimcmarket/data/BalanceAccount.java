package com.mogutofu.aimcmarket.data;

/**
 * 有余额的账户。
 *
 * <p>复式记账的每条腿都落在一个实现这个接口的账户上。除了玩家钱包，系统账户也算账户：
 * {@code sys:issued}（净发行，负数）、{@code market:maker}（做市商/清算）、
 * {@code fee:pool}（手续费池）、{@code gov:budget}（政府预算）、
 * {@code bank:<uuid>}（玩家存款，含活期和定期）。</p>
 *
 * <p>注意：{@link #addBalanceCents(long)} 是增量接口，不是 setter。
 * 有些账户的余额是聚合出来的（比如银行存款 = 活期 + 定期），只有增量才能正确落账。</p>
 */
public interface BalanceAccount {
    /** 账户 id，例如 {@code player:<uuid>}、{@code company:c-0001}、{@code gov:budget}。 */
    String accountId();

    long balanceCents();

    /** 只允许 {@link Ledger} 调用。 */
    void addBalanceCents(long delta);
}
