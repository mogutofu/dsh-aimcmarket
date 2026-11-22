package com.mogutofu.aimcmarket.config;

import com.mogutofu.aimcmarket.util.Cents;

/**
 * 运行期配置快照（纯 Java，不依赖 Forge，单测可以直接 new）。
 *
 * <p>Forge 的 {@code ForgeConfigSpec} 负责写 config/aimcmarket-common.toml，
 * 启动和 {@code /ex admin reload} 时把值读进这个对象，引擎只认这里。</p>
 */
public final class MarketSettings {
    // ── 总开关 ──
    public boolean enabled = true;

    // ── AI ──
    public boolean aiEnabled = true;
    /** auto | exchange | legacy | local */
    public String aiMode = "auto";
    public String aiEndpoint = "http://127.0.0.1:8765";
    public String aiExchangePath = "/mc-exchange";
    public String aiLegacyPath = "/mc-ai";
    public int aiTimeoutSeconds = 5;
    public int aiHourlyBudget = 60;
    public boolean aiGovernmentEnabled = true;
    public int aiGovernmentCheckMinutes = 5;
    /** 玩家 AI 解析最短间隔（毫秒），默认 2 秒。 */
    public long aiPlayerMinIntervalMs = 2000L;

    // ── 市场 ──
    public int marketTickMinutes = 5;
    public int tradingDayMinutes = 60;
    public double askSpread = 0.50;
    public double bidRatio = 0.12;
    public long maxOrderValueCents = 500_000L; // 5000 币
    public int maxItemPerOrder = 576;
    public double priceTheta = 0.05;
    public double maxImpactPerTick = 0.20;
    public double priceFloorRatio = 0.25;
    public double priceCeilingRatio = 4.0;

    // ── 收入（现实时间）──
    public long startingBalanceCents = 50_000L; // 500 币
    public int salaryMinutes = 30;
    public long salaryAmountCents = 5_000L; // 50 币
    public int salaryMaxPerDay = 8;
    public int workCooldownMinutes = 10;
    public long workMinCents = 3_000L; // 30 币
    public long workMaxCents = 8_000L; // 80 币
    public double workDoubleChance = 0.05;
    public int workMaxPerDay = 20;
    public long dailyBaseCents = 1_000L;
    public long dailyStepCents = 1_000L;
    public long dailyMaxCents = 3_000L; // 30 币封顶
    public double sellRatio = 0.60;

    // ── 银行 ──
    public double policyRate = 0.03;
    public double currentRateSpread = -0.015;
    public double currentRateFloor = 0.001;
    public double term7Spread = 0.015;
    public double term30Spread = 0.030;
    public double loanRateSpread = 0.08;
    public double creditInitial = 600.0;
    public double creditMin = 300.0;
    public double creditMax = 850.0;

    // ── 基金 ──
    public double fundSubscribeFee = 0.005;
    public double fundRedeemEarlyFee = 0.02;

    // ── 期货 / 期权 ──
    public double futuresMarginRatio = 0.10;
    public double futuresMaintenanceRatio = 0.05;
    public double futuresCarry = 0.05;
    public double futuresFeeRatio = 0.0005;
    public double optionsMarginRatio = 0.20;
    public long optionsMinMarginCents = 0L;
    public double optionsMarginMaintenance = 0.10;
    public double optionsFeeRatio = 0.002;

    // ── 公司 / IPO ──
    public long companyCreateFeeCents = 500_000L; // 5000 币
    public long companySectorSwitchFeeCents = 200_000L; // 2000 币
    public long companyHireFeeCents = 20_000L; // 200 币
    public long companyDailyWageCents = 2_000L; // 20 币
    public long companySharesTotal = 10_000L;
    public double companyTradeTax = 0.10;

    // ── 管理 ──
    public boolean adminFreeze = false;

    public long policyRateBp() {
        return Math.round(policyRate * 10_000.0);
    }

    /** 交易税：政府政策可调，放在政府状态里；这里只是默认值兜底。 */
    public double defaultTradeTax = 0.005;

