package com.mogutofu.aimcmarket.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forge 配置（{@code config/aimcmarket-common.toml}）。
 *
 * <p>启动时把 spec 里的值读进 {@link MarketSettings}；{@code /ex admin reload}
 * 会直接重新解析这个 TOML 文件（Forge 的 COMMON 配置不会因为改文件自动重载），
 * 解析失败就保留旧值。</p>
 */
public final class MarketConfig {
    public static final ForgeConfigSpec SPEC;
    public static final String FILE_NAME = "aimcmarket-common.toml";

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.BooleanValue AI_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> AI_MODE;
    private static final ForgeConfigSpec.ConfigValue<String> AI_ENDPOINT;
    private static final ForgeConfigSpec.ConfigValue<String> AI_EXCHANGE_PATH;
    private static final ForgeConfigSpec.ConfigValue<String> AI_LEGACY_PATH;
    private static final ForgeConfigSpec.IntValue AI_TIMEOUT;
    private static final ForgeConfigSpec.IntValue AI_BUDGET;
    private static final ForgeConfigSpec.BooleanValue AI_GOV_ENABLED;
    private static final ForgeConfigSpec.IntValue AI_GOV_MINUTES;
    private static final ForgeConfigSpec.IntValue MARKET_TICK_MINUTES;
    private static final ForgeConfigSpec.IntValue TRADING_DAY_MINUTES;
    private static final ForgeConfigSpec.DoubleValue ASK_SPREAD;
    private static final ForgeConfigSpec.DoubleValue BID_RATIO;
    private static final ForgeConfigSpec.LongValue MAX_ORDER_VALUE;
    private static final ForgeConfigSpec.LongValue STARTING_BALANCE;
    private static final ForgeConfigSpec.IntValue SALARY_MINUTES;
    private static final ForgeConfigSpec.LongValue SALARY_AMOUNT;
    private static final ForgeConfigSpec.DoubleValue POLICY_RATE;
    private static final ForgeConfigSpec.DoubleValue FUND_SUBSCRIBE_FEE;
    private static final ForgeConfigSpec.DoubleValue FUTURES_MARGIN;
    private static final ForgeConfigSpec.DoubleValue FUTURES_MAINTENANCE;
    private static final ForgeConfigSpec.DoubleValue OPTIONS_MARGIN;
    private static final ForgeConfigSpec.LongValue COMPANY_CREATE_FEE;
    private static final ForgeConfigSpec.BooleanValue ADMIN_FREEZE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("AIMCmarket 服务端模组配置。改完跑 /ex admin reload 热加载（tick 类参数下个 tick 生效）。")
                .push("general");
        ENABLED = b.comment("模组总开关（关掉后 /ex 只剩只读命令）").define("enabled", true);
        b.pop();

        b.comment("AI 接入：优先 /mc-exchange（插件 0.7.0 且开关打开），其次 /mc-ai，最后本地解析/本地规则。").push("ai");
        AI_ENABLED = b.comment("是否允许调用 AI").define("enabled", true);
        AI_MODE = b.comment("auto=优先 exchange 再 legacy；exchange=只用 exchange；legacy=只用 /mc-ai；local=只用本地")
                .define("mode", "auto");
        AI_ENDPOINT = b.comment("DSH 插件地址（dsh-mc-server-ops）").define("endpoint", "http://127.0.0.1:8765");
        AI_EXCHANGE_PATH = b.comment("结构化接口路径").define("exchangePath", "/mc-exchange");
        AI_LEGACY_PATH = b.comment("旧的自然语言接口路径").define("legacyPath", "/mc-ai");
        AI_TIMEOUT = b.comment("单次 AI 调用超时（秒）").defineInRange("timeoutSeconds", 5, 1, 120);
        AI_BUDGET = b.comment("AI 调用预算（次/小时）").defineInRange("hourlyBudget", 60, 0, 100000);
        AI_GOV_ENABLED = b.comment("是否让 AI 扮演政府/央行").define("governmentEnabled", true);
        AI_GOV_MINUTES = b.comment("两次 AI 政府决策之间的最小间隔（现实分钟）").defineInRange("governmentCheckMinutes", 5, 1, 1440);
        b.pop();

        b.comment("市场时钟与价格参数").push("market");
        MARKET_TICK_MINUTES = b.comment("市场 tick 间隔（现实分钟）").defineInRange("tickMinutes", 5, 1, 1440);
        TRADING_DAY_MINUTES = b.comment("一个交易日 = 多少现实分钟（财报/分红/到期用它）").defineInRange("tradingDayMinutes", 60, 1, 10080);
        ASK_SPREAD = b.comment("买价 = 现价 × (1 + askSpread)").defineInRange("askSpread", 0.02, 0.0, 5.0);
        BID_RATIO = b.comment("回收价 = 现价 × bidRatio").defineInRange("bidRatio", 0.60, 0.01, 1.0);
        MAX_ORDER_VALUE = b.comment("单笔订单上限（分，5000 币 = 500000）").defineInRange("maxOrderValue", 500_000L, 100L, 100_000_000L);
        b.pop();

