package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 复式记账账本。
 *
 * <p>每一笔资金变动都必须走 {@link #post}，且所有腿加起来必须等于 0（分）。
 * 发钱 = 从 {@code sys:issued}（净发行账户，余额恒为负）借出；销毁 = 还回 {@code sys:issued}。
 * 因此"所有账户余额之和 = 0"是硬不变量，对账只需要加一遍。</p>
 *
 * <p>流水追加写到 {@code world/aimcmarket/ledger.jsonl}，一行一笔。</p>
 */
public final class Ledger {
    /** 净发行账户：余额是负数，绝对值等于市面上的货币总量。 */
    public static final String ISSUED = "sys:issued";
    /** 做市商/清算账户：点差、期货期权保证金与盈亏、强平费都从这里进。 */
    public static final String MAKER = "market:maker";
    /** 手续费池。 */
    public static final String FEE_POOL = "fee:pool";
    /** 政府预算账户。 */
    public static final String GOV_BUDGET = "gov:budget";

    private final Map<String, BalanceAccount> accounts = new LinkedHashMap<>();
    private final Path file;
    private long issuedCents;
    private long seq;

    private final BalanceAccount issued = new BalanceAccount() {
        @Override
        public String accountId() {
            return ISSUED;
        }

        @Override
        public long balanceCents() {
            return issuedCents;
        }

        @Override
        public void addBalanceCents(long delta) {
            issuedCents = Math.addExact(issuedCents, delta);
        }
    };

    public Ledger(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public BalanceAccount issuedAccount() {
        return issued;
    }

    /** 注册一个余额账户（重复注册同一个 id 会覆盖引用，余额以先注册的实例为准）。 */
    public void register(BalanceAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("account 不能为空");
        }
        accounts.put(account.accountId(), account);
    }

    public void unregister(String accountId) {
        if (accountId != null && !ISSUED.equals(accountId)) {
            accounts.remove(accountId);
        }
    }

    public BalanceAccount find(String accountId) {
        if (ISSUED.equals(accountId)) {
            return issued;
        }
        return accounts.get(accountId);
    }

    public Collection<BalanceAccount> accounts() {
        return accounts.values();
    }

    public long issuedCents() {
        return issuedCents;
    }

    /** 市面上流通的钱 = 除净发行账户之外所有账户余额之和。 */
    public long circulationCents() {
        long sum = 0;
        for (BalanceAccount account : accounts.values()) {
            sum = Math.addExact(sum, account.balanceCents());
        }
        return sum;
    }

    /** 恒等式：流通量 + 净发行 = 0。 */
    public boolean isBalanced() {
        return Math.addExact(circulationCents(), issuedCents) == 0L;
    }

    public long seq() {
        return seq;
    }

    // ── 记账 ──

    /** 一条腿：账户 + 变动金额（分）。 */
    public static final class Leg {
        final BalanceAccount account;
        final long delta;

        private Leg(BalanceAccount account, long delta) {
            this.account = account;
            this.delta = delta;
        }

        BalanceAccount account() {
            return account;
        }

        long delta() {
            return delta;
        }
    }

    public static Leg leg(BalanceAccount account, long delta) {
        return new Leg(account, delta);
    }

    /**
     * 记一笔账。
     *
     * @param kind  业务类型，例如 {@code trade.buy}、{@code stock.dividend}、{@code gov.welfare}
     * @param actor 操作者（玩家名、系统名），可以空字符串
     * @param memo  备注（给人和对账看的）
     * @param legs  至少两条腿，加起来必须为 0
     * @throws IllegalStateException 账户未注册、记账不平、余额溢出/不该为负
     */
    public void post(String kind, String actor, String memo, Leg... legs) {
        if (legs == null || legs.length < 2) {
            throw new IllegalArgumentException("记账至少要两条腿：" + kind);
        }
        long sum = 0L;
        long[] next = new long[legs.length];
        for (int i = 0; i < legs.length; i++) {
            Leg leg = legs[i];
            if (leg == null || leg.account == null) {
                throw new IllegalArgumentException("记账腿为空：" + kind);
            }
            String id = leg.account.accountId();
            if (!ISSUED.equals(id) && !accounts.containsKey(id)) {
                throw new IllegalStateException("账户未注册：" + id + "（业务：" + kind + "）");
            }
            long current = leg.account.balanceCents();
            long after;
            try {
                after = Math.addExact(current, leg.delta);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("余额溢出：" + id, overflow);
            }
            if (after < 0 && !allowsNegative(id)) {
                throw new IllegalStateException("余额为负（" + id + "，" + Cents.format(after) + " 分），业务：" + kind);
            }
            next[i] = after;
            sum = Math.addExact(sum, leg.delta);
        }
        if (sum != 0L) {
            throw new IllegalStateException("记账不平（差额 " + sum + " 分），业务：" + kind);
        }
        // 先写流水（写失败就不动余额），再真正落账。
        seq++;
        append(kind, actor, memo, legs);
        for (int i = 0; i < legs.length; i++) {
            Leg leg = legs[i];
            if (ISSUED.equals(leg.account.accountId())) {
                issuedCents = next[i];
            } else {
                leg.account.addBalanceCents(leg.delta);
            }
        }
    }

    /** 发钱：从净发行账户出。 */
    public void issue(BalanceAccount to, long amountCents, String kind, String actor, String memo) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("发行金额必须为正：" + amountCents);
        }
        post(kind, actor, memo, leg(issued, -amountCents), leg(to, amountCents));
    }

    /** 销毁钱：还给净发行账户。 */
    public void burn(BalanceAccount from, long amountCents, String kind, String actor, String memo) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("销毁金额必须为正：" + amountCents);
        }
        post(kind, actor, memo, leg(from, -amountCents), leg(issued, amountCents));
    }

    /** 账户间转账。 */
    public void transfer(BalanceAccount from, BalanceAccount to, long amountCents, String kind, String actor, String memo) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("转账金额必须为正：" + amountCents);
        }
        post(kind, actor, memo, leg(from, -amountCents), leg(to, amountCents));
    }

    private static boolean allowsNegative(String accountId) {
        return accountId.startsWith("sys:")
                || MAKER.equals(accountId)
                || accountId.startsWith("company:");
    }

    private void append(String kind, String actor, String memo, Leg[] legs) {
        JsonObject line = new JsonObject();
        line.addProperty("seq", seq);
        line.addProperty("at", System.currentTimeMillis());
        line.addProperty("kind", kind == null ? "" : kind);
        line.addProperty("actor", actor == null ? "" : actor);
        line.addProperty("memo", memo == null ? "" : memo);
        JsonArray legArray = new JsonArray();
        for (Leg leg : legs) {
            JsonArray one = new JsonArray();
            one.add(leg.account.accountId());
            one.add(leg.delta);
            legArray.add(one);
        }
        line.add("legs", legArray);
        try {
            JsonFile.appendLine(file, line.toString());
        } catch (IOException error) {
            seq--;
            throw new IllegalStateException("写账本失败，交易已取消：" + error.getMessage(), error);
        }
    }

    // ── 对账 ──

    /**
     * 对账修正：只往 ledger.jsonl 追加调整分录，让「重放结果」和内存里的真实余额对齐。
     *
     * <p>不动任何余额、不改净发行计数器——只是把历史上因为崩溃/手工改动丢掉的差额补成一条账。
     * 每条调整分录都是「账户 +delta / sys:issued -delta」，加起来为 0，恒等式不受影响。</p>
     *
     * @return 修正的账户数；读账本失败返回 -1
     */
    public int reconcileSilently(String actor) {
        Map<String, Long> replayed = new LinkedHashMap<>();
        int[] badLines = {0};
        if (replayInto(replayed, badLines) == null) {
            return -1;
        }
        int fixed = 0;
        for (BalanceAccount account : accounts.values()) {
            long expected = replayed.getOrDefault(account.accountId(), 0L);
            long delta = account.balanceCents() - expected;
            if (delta == 0L) {
                continue;
            }
            seq++;
            append("ledger.reconcile", actor, "对账修正：只补账本，不动余额", new Leg[]{
                    leg(account, delta), leg(issued, -delta)});
            fixed++;
        }
        return fixed;
    }

    /**
     * 用 ledger.jsonl 重放，逐账户核对余额。
     *
     * @return 对不上的账户数（0 = 完全一致）；同时返回重放出来的净发行额
     */
    public ReplayResult replayAndVerify() {
        Map<String, Long> replayed = new LinkedHashMap<>();
        int[] badCounter = {0};
        Long replayedIssuedValue = replayInto(replayed, badCounter);
        if (replayedIssuedValue == null) {
            return new ReplayResult(-1, 0L, 0L, 1, "读账本失败");
        }
        long replayedIssued = replayedIssuedValue;
        int badLines = badCounter[0];
        int mismatches = badLines;
        StringBuilder detail = new StringBuilder();
        for (BalanceAccount account : accounts.values()) {
            long expected = replayed.getOrDefault(account.accountId(), 0L);
            long actual = account.balanceCents();
            if (expected != actual) {
                mismatches++;
                if (detail.length() < 400) {
                    detail.append(account.accountId()).append(" 账本=").append(expected)
                            .append(" 实际=").append(actual).append("；");
                }
            }
        }
        if (replayedIssued != issuedCents) {
            mismatches++;
            detail.append("净发行 账本=").append(replayedIssued).append(" 实际=").append(issuedCents).append("；");
        }
        return new ReplayResult(mismatches, issuedCents, replayedIssued, badLines, detail.toString());
    }

    /**
     * 读账本把每个账户的净额累加到 {@code replayed}，返回净发行额；IO 失败返回 {@code null}。
     *
     * <p>解析不了的行计入 {@code badLines[0]}，不影响其余行。</p>
     */
    private Long replayInto(Map<String, Long> replayed, int[] badLines) {
        long replayedIssued = 0L;
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    JsonObject obj;
                    try {
                        JsonElement parsed = JsonParser.parseString(line);
                        obj = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                    } catch (RuntimeException error) {
                        obj = null;
                    }
                    if (obj == null) {
                        badLines[0]++;
                        continue;
                    }
                    long sum = 0L;
                    for (JsonElement legElement : JsonFile.array(obj, "legs")) {
                        if (!legElement.isJsonArray() || legElement.getAsJsonArray().size() < 2) {
                            badLines[0]++;
                            continue;
                        }
                        JsonArray leg = legElement.getAsJsonArray();
                        String id = leg.get(0).getAsString();
                        long delta = leg.get(1).getAsLong();
                        sum = Math.addExact(sum, delta);
                        if (ISSUED.equals(id)) {
                            replayedIssued = Math.addExact(replayedIssued, delta);
                        } else {
                            replayed.put(id, Math.addExact(replayed.getOrDefault(id, 0L), delta));
                        }
                    }
                    if (sum != 0L) {
                        badLines[0]++;
                    }
                }
            } catch (IOException error) {
                return null;
            }
        }
        return replayedIssued;
    }

    /** 重放核对结果。 */
    public static final class ReplayResult {
        public final int mismatches;
        public final long issuedCents;
        public final long replayedIssuedCents;
        public final int badLines;
        public final String detail;

        ReplayResult(int mismatches, long issuedCents, long replayedIssuedCents, int badLines, String detail) {
            this.mismatches = mismatches;
            this.issuedCents = issuedCents;
            this.replayedIssuedCents = replayedIssuedCents;
            this.badLines = badLines;
            this.detail = detail;
        }

        public boolean ok() {
            return mismatches == 0;
        }
    }

    /**
     * 所有数据文件刚加载完时调用：以"其它账户余额之和"为准，重新锚定净发行额。
     * 这样即使 ledger.jsonl 被删了，账也不会立刻失衡（但 replay 会报出来）。
     */
    public void reanchorIssued() {
        issuedCents = -circulationCents();
    }

    /** 账本行数（自本轮启动以来的流水条数）。 */
    public long seqValue() {
        return seq;
    }

    /** 只读快照，给 /ex admin stats 用。 */
    public Map<String, Long> balanceSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (BalanceAccount account : accounts.values()) {
            snapshot.put(account.accountId(), account.balanceCents());
        }
        snapshot.put(ISSUED, issuedCents);
        return snapshot;
    }

    /** 数据文件的时间戳（调试用）。 */
    public String lastWriteIso() {
        return Instant.now().toString();
    }
}
