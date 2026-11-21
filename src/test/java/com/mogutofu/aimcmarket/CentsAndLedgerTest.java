package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.data.BalanceAccount;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.util.Cents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Cents} 金额工具与 {@link Ledger} 复式记账的单元测试。
 *
 * <p>纯 Java，不碰 Minecraft：金额一律 long 分，账本每笔腿和必须为 0。</p>
 */
class CentsAndLedgerTest {

    @TempDir
    Path tempDir;

    /** 只用于账本测试的极简余额账户。 */
    private static final class TestAccount implements BalanceAccount {
        private final String id;
        private long balance;

        TestAccount(String id) {
            this.id = id;
        }

        @Override
        public String accountId() {
            return id;
        }

        @Override
        public long balanceCents() {
            return balance;
        }

        @Override
        public void addBalanceCents(long delta) {
            balance = Math.addExact(balance, delta);
        }
    }

    private static TestAccount account(String id) {
        return new TestAccount(id);
    }

    // ══════════════════════════════════════════════════════════════
    // Cents：格式化 / 解析
    // ══════════════════════════════════════════════════════════════

    @Test
    void formatCents() {
        assertEquals("12.34", Cents.format(1234L), "1234 分应显示为 12.34");
        assertEquals("0.05", Cents.format(5L), "5 分应显示为 0.05（补零）");
        assertEquals("-12.34", Cents.format(-1234L), "负数应带负号");
        assertEquals("-0.05", Cents.format(-5L), "负的小额也要补零");
        assertEquals("0.00", Cents.format(0L), "0 应显示为 0.00");
        assertEquals("1.00", Cents.format(100L), "100 分应显示为 1.00");
        assertEquals("1234.56", Cents.format(123456L), "123456 分应显示为 1234.56");
    }

    @Test
    void formatSignedCents() {
        assertEquals("+12.34", Cents.formatSigned(1234L), "正数要带 + 号");
        assertEquals("-0.50", Cents.formatSigned(-50L), "负数保留负号");
        assertEquals("0.00", Cents.formatSigned(0L), "0 不带符号");
    }

    @Test
    void parseCentsAcceptsDecimalInput() {
        assertEquals(300L, Cents.parse("3"), "整数 3 币 = 300 分");
        assertEquals(350L, Cents.parse("3.5"), "3.5 币 = 350 分");
        assertEquals(355L, Cents.parse("3.55"), "两位小数应精确");
        assertEquals(5L, Cents.parse("0.05"), "0.05 币 = 5 分");
        assertEquals(200L, Cents.parse("+2"), "带 + 号的整数");
        assertEquals(-125L, Cents.parse("-1.25"), "负数解析");
        assertEquals(123400L, Cents.parse("1,234"), "逗号分隔应被去掉");
        assertEquals(123456L, Cents.parse("1,234.56"), "逗号 + 两位小数");
        assertEquals(300L, Cents.parse(" 3 "), "两端空白应忽略");
        assertEquals(0L, Cents.parse("0"), "0 是合法金额");
        assertEquals(-5L, Cents.parse("-0.05"), "负的小额解析");
    }

    @Test
    void parseCentsRejectsInvalidInput() {
        assertEquals(-1L, Cents.parse(null), "null 应返回 -1");
        assertEquals(-1L, Cents.parse(""), "空串应返回 -1");
        assertEquals(-1L, Cents.parse("   "), "空白应返回 -1");
        assertEquals(-1L, Cents.parse("abc"), "非数字应返回 -1");
        assertEquals(-1L, Cents.parse("12a"), "夹字母应返回 -1");
        assertEquals(-1L, Cents.parse("1.234"), "三位小数应返回 -1");
        assertEquals(-1L, Cents.parse("1..2"), "多小数点应返回 -1");
        assertEquals(-1L, Cents.parse("."), "只有小数点应返回 -1");
        assertEquals(-1L, Cents.parse("-"), "只有负号应返回 -1");
        assertEquals(-1L, Cents.parse("1".repeat(21)), "超长输入应返回 -1");
    }

    // ══════════════════════════════════════════════════════════════
    // Cents：运算 / 夹取 / 溢出
    // ══════════════════════════════════════════════════════════════