        b.comment("经济与收入（现实时间口径）").push("economy");
        STARTING_BALANCE = b.comment("新玩家初始资金（分，500 币 = 50000）").defineInRange("startingBalance", 50_000L, 0L, 100_000_000L);
        SALARY_MINUTES = b.comment("在线工资间隔（现实分钟）").defineInRange("salaryMinutes", 30, 1, 1440);
        SALARY_AMOUNT = b.comment("每次在线工资（分）").defineInRange("salaryAmount", 5_000L, 0L, 10_000_000L);
        POLICY_RATE = b.comment("初始政策利率（0.03 = 3%）").defineInRange("policyRate", 0.03, 0.0, 0.10);
        b.pop();

        b.comment("金融参数").push("finance");
        FUND_SUBSCRIBE_FEE = b.comment("基金申购/赎回费率").defineInRange("fundSubscribeFee", 0.005, 0.0, 0.1);
        FUTURES_MARGIN = b.comment("期货初始保证金比例").defineInRange("futuresMarginRatio", 0.10, 0.01, 1.0);
        FUTURES_MAINTENANCE = b.comment("期货维持保证金比例").defineInRange("futuresMaintenanceRatio", 0.05, 0.001, 1.0);
        OPTIONS_MARGIN = b.comment("期权卖方保证金比例").defineInRange("optionsMarginRatio", 0.20, 0.01, 1.0);
        b.pop();

        b.comment("公司与 IPO").push("company");
        COMPANY_CREATE_FEE = b.comment("创建公司费用（分，5000 币 = 500000）").defineInRange("createFeeCents", 500_000L, 0L, 100_000_000L);
        b.pop();

        b.comment("管理").push("admin");
        ADMIN_FREEZE = b.comment("冻结交易（OP 应急用）").define("freeze", false);
        b.pop();

