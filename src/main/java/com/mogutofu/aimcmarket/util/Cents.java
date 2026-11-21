package com.mogutofu.aimcmarket.util;

import java.math.BigInteger;

/**
 * 金额工具：内部一律用 {@code long} 存"分"（1 币 = 100 分）。
 *
 * <p>整个模组禁止用 double 累加货币；double 只出现在比率（利率、波动率、税率）计算里，
 * 结果马上四舍五入回 long。所有加减乘都用 {@code Math.*Exact}，溢出直接抛异常而不是静默截断。</p>
 */
public final class Cents {
    /** 1 币 = 100 分。 */
    public static final long COIN = 100L;

    private Cents() {
    }

    public static long add(long a, long b) {
        return Math.addExact(a, b);
    }

    public static long sub(long a, long b) {
        return Math.subtractExact(a, b);
    }

    public static long mul(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    public static long negate(long a) {
        return Math.negateExact(a);
    }

    /** 绝对值（Long.MIN_VALUE 会抛异常，这是对的：不该出现这种金额）。 */
    public static long abs(long a) {
        return a < 0 ? Math.negateExact(a) : a;
    }

    /** {@code a * b / c}，四舍五入，溢出安全。 */
    public static long mulDiv(long a, long b, long c) {
        if (c == 0) {
            throw new ArithmeticException("mulDiv 除数为 0");
        }
        BigInteger product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
        BigInteger divisor = BigInteger.valueOf(c);
        BigInteger[] qr = product.divideAndRemainder(divisor);
        BigInteger q = qr[0];
        BigInteger r = qr[1];
        // 四舍五入：看 2*|r| 是否 >= |c|
        if (r.signum() != 0 && r.abs().shiftLeft(1).compareTo(divisor.abs()) >= 0) {
            q = q.add(BigInteger.valueOf(product.signum() * divisor.signum() >= 0 ? 1 : -1));
        }
        return q.longValueExact();
    }

    /** 金额的百分比/比率，四舍五入到分。ratio 是 0.05 这种比例。 */
    public static long percent(long amount, double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            throw new IllegalArgumentException("非法比率: " + ratio);
        }
        double raw = amount * ratio;
        if (raw > 9.0e18 || raw < -9.0e18) {
            throw new ArithmeticException("percent 溢出: " + amount + " * " + ratio);
        }
        return Math.round(raw);
    }

    /** 上下限夹取。 */
    public static long clamp(long value, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max");
        }
        return value < min ? min : (value > max ? max : value);
    }

    public static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static double clampDouble(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /** 显示用：12.34。负数带 -。 */
    public static String format(long cents) {
        boolean negative = cents < 0;
        long abs = negative ? Math.abs(cents) : cents; // 金额不会接近 Long.MIN_VALUE
        long coins = abs / COIN;
        long fen = abs % COIN;
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        sb.append(coins).append('.');
        if (fen < 10) {
            sb.append('0');
        }
        sb.append(fen);
        return sb.toString();
    }

    /** 显示用：带正负号，+12.34 / -0.50 / 0.00。 */
    public static String formatSigned(long cents) {
        return (cents > 0 ? "+" : "") + format(cents);
    }

    /** 允许输入 "3"、"3.5"、"3.55"（最多两位小数）；非法返回 -1。 */
    public static long parse(String text) {
        if (text == null) {
            return -1L;
        }
        String s = text.trim().replace(",", "");
        if (s.isEmpty() || s.length() > 20) {
            return -1L;
        }
        boolean negative = false;
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        int dot = s.indexOf('.');
        String yuanPart = dot < 0 ? s : s.substring(0, dot);
        String fenPart = dot < 0 ? "" : s.substring(dot + 1);
        if (yuanPart.isEmpty() && fenPart.isEmpty()) {
            return -1L;
        }
        if (yuanPart.length() > 15) {
            return -1L;
        }
        for (int i = 0; i < yuanPart.length(); i++) {
            if (!Character.isDigit(yuanPart.charAt(i))) {
                return -1L;
            }
        }
        if (fenPart.length() > 2) {
            return -1L;
        }
        for (int i = 0; i < fenPart.length(); i++) {
            if (!Character.isDigit(fenPart.charAt(i))) {
                return -1L;
            }
        }
        long yuan = yuanPart.isEmpty() ? 0L : Long.parseLong(yuanPart);
        long fen = 0L;
        if (!fenPart.isEmpty()) {
            fen = (fenPart.length() == 1 ? (fenPart.charAt(0) - '0') * 10L : Long.parseLong(fenPart));
        }
        long total = Math.addExact(Math.multiplyExact(yuan, COIN), fen);
        return negative ? -total : total;
    }

    /** 利率/比率显示：0.5% → "0.50%"；bp=25 → "0.25%"。 */
    public static String formatPercent(double ratio) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100.0);
    }
}