    /** 从配置里读进来的值做一次边界修正，防止手改成负的/0 把服务器搞炸。 */
    public void sanitize() {
        aiTimeoutSeconds = Cents.clampInt(aiTimeoutSeconds, 1, 120);
        aiHourlyBudget = Cents.clampInt(aiHourlyBudget, 0, 100_000);
        aiGovernmentCheckMinutes = Cents.clampInt(aiGovernmentCheckMinutes, 1, 1440);
        aiPlayerMinIntervalMs = Math.max(0L, aiPlayerMinIntervalMs);
        marketTickMinutes = Cents.clampInt(marketTickMinutes, 1, 1440);
        tradingDayMinutes = Cents.clampInt(tradingDayMinutes, 1, 10_080);
        askSpread = Cents.clampDouble(askSpread, 0.0, 5.0);
        bidRatio = Cents.clampDouble(bidRatio, 0.01, 1.0);
        maxOrderValueCents = Math.max(100L, maxOrderValueCents);
        maxItemPerOrder = Cents.clampInt(maxItemPerOrder, 1, 2304);
        priceTheta = Cents.clampDouble(priceTheta, 0.0, 1.0);
        maxImpactPerTick = Cents.clampDouble(maxImpactPerTick, 0.0, 1.0);
        priceFloorRatio = Cents.clampDouble(priceFloorRatio, 0.01, 1.0);
        priceCeilingRatio = Cents.clampDouble(priceCeilingRatio, 1.0, 100.0);
        startingBalanceCents = Math.max(0L, startingBalanceCents);
        salaryMinutes = Cents.clampInt(salaryMinutes, 1, 1440);
        salaryAmountCents = Math.max(0L, salaryAmountCents);
        salaryMaxPerDay = Cents.clampInt(salaryMaxPerDay, 0, 1000);
        workCooldownMinutes = Cents.clampInt(workCooldownMinutes, 0, 1440);
        workMinCents = Math.max(0L, workMinCents);
        workMaxCents = Math.max(workMinCents, workMaxCents);
        workMaxPerDay = Cents.clampInt(workMaxPerDay, 0, 1000);
        dailyBaseCents = Math.max(0L, dailyBaseCents);
        dailyStepCents = Math.max(0L, dailyStepCents);
        dailyMaxCents = Math.max(dailyBaseCents, dailyMaxCents);
        sellRatio = Cents.clampDouble(sellRatio, 0.0, 1.0);
        policyRate = Cents.clampDouble(policyRate, 0.0, 0.10);
        fundSubscribeFee = Cents.clampDouble(fundSubscribeFee, 0.0, 0.1);
        fundRedeemEarlyFee = Cents.clampDouble(fundRedeemEarlyFee, 0.0, 0.5);
        futuresMarginRatio = Cents.clampDouble(futuresMarginRatio, 0.01, 1.0);
        futuresMaintenanceRatio = Cents.clampDouble(futuresMaintenanceRatio, 0.001, futuresMarginRatio);
        futuresCarry = Cents.clampDouble(futuresCarry, 0.0, 1.0);
        futuresFeeRatio = Cents.clampDouble(futuresFeeRatio, 0.0, 0.1);
        optionsMarginRatio = Cents.clampDouble(optionsMarginRatio, 0.01, 1.0);
        optionsMarginMaintenance = Cents.clampDouble(optionsMarginMaintenance, 0.001, optionsMarginRatio);
        optionsFeeRatio = Cents.clampDouble(optionsFeeRatio, 0.0, 0.1);
        companyCreateFeeCents = Math.max(0L, companyCreateFeeCents);
        companySharesTotal = Math.max(100L, companySharesTotal);
        companyTradeTax = Cents.clampDouble(companyTradeTax, 0.0, 0.5);
        defaultTradeTax = Cents.clampDouble(defaultTradeTax, 0.0, 0.2);
    }

    public static MarketSettings defaults() {
        MarketSettings s = new MarketSettings();
        s.sanitize();
        return s;
    }
}