        SPEC = b.build();
    }

    private MarketConfig() {
    }

    /** 在模组构造里调用。 */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, FILE_NAME);
    }

    /** 从 Forge spec 读一份设置。 */
    public static MarketSettings read() {
        MarketSettings s = new MarketSettings();
        try {
            s.enabled = ENABLED.get();
            s.aiEnabled = AI_ENABLED.get();
            s.aiMode = AI_MODE.get();
            s.aiEndpoint = AI_ENDPOINT.get();
            s.aiExchangePath = AI_EXCHANGE_PATH.get();
            s.aiLegacyPath = AI_LEGACY_PATH.get();
            s.aiTimeoutSeconds = AI_TIMEOUT.get();
            s.aiHourlyBudget = AI_BUDGET.get();
            s.aiGovernmentEnabled = AI_GOV_ENABLED.get();
            s.aiGovernmentCheckMinutes = AI_GOV_MINUTES.get();
            s.marketTickMinutes = MARKET_TICK_MINUTES.get();
            s.tradingDayMinutes = TRADING_DAY_MINUTES.get();
            s.askSpread = ASK_SPREAD.get();
            s.bidRatio = BID_RATIO.get();
            s.maxOrderValueCents = MAX_ORDER_VALUE.get();
            s.startingBalanceCents = STARTING_BALANCE.get();
            s.salaryMinutes = SALARY_MINUTES.get();
            s.salaryAmountCents = SALARY_AMOUNT.get();
            s.policyRate = POLICY_RATE.get();
            s.fundSubscribeFee = FUND_SUBSCRIBE_FEE.get();
            s.futuresMarginRatio = FUTURES_MARGIN.get();
            s.futuresMaintenanceRatio = FUTURES_MAINTENANCE.get();
            s.optionsMarginRatio = OPTIONS_MARGIN.get();
            s.companyCreateFeeCents = COMPANY_CREATE_FEE.get();
            s.adminFreeze = ADMIN_FREEZE.get();
        } catch (RuntimeException error) {
            // 配置没加载时用默认值
        }
        s.sanitize();
        return s;
    }

    /** 热加载：直接重读 TOML 文件，只覆盖我们认识的键。 */
    public static MarketSettings reload(MarketSettings current) {
        Path file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return current;
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            String section = "";
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                int comment = value.indexOf('#');
                if (comment >= 0 && !value.startsWith("\"")) {
                    value = value.substring(0, comment).trim();
                }
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(section.isEmpty() ? key : section + "." + key, value);
            }
        } catch (Exception error) {
            return current;
        }
        MarketSettings s = current;
        try {
            s.enabled = bool(values, "general.enabled", s.enabled);
            s.aiEnabled = bool(values, "ai.enabled", s.aiEnabled);
            s.aiMode = text(values, "ai.mode", s.aiMode);
            s.aiEndpoint = text(values, "ai.endpoint", s.aiEndpoint);
            s.aiExchangePath = text(values, "ai.exchangePath", s.aiExchangePath);
            s.aiLegacyPath = text(values, "ai.legacyPath", s.aiLegacyPath);
            s.aiTimeoutSeconds = integer(values, "ai.timeoutSeconds", s.aiTimeoutSeconds);
            s.aiHourlyBudget = integer(values, "ai.hourlyBudget", s.aiHourlyBudget);
            s.aiGovernmentEnabled = bool(values, "ai.governmentEnabled", s.aiGovernmentEnabled);
            s.aiGovernmentCheckMinutes = integer(values, "ai.governmentCheckMinutes", s.aiGovernmentCheckMinutes);
            s.marketTickMinutes = integer(values, "market.tickMinutes", s.marketTickMinutes);
            s.tradingDayMinutes = integer(values, "market.tradingDayMinutes", s.tradingDayMinutes);
            s.askSpread = decimal(values, "market.askSpread", s.askSpread);
            s.bidRatio = decimal(values, "market.bidRatio", s.bidRatio);
            s.maxOrderValueCents = number(values, "market.maxOrderValue", s.maxOrderValueCents);
            s.startingBalanceCents = number(values, "economy.startingBalance", s.startingBalanceCents);
            s.salaryMinutes = integer(values, "economy.salaryMinutes", s.salaryMinutes);
            s.salaryAmountCents = number(values, "economy.salaryAmount", s.salaryAmountCents);
            s.policyRate = decimal(values, "economy.policyRate", s.policyRate);
            s.fundSubscribeFee = decimal(values, "finance.fundSubscribeFee", s.fundSubscribeFee);
            s.futuresMarginRatio = decimal(values, "finance.futuresMarginRatio", s.futuresMarginRatio);
            s.futuresMaintenanceRatio = decimal(values, "finance.futuresMaintenanceRatio", s.futuresMaintenanceRatio);
            s.optionsMarginRatio = decimal(values, "finance.optionsMarginRatio", s.optionsMarginRatio);
            s.companyCreateFeeCents = number(values, "company.createFeeCents", s.companyCreateFeeCents);
            s.adminFreeze = bool(values, "admin.freeze", s.adminFreeze);
            s.sanitize();
        } catch (RuntimeException error) {
            // 有一项解析失败就整体回滚：直接把 spec 的当前值读回来
            MarketSettings fresh = read();
            copy(fresh, s);
        }
        return s;
    }

    private static void copy(MarketSettings from, MarketSettings to) {
        to.enabled = from.enabled;
        to.aiEnabled = from.aiEnabled;
        to.aiMode = from.aiMode;
        to.aiEndpoint = from.aiEndpoint;
        to.aiTimeoutSeconds = from.aiTimeoutSeconds;
        to.aiHourlyBudget = from.aiHourlyBudget;
        to.marketTickMinutes = from.marketTickMinutes;
        to.tradingDayMinutes = from.tradingDayMinutes;
        to.askSpread = from.askSpread;
        to.bidRatio = from.bidRatio;
        to.maxOrderValueCents = from.maxOrderValueCents;
        to.startingBalanceCents = from.startingBalanceCents;
        to.salaryMinutes = from.salaryMinutes;
        to.salaryAmountCents = from.salaryAmountCents;
        to.policyRate = from.policyRate;
        to.fundSubscribeFee = from.fundSubscribeFee;
        to.futuresMarginRatio = from.futuresMarginRatio;
        to.futuresMaintenanceRatio = from.futuresMaintenanceRatio;
        to.optionsMarginRatio = from.optionsMarginRatio;
        to.companyCreateFeeCents = from.companyCreateFeeCents;
        to.adminFreeze = from.adminFreeze;
    }

    private static boolean bool(Map<String, String> values, String key, boolean def) {
        String v = values.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static String text(Map<String, String> values, String key, String def) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int integer(Map<String, String> values, String key, int def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException error) {
            return def;
        }
    }

    private static long number(Map<String, String> values, String key, long def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException error) {
            return def;
        }
    }

    private static double decimal(Map<String, String> values, String key, double def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException error) {
            return def;
        }
    }

    /** 配置文件名（显示用）。 */
    public static String fileName() {
        return FILE_NAME;
    }
}