    @Test
    void mulDivRoundsHalfUp() {
        assertEquals(8L, Cents.mulDiv(10L, 3L, 4L), "30/4 = 7.5 应四舍五入为 8");
        assertEquals(3L, Cents.mulDiv(10L, 1L, 4L), "10/4 = 2.5 应四舍五入为 3");
        assertEquals(0L, Cents.mulDiv(1L, 1L, 3L), "1/3 = 0.33 应舍入为 0");
        assertEquals(1L, Cents.mulDiv(2L, 1L, 3L), "2/3 = 0.67 应进位为 1");
        assertEquals(5L, Cents.mulDiv(7L, 3L, 4L), "21/4 = 5.25 应舍入为 5");
        assertEquals(-8L, Cents.mulDiv(-10L, 3L, 4L), "-7.5 应远离零舍入为 -8");
        assertEquals(Long.MAX_VALUE, Cents.mulDiv(Long.MAX_VALUE, 2L, 2L), "中间乘积用 BigInteger，不应溢出");
        assertEquals(Long.MAX_VALUE, Cents.mulDiv(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                "极端乘积也不应溢出");
        assertThrows(ArithmeticException.class, () -> Cents.mulDiv(1L, 1L, 0L), "除数为 0 应抛异常");
    }

    @Test
    void percentRoundsToCents() {
        assertEquals(62L, Cents.percent(1234L, 0.05), "1234 × 5% = 61.7 应舍入为 62");
        assertEquals(0L, Cents.percent(1L, 0.4), "0.4 分应舍入为 0");
        assertEquals(2L, Cents.percent(3L, 0.5), "1.5 分应四舍五入为 2");
        assertThrows(IllegalArgumentException.class, () -> Cents.percent(100L, Double.NaN),
                "NaN 比率应抛 IllegalArgumentException");
        assertThrows(IllegalArgumentException.class, () -> Cents.percent(100L, Double.POSITIVE_INFINITY),
                "无穷比率应抛 IllegalArgumentException");
        assertThrows(ArithmeticException.class, () -> Cents.percent(Long.MAX_VALUE, 2.0),
                "比率导致结果溢出应抛 ArithmeticException");
    }

    @Test
    void clampBounds() {
        assertEquals(5L, Cents.clamp(5L, 1L, 10L), "区间内原样返回");
        assertEquals(1L, Cents.clamp(-3L, 1L, 10L), "低于下限取下限");
        assertEquals(10L, Cents.clamp(30L, 1L, 10L), "高于上限取上限");
        assertThrows(IllegalArgumentException.class, () -> Cents.clamp(0L, 10L, 1L), "min > max 应抛异常");
        assertEquals(100, Cents.clampInt(500, 1, 100), "clampInt 应夹到上限");
        assertEquals(0.0, Cents.clampDouble(-1.0, 0.0, 1.0), 1.0e-12, "clampDouble 应夹到下限");
        assertEquals(1.0, Cents.clampDouble(2.5, 0.0, 1.0), 1.0e-12, "clampDouble 应夹到上限");
    }

    @Test
    void overflowThrows() {
        assertThrows(ArithmeticException.class, () -> Cents.add(Long.MAX_VALUE, 1L), "加法溢出应抛异常");
        assertThrows(ArithmeticException.class, () -> Cents.sub(Long.MIN_VALUE, 1L), "减法溢出应抛异常");
        assertThrows(ArithmeticException.class, () -> Cents.mul(Long.MAX_VALUE, 2L), "乘法溢出应抛异常");
        assertThrows(ArithmeticException.class, () -> Cents.negate(Long.MIN_VALUE), "取负溢出应抛异常");
        assertThrows(ArithmeticException.class, () -> Cents.abs(Long.MIN_VALUE), "绝对值溢出应抛异常");
    }

    @Test
    void formatPercent() {
        assertEquals("0.25%", Cents.formatPercent(0.0025), "25bp 应显示为 0.25%");
        assertEquals("5.00%", Cents.formatPercent(0.05), "5% 应显示为 5.00%");
    }

    // ══════════════════════════════════════════════════════════════
    // Ledger：记账 / 不变量 / 重放
    // ══════════════════════════════════════════════════════════════

    private Ledger newLedger() {
        return new Ledger(tempDir.resolve("ledger.jsonl"));
    }

    @Test
    void postRequiresAtLeastTwoLegs() {
        Ledger ledger = newLedger();
        TestAccount a = account("player:one-leg");
        ledger.register(a);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.post("test", "Tester", "只有一条腿", Ledger.leg(a, 100L)),
                "少于两条腿应抛 IllegalArgumentException");
        assertEquals(0L, a.balanceCents(), "记账失败不应改余额");
    }

    @Test
    void postRejectsUnbalancedLegs() {
        Ledger ledger = newLedger();
        TestAccount a = account("player:unbalanced-a");
        TestAccount b = account("player:unbalanced-b");
        ledger.register(a);
        ledger.register(b);
        assertThrows(IllegalStateException.class,
                () -> ledger.post("test", "Tester", "腿和不为 0", Ledger.leg(a, 100L), Ledger.leg(b, -99L)),
                "腿和不为 0 应抛 IllegalStateException");
        assertEquals(0L, a.balanceCents(), "失败记账不能给 A 加钱");
        assertEquals(0L, b.balanceCents(), "失败记账不能给 B 扣钱");
        assertTrue(ledger.isBalanced(), "失败记账后账本仍应平衡");
        assertEquals(0L, ledger.seqValue(), "失败记账不应增加流水序号");
    }

