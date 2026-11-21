package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 基金：3 只模拟组合的基金。
 *
 * <p>每只基金有自己的余额账户 {@code fund:FND01}，钱是实打实的：申购把钱转进基金账户，
 * 赎回再从账户转出来。净值按"基金现金 / 总份额"算，所以
 * <b>净值 × 总份额 = 基金现金</b> 恒成立（误差只来自四舍五入的 1 分以内）。</p>
 *
 * <p>收益：每个 market tick 按"债券收益 + 股票指数收益 + 日波动噪声"给基金账户记一笔
 * 增发（盈利）或销毁（亏损），净值随之变化。</p>
 */
public final class FundStore {
    /** 一只基金。 */
    public static final class Fund {
        public String code;
        public String name;
        public String description;
        public double bondWeight;
        public double stockWeight;
        public double targetAnnual;
        public double dailyVol;
        /** 1 份 = 1_000_000 微币；navMicroCents = 1.0000 币。 */
        public long navMicroCents = 1_000_000L;
        public long cashCents;

        public BalanceAccount account;
    }

    /** 待成交的申赎单：下一个 market tick 按当期净值成交。 */
    public static final class PendingOrder {
        public String id;
        public String uuid;
        public String playerName;
        /** buy | sell */
        public String type;
        public String fundCode;
        public long amountCents;
        public long unitsMilli;
        /** 创建时的 tick，用于判断持有是否满 1 交易日。 */
        public long createdTick;
        /** 应该在哪个 tick 成交。 */
        public long executeTick;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("uuid", uuid);
            json.addProperty("player", playerName == null ? "" : playerName);
            json.addProperty("type", type);
            json.addProperty("fund", fundCode);
            json.addProperty("amountCents", amountCents);
            json.addProperty("unitsMilli", unitsMilli);
            json.addProperty("createdTick", createdTick);
            json.addProperty("executeTick", executeTick);
            return json;
        }