    @Test
    void postRejectsNegativePlayerBalance() {
        Ledger ledger = newLedger();
        TestAccount player = account("player:overdraft");
        TestAccount other = account("player:counterparty");
        ledger.register(player);
        ledger.register(other);
        ledger.issue(player, 1_000L, "test.issue", "Tester", "发钱");
        assertThrows(IllegalStateException.class,
                () -> ledger.post("test", "Tester", "透支", Ledger.leg(player, -2_000L), Ledger.leg(other, 2_000L)),
                "玩家账户余额不允许为负");
        assertEquals(1_000L, player.balanceCents(), "被拒后余额不应变化");
        assertTrue(ledger.isBalanced(), "被拒后账本仍应平衡");
    }

    @Test
    void postRejectsUnregisteredAccount() {
        Ledger ledger = newLedger();
        TestAccount registered = account("player:registered");
        TestAccount ghost = account("player:ghost");
        ledger.register(registered);
        assertThrows(IllegalStateException.class,
                () -> ledger.post("test", "Tester", "未注册账户", Ledger.leg(ghost, 100L),
                        Ledger.leg(registered, -100L)),
                "未注册账户记账应抛 IllegalStateException");
    }

    @Test
    void issueBurnTransferKeepLedgerBalanced() {
        Ledger ledger = newLedger();
        TestAccount a = account("player:alice");
        TestAccount b = account("player:bob");
        ledger.register(a);
        ledger.register(b);

        ledger.issue(a, 10_000L, "test.issue", "Tester", "发行给 A");
        assertTrue(ledger.isBalanced(), "发行后账本应平衡");
        assertEquals(10_000L, a.balanceCents(), "A 应收到 100 币");
        assertEquals(-10_000L, ledger.issuedCents(), "净发行额应为 -10000");
        assertEquals(10_000L, ledger.circulationCents(), "流通量应为 10000");

        ledger.transfer(a, b, 4_000L, "test.transfer", "Tester", "A 转给 B");
        assertTrue(ledger.isBalanced(), "转账后账本应平衡");
        assertEquals(6_000L, a.balanceCents(), "A 应剩 60 币");
        assertEquals(4_000L, b.balanceCents(), "B 应收到 40 币");
        assertEquals(10_000L, ledger.circulationCents(), "转账不改变货币总量");

        ledger.burn(a, 1_000L, "test.burn", "Tester", "销毁 10 币");
        assertTrue(ledger.isBalanced(), "销毁后账本应平衡");
        assertEquals(5_000L, a.balanceCents(), "A 应剩 50 币");
        assertEquals(9_000L, ledger.circulationCents(), "销毁后流通量应为 9000");
        assertEquals(-9_000L, ledger.issuedCents(), "净发行额应为 -9000");

        Ledger.ReplayResult replay = ledger.replayAndVerify();
        assertTrue(replay.ok(), "重放核对应与账户余额一致：" + replay.detail);
        assertEquals(0, replay.badLines, "流水文件不应有坏行");
    }

    @Test
    void issueAndBurnRejectNonPositiveAmounts() {
        Ledger ledger = newLedger();
        TestAccount a = account("player:zero");
        ledger.register(a);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.issue(a, 0L, "test", "Tester", "金额 0"), "发行金额必须为正");
        assertThrows(IllegalArgumentException.class,
                () -> ledger.burn(a, -1L, "test", "Tester", "负金额"), "销毁金额必须为正");
        assertThrows(IllegalArgumentException.class,
                () -> ledger.transfer(a, a, 0L, "test", "Tester", "金额 0"), "转账金额必须为正");
    }

    @Test
    void replayAndVerifyDetectsMismatch() {
        Ledger ledger = newLedger();
        TestAccount a = account("player:replay");
        ledger.register(a);
        ledger.issue(a, 500L, "test.issue", "Tester", "发行");
        assertTrue(ledger.replayAndVerify().ok(), "正常流水重放应一致");

        // 模拟"账户余额被外部改过"：再注册一个同 id 但没有流水的账户，重放必然对不上。
        TestAccount forged = account("player:replay");
        forged.addBalanceCents(999_999L);
        ledger.register(forged);
        Ledger.ReplayResult mismatch = ledger.replayAndVerify();
        assertFalse(mismatch.ok(), "账户余额与流水不一致时，重放核对必须报出来");
        assertTrue(mismatch.mismatches >= 1, "应至少报一个对不上的账户");
    }
}