        public static PendingOrder fromJson(JsonObject json) {
            PendingOrder order = new PendingOrder();
            order.id = JsonFile.str(json, "id", "");
            order.uuid = JsonFile.str(json, "uuid", "");
            order.playerName = JsonFile.str(json, "player", "");
            order.type = JsonFile.str(json, "type", "buy");
            order.fundCode = JsonFile.str(json, "fund", "").toUpperCase(Locale.ROOT);
            order.amountCents = JsonFile.lng(json, "amountCents", 0L);
            order.unitsMilli = JsonFile.lng(json, "unitsMilli", 0L);
            order.createdTick = JsonFile.lng(json, "createdTick", 0L);
            order.executeTick = JsonFile.lng(json, "executeTick", 0L);
            return order;
        }
    }

    public static final String[][] FUND_SPECS = {
            // 代码, 名称, 说明, 债券权重, 股票权重, 目标年化, 日波动
            {"FND01", "稳健基金", "六成债券、四成股票指数，波动小", "0.60", "0.40", "0.04", "0.010"},
            {"FND02", "平衡基金", "股债各一半，风险和收益都居中", "0.50", "0.50", "0.06", "0.018"},
            {"FND03", "激进基金", "两成债券、八成股票指数，波动大", "0.20", "0.80", "0.09", "0.030"},
    };

    private final Path file;
    private final Map<String, Fund> funds = new LinkedHashMap<>();
    private final List<PendingOrder> pending = new ArrayList<>();
    private long lastIndexCentiPoints = 100_000L;

    public FundStore(Path file) {
        this.file = file;
    }

    public void load() {
        funds.clear();
        pending.clear();
        for (String[] spec : FUND_SPECS) {
            Fund fund = new Fund();
            fund.code = spec[0];
            fund.name = spec[1];
            fund.description = spec[2];
            fund.bondWeight = Double.parseDouble(spec[3]);
            fund.stockWeight = Double.parseDouble(spec[4]);
            fund.targetAnnual = Double.parseDouble(spec[5]);
            fund.dailyVol = Double.parseDouble(spec[6]);
            fund.account = new FundAccount(fund);
            funds.put(fund.code, fund);
        }
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            for (JsonElement element : JsonFile.array(root, "funds")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject json = element.getAsJsonObject();
                Fund fund = funds.get(JsonFile.str(json, "code", "").toUpperCase(Locale.ROOT));
                if (fund == null) {
                    continue;
                }
                fund.navMicroCents = JsonFile.lng(json, "navMicroCents", 1_000_000L);
                fund.cashCents = JsonFile.lng(json, "cashCents", 0L);
            }
            for (JsonElement element : JsonFile.array(root, "pending")) {
                if (element.isJsonObject()) {
                    PendingOrder order = PendingOrder.fromJson(element.getAsJsonObject());
                    if (!order.id.isEmpty() && funds.containsKey(order.fundCode)) {
                        pending.add(order);
                    }
                }
            }
            lastIndexCentiPoints = JsonFile.lng(root, "lastIndexCentiPoints", 100_000L);
        }
        Log.info("基金加载完成：" + funds.size() + " 只，待成交 " + pending.size() + " 单");
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonArray fundArray = new JsonArray();
        for (Fund fund : funds.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("code", fund.code);
            json.addProperty("navMicroCents", fund.navMicroCents);
            json.addProperty("cashCents", fund.cashCents);
            fundArray.add(json);
        }
        root.add("funds", fundArray);
        JsonArray pendingArray = new JsonArray();
        for (PendingOrder order : pending) {
            pendingArray.add(order.toJson());
        }
        root.add("pending", pendingArray);
        root.addProperty("lastIndexCentiPoints", lastIndexCentiPoints);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 funds.json 失败（下个 tick 重试）", error);
        }
    }

    // ── 查询 ──

    public Fund fund(String code) {
        return code == null ? null : funds.get(code.trim().toUpperCase(Locale.ROOT));
    }

    public Collection<Fund> all() {
        return funds.values();
    }

    public BalanceAccount account(String code) {
        Fund fund = fund(code);
        return fund == null ? null : fund.account;
    }

    public long navMicroCents(String code) {
        Fund fund = fund(code);
        return fund == null ? 0L : fund.navMicroCents;
    }

    /** 净值显示：1.0000。 */
    public String formatNav(String code) {
        long nav = navMicroCents(code);
        long whole = nav / 1_000_000L;
        long fraction = nav % 1_000_000L;
        return whole + "." + String.format(Locale.ROOT, "%04d", fraction / 100L);
    }

    /** 份额 × 1000 的市值（分）。 */
    public long valueOfUnits(String code, long unitsMilli) {
        Fund fund = fund(code);
        if (fund == null || unitsMilli <= 0L) {
            return 0L;
        }
        return Cents.mulDiv(unitsMilli, fund.navMicroCents, 1_000_000_000L);
    }

    /** 一笔金额按当前净值能买多少份额（unitsMilli）。 */
    public long unitsForAmount(String code, long amountCents) {
        Fund fund = fund(code);
        if (fund == null || amountCents <= 0L || fund.navMicroCents <= 0L) {
            return 0L;
        }
        return Cents.mulDiv(amountCents, 1_000_000_000L, fund.navMicroCents);
    }

    /** 从账户里把所有基金的份额汇总（算净值用）。 */
    public long totalUnitsMilli(String code, Accounts accounts) {
        long sum = 0L;
        for (Account account : accounts.all()) {
            sum = Math.addExact(sum, account.fundUnitsMilli(code));
        }
        return sum;
    }

    // ── 申赎 ──

    public void enqueueBuy(String orderId, Account account, String code, long amountCents, long tick) {
        Fund fund = fund(code);
        if (fund == null) {
            throw new IllegalArgumentException("没有这只基金：" + code);
        }
        PendingOrder order = new PendingOrder();
        order.id = orderId;
        order.uuid = account.uuid;
        order.playerName = account.name;
        order.type = "buy";
        order.fundCode = fund.code;
        order.amountCents = amountCents;
        order.createdTick = tick;
        order.executeTick = tick + 1L;
        pending.add(order);
    }

    public void enqueueSell(String orderId, Account account, String code, long unitsMilli, long tick) {
        Fund fund = fund(code);
        if (fund == null) {
            throw new IllegalArgumentException("没有这只基金：" + code);
        }
        PendingOrder order = new PendingOrder();
        order.id = orderId;
        order.uuid = account.uuid;
        order.playerName = account.name;
        order.type = "sell";
        order.fundCode = fund.code;
        order.unitsMilli = unitsMilli;
        order.createdTick = tick;
        order.executeTick = tick + 1L;
        pending.add(order);
    }

    public List<PendingOrder> pending() {
        return pending;
    }

    // ── 每个 market tick ──

    /**
     * 处理一个 market tick：先按当前净值成交上一 tick 排队的申赎，再按本 tick 收益更新净值。
     *
     * @param indexReturn tick 内指数收益率（例如 0.002 = 涨 0.2%）
     */
    public void onTick(long tick, long tradingDay, long ticksPerDay, double indexReturn,
                       Accounts accounts, Ledger ledger, MarketSettings settings, Random random) {
        executePending(tick, ticksPerDay, accounts, ledger, settings);
        accrue(tick, ticksPerDay, indexReturn, accounts, ledger, random);
        // 净值 = 基金现金 / 总份额；没有份额时保持上一净值（不跳变）
        for (Fund fund : funds.values()) {
            long units = totalUnitsMilli(fund.code, accounts);
            if (units > 0L) {
                long nav = Cents.mulDiv(fund.cashCents, 1_000_000_000L, units);
                fund.navMicroCents = Math.max(1L, nav);
            }
        }
        lastIndexCentiPoints = Math.round(lastIndexCentiPoints * (1.0 + indexReturn));
        if (lastIndexCentiPoints <= 0L) {
            lastIndexCentiPoints = 100_000L;
        }
    }

    private void executePending(long tick, long ticksPerDay, Accounts accounts, Ledger ledger,
                                MarketSettings settings) {
        List<PendingOrder> done = new ArrayList<>();
        for (PendingOrder order : pending) {
            if (order.executeTick > tick) {
                continue;
            }
            Fund fund = fund(order.fundCode);
            Account account = accounts.get(order.uuid);
            if (fund == null || account == null) {
                done.add(order);
                continue;
            }
            try {
                if ("buy".equals(order.type)) {
                    long fee = Cents.percent(order.amountCents, settings.fundSubscribeFee);
                    long net = order.amountCents - fee;
                    long units = unitsForAmount(fund.code, net);
                    if (units <= 0L) {
                        throw new IllegalStateException("金额太少，买不到份额");
                    }
                    if (fee > 0L) {
                        ledger.transfer(account, ledger.find(Ledger.FEE_POOL), fee, "fund.subscribeFee",
                                account.name, "申购费 " + fund.name);
                    }
                    ledger.transfer(account, fund.account, net, "fund.subscribe", account.name,
                            "申购 " + fund.name + " " + Cents.format(order.amountCents) + " 币");
                    account.addFundUnits(fund.code, units, tick);
                } else {
                    long units = Math.min(order.unitsMilli, account.fundUnitsMilli(fund.code));
                    if (units <= 0L) {
                        done.add(order);
                        continue;
                    }
                    long gross = valueOfUnits(fund.code, units);
                    long fee = Cents.percent(gross, settings.fundSubscribeFee);
                    // 持有不足 1 交易日：赎回费
                    Account.FundHolding holding = account.funds.get(fund.code);
                    long sinceTick = holding == null ? tick : holding.sinceTick;
                    if (tick - sinceTick < ticksPerDay) {
                        fee = Math.addExact(fee, Cents.percent(gross, settings.fundRedeemEarlyFee));
                    }
                    long net = gross - fee;
                    if (fee > 0L) {
                        ledger.transfer(fund.account, ledger.find(Ledger.FEE_POOL), fee, "fund.redeemFee",
                                account.name, "赎回费 " + fund.name);
                    }
                    if (net > 0L) {
                        ledger.transfer(fund.account, account, net, "fund.redeem", account.name,
                                "赎回 " + fund.name + " " + Cents.format(net) + " 币");
                    }
                    account.addFundUnits(fund.code, -units, tick);
                }
                done.add(order);
            } catch (RuntimeException error) {
                Log.warn("基金申赎失败（" + order.playerName + " " + order.fundCode + "）", error);
                done.add(order);
            }
        }
        pending.removeAll(done);
    }

    private void accrue(long tick, long ticksPerDay, double indexReturn, Accounts accounts, Ledger ledger, Random random) {
        for (Fund fund : funds.values()) {
            if (fund.cashCents <= 0L) {
                continue;
            }
            double bondPart = fund.bondWeight * 0.04 / 365.0 / Math.max(1, ticksPerDay);
            double stockPart = fund.stockWeight * indexReturn;
            double noise = 0.0;
            if (fund.dailyVol > 0.0 && ticksPerDay > 0) {
                noise = (fund.dailyVol / Math.sqrt(ticksPerDay)) * random.nextGaussian() * 0.25;
            }
            double tickReturn = bondPart + stockPart + noise;
            long delta = Cents.percent(fund.cashCents, tickReturn);
            if (delta > 0L) {
                ledger.issue(fund.account, delta, "fund.gain", "系统", fund.name + " 组合收益");
            } else if (delta < 0L) {
                delta = Math.max(delta, -fund.cashCents);
                if (delta < 0L) {
                    ledger.burn(fund.account, -delta, "fund.loss", "系统", fund.name + " 组合亏损");
                }
            }
        }
    }

    private static final class FundAccount implements BalanceAccount {
        private final Fund fund;

        FundAccount(Fund fund) {
            this.fund = fund;
        }

        @Override
        public String accountId() {
            return "fund:" + fund.code;
        }

        @Override
        public long balanceCents() {
            return fund.cashCents;
        }

        @Override
        public void addBalanceCents(long delta) {
            fund.cashCents = Math.addExact(fund.cashCents, delta);
        }
    }

    public Path file() {
        return file;
    }
}
