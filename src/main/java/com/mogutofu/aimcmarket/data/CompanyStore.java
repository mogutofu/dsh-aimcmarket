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
 * 玩家公司：创建、员工、日结、等级、估值、分红、破产，以及 IPO 需要的股份结构。
 *
 * <p>NPC 上市公司的资料在 {@link StockStore} 里，这里只存玩家公司。</p>
 *
 * <p>所有金额单位是分。<b>交易日的"日"指交易日</b>（60 现实分钟 = 1 交易日），不是现实天。</p>
 */
public final class CompanyStore {
    /** 五个行业。key 是内部代码，value 是中文名。 */
    public static final Map<String, String> SECTORS = new LinkedHashMap<>();
    /** 每个行业的基础日营收（分）：采矿 60 币、农业 40 币、制造 80 币、贸易 50 币、服务 45 币。 */
    public static final Map<String, Long> SECTOR_BASE_REVENUE = new LinkedHashMap<>();

    static {
        SECTORS.put("mining", "采矿");
        SECTORS.put("farming", "农业");
        SECTORS.put("manufacturing", "制造");
        SECTORS.put("trading", "贸易");
        SECTORS.put("service", "服务");
        SECTOR_BASE_REVENUE.put("mining", 50_000L);      // 500 币/交易日
        SECTOR_BASE_REVENUE.put("farming", 35_000L);     // 350 币
        SECTOR_BASE_REVENUE.put("manufacturing", 65_000L); // 650 币
        SECTOR_BASE_REVENUE.put("trading", 42_000L);     // 420 币
        SECTOR_BASE_REVENUE.put("service", 38_000L);     // 380 币
    }

    /** 公司等级 1-5 的升级门槛：累计营收（分）与升级费（分）。 */
    public static final long[] LEVEL_REVENUE_REQUIRED = {0L, 1_000_000L, 3_000_000L, 8_000_000L, 20_000_000L};
    public static final long[] LEVEL_UP_FEE = {0L, 200_000L, 500_000L, 1_200_000L, 3_000_000L};

    /** 一家玩家公司。 */
    public static final class Company implements BalanceAccount {
        public String id;
        public String name;
        public String ceoUuid;
        /** 内部行业代码：mining/farming/manufacturing/trading/service */
        public String sector;
        public int level = 1;
        public long cashCents;
        public long valuationCents = 500_000L;
        public int employees;
        public double reputation = 1.0;
        public long createdDay;
        public long lastSettleDay = -1L;
        public long lastDividendDay = -1L;
        /** 分红比例 0-0.8。 */
        public double dividendRatio;
        public long sharesTotal = 10_000L;
        /** uuid → 股数；"{@code public}" 是 IPO 流通股。 */
        public final Map<String, Long> holders = new LinkedHashMap<>();
        public boolean listed;
        public String code;
        public long lockUntilDay = -1L;
        public long cumulativeRevenueCents;
        public final List<Long> revenueHistory = new ArrayList<>();
        public final List<Long> profitHistory = new ArrayList<>();
        public boolean bankrupt;
        public long sectorChangeDay = -1L;
        public long lastRaiseDay = -1L;
        /** IPO 申请状态：投行受理后 1 个交易日才能确认上市。 */
        public boolean ipoPending;
        public long ipoApplyDay = -1L;
        public double ipoFloatRatio;
        /** 招股书 JSON（AI 生成或本地模板），文本里给玩家看。 */
        public String ipoProspectus = "";

        @Override
        public String accountId() {
            return "company:" + id;
        }

        @Override
        public long balanceCents() {
            return cashCents;
        }

        @Override
        public void addBalanceCents(long delta) {
            cashCents = Math.addExact(cashCents, delta);
        }

        public String sectorName() {
            return SECTORS.getOrDefault(sector, sector);
        }

        public long sharesOf(String uuid) {
            return holders.getOrDefault(uuid, 0L);
        }

        public long publicShares() {
            return holders.getOrDefault("public", 0L);
        }

        public int maxEmployees() {
            return 5 + level * 3;
        }

        public long averageDailyProfit(int days) {
            if (profitHistory.isEmpty()) {
                return 0L;
            }
            int available = Math.min(days, profitHistory.size());
            long sum = 0L;
            for (int i = profitHistory.size() - available; i < profitHistory.size(); i++) {
                sum = Math.addExact(sum, profitHistory.get(i));
            }
            return sum / available;
        }

        public long averageDailyRevenue(int days) {
            if (revenueHistory.isEmpty()) {
                return 0L;
            }
            int available = Math.min(days, revenueHistory.size());
            long sum = 0L;
            for (int i = revenueHistory.size() - available; i < revenueHistory.size(); i++) {
                sum = Math.addExact(sum, revenueHistory.get(i));
            }
            return sum / available;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("name", name);
            json.addProperty("ceo", ceoUuid);
            json.addProperty("sector", sector);
            json.addProperty("level", level);
            json.addProperty("cashCents", cashCents);
            json.addProperty("valuationCents", valuationCents);
            json.addProperty("employees", employees);
            json.addProperty("reputation", reputation);
            json.addProperty("createdDay", createdDay);
            json.addProperty("lastSettleDay", lastSettleDay);
            json.addProperty("lastDividendDay", lastDividendDay);
            json.addProperty("dividendRatio", dividendRatio);
            json.addProperty("sharesTotal", sharesTotal);
            json.addProperty("listed", listed);
            if (code != null) {
                json.addProperty("code", code);
            }
            json.addProperty("lockUntilDay", lockUntilDay);
            json.addProperty("cumulativeRevenueCents", cumulativeRevenueCents);
            json.addProperty("bankrupt", bankrupt);
            json.addProperty("sectorChangeDay", sectorChangeDay);
            json.addProperty("lastRaiseDay", lastRaiseDay);
            json.addProperty("ipoPending", ipoPending);
            json.addProperty("ipoApplyDay", ipoApplyDay);
            json.addProperty("ipoFloatRatio", ipoFloatRatio);
            json.addProperty("ipoProspectus", ipoProspectus);
            JsonObject shareJson = new JsonObject();
            for (Map.Entry<String, Long> entry : holders.entrySet()) {
                shareJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("holders", shareJson);
            JsonArray revenue = new JsonArray();
            for (Long value : revenueHistory) {
                revenue.add(value);
            }
            json.add("revenueHistory", revenue);
            JsonArray profit = new JsonArray();
            for (Long value : profitHistory) {
                profit.add(value);
            }
            json.add("profitHistory", profit);
            return json;
        }

        public static Company fromJson(JsonObject json) {
            Company company = new Company();
            company.id = JsonFile.str(json, "id", "");
            company.name = JsonFile.str(json, "name", "");
            company.ceoUuid = JsonFile.str(json, "ceo", "");
            company.sector = JsonFile.str(json, "sector", "mining");
            company.level = Cents.clampInt(JsonFile.intVal(json, "level", 1), 1, 5);
            company.cashCents = JsonFile.lng(json, "cashCents", 0L);
            company.valuationCents = JsonFile.lng(json, "valuationCents", 500_000L);
            company.employees = Math.max(0, JsonFile.intVal(json, "employees", 0));
            company.reputation = Cents.clampDouble(JsonFile.dbl(json, "reputation", 1.0), 0.6, 1.4);
            company.createdDay = JsonFile.lng(json, "createdDay", 0L);
            company.lastSettleDay = JsonFile.lng(json, "lastSettleDay", -1L);
            company.lastDividendDay = JsonFile.lng(json, "lastDividendDay", -1L);
            company.dividendRatio = Cents.clampDouble(JsonFile.dbl(json, "dividendRatio", 0.0), 0.0, 0.8);
            company.sharesTotal = Math.max(1L, JsonFile.lng(json, "sharesTotal", 10_000L));
            company.listed = JsonFile.bool(json, "listed", false);
            String code = JsonFile.str(json, "code", "");
            company.code = code.isEmpty() ? null : code;
            company.lockUntilDay = JsonFile.lng(json, "lockUntilDay", -1L);
            company.cumulativeRevenueCents = JsonFile.lng(json, "cumulativeRevenueCents", 0L);
            company.bankrupt = JsonFile.bool(json, "bankrupt", false);
            company.sectorChangeDay = JsonFile.lng(json, "sectorChangeDay", -1L);
            company.lastRaiseDay = JsonFile.lng(json, "lastRaiseDay", -1L);
            company.ipoPending = JsonFile.bool(json, "ipoPending", false);
            company.ipoApplyDay = JsonFile.lng(json, "ipoApplyDay", -1L);
            company.ipoFloatRatio = JsonFile.dbl(json, "ipoFloatRatio", 0.0);
            company.ipoProspectus = JsonFile.str(json, "ipoProspectus", "");
            for (Map.Entry<String, JsonElement> entry : JsonFile.object(json, "holders").entrySet()) {
                try {
                    long value = entry.getValue().getAsLong();
                    if (value > 0L) {
                        company.holders.put(entry.getKey(), value);
                    }
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            for (JsonElement element : JsonFile.array(json, "revenueHistory")) {
                try {
                    company.revenueHistory.add(element.getAsLong());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            for (JsonElement element : JsonFile.array(json, "profitHistory")) {
                try {
                    company.profitHistory.add(element.getAsLong());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            return company;
        }
    }

    private final Path file;
    private final Ledger ledger;
    private final Map<String, Company> companies = new LinkedHashMap<>();
    private long nextId = 1L;
    private final List<String> bankruptHistory = new ArrayList<>();

    public CompanyStore(Path file, Ledger ledger) {
        this.file = file;
        this.ledger = ledger;
    }

    public void load() {
        companies.clear();
        bankruptHistory.clear();
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            for (JsonElement element : JsonFile.array(root, "companies")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                Company company = Company.fromJson(element.getAsJsonObject());
                if (company.id.isEmpty()) {
                    continue;
                }
                companies.put(company.id, company);
            }
            nextId = JsonFile.lng(root, "nextId", 1L);
            for (JsonElement element : JsonFile.array(root, "bankruptHistory")) {
                try {
                    bankruptHistory.add(element.getAsString());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
        }
        for (Company company : companies.values()) {
            ledger.register(company);
        }
        // nextId 兜底：防止 id 重复
        for (Company company : companies.values()) {
            String digits = company.id.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    long parsed = Long.parseLong(digits);
                    if (parsed >= nextId) {
                        nextId = parsed + 1L;
                    }
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        Log.info("公司加载完成：" + companies.size() + " 家（其中破产 " + bankruptHistory.size() + " 家）");
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("nextId", nextId);
        JsonArray array = new JsonArray();
        for (Company company : companies.values()) {
            array.add(company.toJson());
        }
        root.add("companies", array);
        JsonArray history = new JsonArray();
        for (String name : bankruptHistory) {
            history.add(name);
        }
        root.add("bankruptHistory", history);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 companies.json 失败（下个 tick 重试）", error);
        }
    }

    // ── 查询 ──

    public Company get(String id) {
        return id == null ? null : companies.get(id);
    }

    public Company byCode(String code) {
        if (code == null) {
            return null;
        }
        for (Company company : companies.values()) {
            if (code.equalsIgnoreCase(company.code)) {
                return company;
            }
        }
        return null;
    }

    public Collection<Company> all() {
        return companies.values();
    }

    public List<Company> byCeo(String uuid) {
        List<Company> result = new ArrayList<>();
        for (Company company : companies.values()) {
            if (company.ceoUuid.equals(uuid)) {
                result.add(company);
            }
        }
        return result;
    }

    public Company findByName(String name) {
        if (name == null) {
            return null;
        }
        String needle = name.trim().toLowerCase(Locale.ROOT);
        for (Company company : companies.values()) {
            if (company.name.toLowerCase(Locale.ROOT).equals(needle)) {
                return company;
            }
        }
        return null;
    }

    public List<Company> listed() {
        List<Company> result = new ArrayList<>();
        for (Company company : companies.values()) {
            if (company.listed && !company.bankrupt) {
                result.add(company);
            }
        }
        return result;
    }

    public List<String> bankruptHistory() {
        return bankruptHistory;
    }

    public long countByCeo(String uuid) {
        return byCeo(uuid).size();
    }

    // ── 创建 / 行业切换 ──

    public Company create(Account ceo, String name, String sectorCode, long tradingDay, MarketSettings settings) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 16) {
            throw new IllegalArgumentException("公司名要 2-16 个字符");
        }
        if (!SECTORS.containsKey(sectorCode)) {
            throw new IllegalArgumentException("行业只能是：采矿(mining)、农业(farming)、制造(manufacturing)、贸易(trading)、服务(service)");
        }
        if (findByName(trimmed) != null) {
            throw new IllegalStateException("公司名已被占用");
        }
        if (containsSensitive(trimmed)) {
            throw new IllegalStateException("公司名包含敏感词");
        }
        if (ceo.companies.size() >= 3) {
            throw new IllegalStateException("一个人最多开 3 家公司");
        }
        long fee = settings.companyCreateFeeCents;
        if (ceo.balanceCents() < fee) {
            throw new IllegalStateException("创建公司需要 " + Cents.format(fee) + " 币，余额不足");
        }
        String id = String.format(Locale.ROOT, "c-%04d", nextId++);
        Company company = new Company();
        company.id = id;
        company.name = trimmed;
        company.ceoUuid = ceo.uuid;
        company.sector = sectorCode;
        company.createdDay = tradingDay;
        company.sharesTotal = settings.companySharesTotal;
        company.holders.put(ceo.uuid, company.sharesTotal);
        companies.put(id, company);
        ledger.register(company);
        ceo.companies.add(id);
        // 创建费：回收货币
        ledger.burn(ceo, fee, "company.createFee", ceo.name, "创建公司 " + trimmed);
        return company;
    }

    private static boolean containsSensitive(String name) {
        String[] bad = {"admin", "管理员", "服主", "gm", "system", "系统", "sb", "傻逼", "fuck", "shit", "nigger", "习近平", "共产党"};
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : bad) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public void switchSector(Company company, String sectorCode, long tradingDay, long cooldownDays,
                             MarketSettings settings) {
        if (!SECTORS.containsKey(sectorCode)) {
            throw new IllegalArgumentException("没有这个行业：" + sectorCode);
        }
        if (company.sector.equals(sectorCode)) {
            throw new IllegalStateException("公司已经在" + SECTORS.get(sectorCode) + "行业了");
        }
        if (tradingDay - company.sectorChangeDay < cooldownDays && company.sectorChangeDay >= 0L) {
            throw new IllegalStateException("行业切换冷却中（还要 " + (cooldownDays - (tradingDay - company.sectorChangeDay)) + " 交易日）");
        }
        long fee = settings.companySectorSwitchFeeCents;
        if (company.cashCents < fee) {
            throw new IllegalStateException("公司现金不足，切换行业需要 " + Cents.format(fee) + " 币");
        }
        ledger.burn(company, fee, "company.sectorFee", company.name, "切换行业到 " + SECTORS.get(sectorCode));
        company.sector = sectorCode;
        company.sectorChangeDay = tradingDay;
    }

    // ── 存钱/取钱 ──

    public void deposit(Account ceo, Company company, long amountCents) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("存入金额必须为正");
        }
        ledger.transfer(ceo, company, amountCents, "company.deposit", ceo.name, "向 " + company.name + " 注资");
    }

    public void withdraw(Account ceo, Company company, long amountCents) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("取出金额必须为正");
        }
        if (company.cashCents < amountCents) {
            throw new IllegalStateException("公司现金不足");
        }
        ledger.transfer(company, ceo, amountCents, "company.withdraw", ceo.name, "从 " + company.name + " 取现");
        ceo.recordIncome(amountCents);
    }

    // ── 员工 ──

    public void hire(Company company, int count, MarketSettings settings) {
        if (count <= 0) {
            throw new IllegalArgumentException("招聘人数必须为正");
        }
        if (company.employees + count > company.maxEmployees()) {
            throw new IllegalStateException("员工上限是 " + company.maxEmployees() + " 人（等级 " + company.level + "）");
        }
        long fee = Math.multiplyExact(settings.companyHireFeeCents, count);
        if (company.cashCents < fee) {
            throw new IllegalStateException("招聘费不足：" + Cents.format(fee) + " 币");
        }
        ledger.burn(company, fee, "company.hireFee", company.name, "招聘 " + count + " 人");
        company.employees += count;
    }

    public void fire(Company company, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("裁员人数必须为正");
        }
        company.employees = Math.max(0, company.employees - count);
        // 裁员不退招聘费；欠薪会掉口碑
        company.reputation = Cents.clampDouble(company.reputation - 0.01 * count, 0.6, 1.4);
    }

    // ── 升级 ──

    public void levelUp(Company company, MarketSettings settings) {
        if (company.level >= 5) {
            throw new IllegalStateException("公司已经是 5 级了");
        }
        long fee = LEVEL_UP_FEE[company.level];
        if (company.cashCents < fee) {
            throw new IllegalStateException("公司现金不足，升级需要 " + Cents.format(fee) + " 币");
        }
        ledger.burn(company, fee, "company.levelUpFee", company.name, "升级到 " + (company.level + 1) + " 级");
        company.level++;
    }

    // ── 日结 ──

    /**
     * 每个交易日结算一次。
     *
     * <p>口径：营收由系统支付给公司（增发）→ 公司再付员工日薪（销毁）、维护费（销毁）、
     * 公司税（进政府预算），剩下的净利润留在公司现金里。这样"营收 ≥ 成本"时公司现金自然增加。</p>
     *
     * @return 净利润（分），可以为负
     */
    public long settleDaily(Company company, long tradingDay, double sectorClimate, MarketSettings settings,
                            Random random, double companyTaxRate) {
        if (company.bankrupt) {
            return 0L;
        }
        long base = SECTOR_BASE_REVENUE.getOrDefault(company.sector, 40_000L);
        double employeeFactor = 1.0 + 0.08 * company.employees;
        double noise = 0.9 + random.nextDouble() * 0.2;
        long revenue = Math.max(0L, Math.round(base * employeeFactor * sectorClimate * company.reputation * noise));
        long wages = Cents.mul(settings.companyDailyWageCents, company.employees);
        long maintenance = Cents.percent(revenue, 0.05);
        long profitBeforeTax = revenue - wages - maintenance;
        long tax = profitBeforeTax > 0L ? Cents.percent(profitBeforeTax, companyTaxRate) : 0L;
        long netProfit = profitBeforeTax - tax;

        // 营收进公司现金
        if (revenue > 0L) {
            ledger.issue(company, revenue, "company.revenue", company.name, "日结营收");
        }
        // 员工日薪：付得出就付（销毁），付不出掉口碑
        if (wages > 0L) {
            if (company.cashCents >= wages) {
                ledger.burn(company, wages, "company.wage", company.name, "员工日薪 " + company.employees + " 人");
                company.reputation = Cents.clampDouble(company.reputation + 0.01, 0.6, 1.4);
            } else {
                company.reputation = Cents.clampDouble(company.reputation - 0.05, 0.6, 1.4);
            }
        }
        // 维护费：系统成本，销毁
        long payableMaintenance = Math.min(maintenance, Math.max(0L, company.cashCents));
        if (payableMaintenance > 0L) {
            ledger.burn(company, payableMaintenance, "company.maintenance", company.name, "维护费");
        }
        // 公司税进政府预算
        long payableTax = Math.min(tax, Math.max(0L, company.cashCents));
        com.mogutofu.aimcmarket.data.BalanceAccount govBudget = ledger.find(Ledger.GOV_BUDGET);
        if (payableTax > 0L && govBudget != null) {
            ledger.transfer(company, govBudget, payableTax, "company.tax", company.name, "公司税");
        }
        company.cumulativeRevenueCents = Math.addExact(company.cumulativeRevenueCents, revenue);
        company.revenueHistory.add(revenue);
        company.profitHistory.add(netProfit);
        while (company.revenueHistory.size() > 30) {
            company.revenueHistory.remove(0);
        }
        while (company.profitHistory.size() > 30) {
            company.profitHistory.remove(0);
        }
        company.lastSettleDay = tradingDay;
        updateValuation(company);
        // 破产判定：现金为负且连续 3 个交易日净利润为负
        if (company.cashCents < 0L) {
            int negative = 0;
            for (int i = company.profitHistory.size() - 1; i >= 0 && negative < 3; i--) {
                if (company.profitHistory.get(i) < 0L) {
                    negative++;
                } else {
                    break;
                }
            }
            if (negative >= 3) {
                markBankrupt(company, tradingDay);
            }
        }
        return netProfit;
    }

    public void updateValuation(Company company) {
        long avgProfit = Math.max(0L, company.averageDailyProfit(30));
        long value = Math.addExact(company.cashCents, Cents.mul(avgProfit, 120L));
        company.valuationCents = Math.max(500_000L, value);
    }

    public void markBankrupt(Company company, long tradingDay) {
        if (company.bankrupt) {
            return;
        }
        company.bankrupt = true;
        company.employees = 0;
        company.listed = false;
        bankruptHistory.add(company.name);
        // 公司现金清零（销毁）
        if (company.cashCents > 0L) {
            ledger.burn(company, company.cashCents, "company.bankrupt", company.name, "破产清算");
        }
        Log.info("公司破产：" + company.name + "（" + company.id + "）");
    }

    /** 关闭公司：CEO 主动关，要求没有未偿债务（这里简化：现金归零后注销）。 */
    public void close(Company company, Account ceo) {
        if (company.listed) {
            throw new IllegalStateException("上市公司不能直接关闭，先退市");
        }
        if (company.cashCents > 0L) {
            ledger.transfer(company, ceo, company.cashCents, "company.closeRefund", ceo.name, "关闭公司退还现金");
        }
        companies.remove(company.id);
        ledger.unregister(company.accountId());
        ceo.companies.remove(company.id);
    }

    // ── 分红 ──

    /**
     * 每 7 交易日分红一次：把净利润的 dividendRatio 按持股比例发给股东。
     *
     * @return 分红总额
     */
    public long payDividend(Company company, long tradingDay, Accounts accounts) {
        if (company.bankrupt || company.dividendRatio <= 0.0) {
            return 0L;
        }
        long profit = company.profitHistory.isEmpty() ? 0L : company.profitHistory.get(company.profitHistory.size() - 1);
        if (profit <= 0L) {
            return 0L;
        }
        long budget = Cents.percent(profit, company.dividendRatio);
        budget = Math.min(budget, Math.max(0L, company.cashCents));
        if (budget <= 0L) {
            return 0L;
        }
        long totalPaid = 0L;
        long distributed = 0L;
        List<Map.Entry<String, Long>> holders = new ArrayList<>(company.holders.entrySet());
        for (Map.Entry<String, Long> entry : holders) {
            if ("public".equals(entry.getKey())) {
                // 流通股的分红进做市商账户（代表公众股东）
                long part = Cents.mulDiv(budget, entry.getValue(), company.sharesTotal);
                if (part > 0L) {
                    ledger.transfer(company, ledger.find(Ledger.MAKER), part, "company.dividend", company.name, "公众股东分红");
                    totalPaid = Math.addExact(totalPaid, part);
                }
                continue;
            }
            Account account = accounts.get(entry.getKey());
            long part = Cents.mulDiv(budget, entry.getValue(), company.sharesTotal);
            if (account != null && part > 0L) {
                ledger.transfer(company, account, part, "company.dividend", company.name, company.name + " 分红");
                account.recordIncome(part);
                totalPaid = Math.addExact(totalPaid, part);
            }
            distributed = Math.addExact(distributed, part);
        }
        company.lastDividendDay = tradingDay;
        return totalPaid;
    }

    // ── IPO 支持 ──

    /**
     * 上市：按流通比例发行新股，CEO 股份等比例稀释，募资进公司现金。
     *
     * <p>发行价 = 公司估值 / 发行后总股本（等价于任务书里的 {@code valuation / (原股本 / (1-流通比例))}）。</p>
     *
     * @param floatRatio 流通比例 0.10-0.49
     * @return 每股发行价（分）
     */
    public long listCompany(Company company, Account ceo, double floatRatio, long tradingDay, long lockDays) {
        if (company.listed) {
            throw new IllegalStateException("公司已经上市了");
        }
        if (floatRatio < 0.10 || floatRatio > 0.49) {
            throw new IllegalArgumentException("流通比例要在 10%-49% 之间");
        }
        long newTotal = Math.max(company.sharesTotal, Math.round(company.sharesTotal / (1.0 - floatRatio)));
        long newShares = newTotal - company.sharesTotal;
        if (newShares <= 0L) {
            throw new IllegalStateException("发行股数为 0，检查流通比例");
        }
        long ipoPrice = Math.max(1L, company.valuationCents / newTotal);
        company.sharesTotal = newTotal;
        company.holders.merge("public", newShares, Long::sum);
        long raised = Cents.mul(newShares, ipoPrice);
        if (raised > 0L) {
            ledger.issue(company, raised, "company.ipo", ceo == null ? "系统" : ceo.name, company.name + " IPO 募资");
        }
        company.listed = true;
        company.lockUntilDay = tradingDay + lockDays;
        updateValuation(company);
        return ipoPrice;
    }

    /** 增发再融资：按当前股价 9 折向 NPC 机构发行，单次稀释 ≤20%。 */
    public long raise(Company company, long amountCents, long currentDay, long cooldownDays, MarketSettings settings,
                      double currentPriceCents, Ledger ledger, Account ceo) {
        if (!company.listed) {
            throw new IllegalStateException("没上市不能增发");
        }
        if (currentDay - company.lastRaiseDay < cooldownDays && company.lastRaiseDay >= 0L) {
            throw new IllegalStateException("增发冷却中");
        }
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("增发金额必须为正");
        }
        double issuePrice = Math.max(1.0, currentPriceCents * 0.9);
        long newShares = Math.max(1L, Math.round(amountCents / issuePrice));
        long dilution = Cents.mulDiv(newShares, 1_000_000L, company.sharesTotal);
        if (dilution > 200_000L) {
            throw new IllegalStateException("单次增发稀释不能超过 20%");
        }
        company.sharesTotal = Math.addExact(company.sharesTotal, newShares);
        company.holders.merge("public", newShares, Long::sum);
        ledger.issue(company, amountCents, "company.raise", ceo.name, company.name + " 增发");
        company.lastRaiseDay = currentDay;
        return newShares;
    }

    /**
     * 合伙人/投资人入股：投资人转钱进公司账户，公司增发新股给投资人。
     *
     * <p>入股价格 = 公司估值 / 总股数；单次最多拿到公司 49% 股份（防止一笔买走）。
     * 钱走 ledger.transfer（投资人 → 公司），账本两边都变，不会凭空多钱。</p>
     *
     * @return 本次获得的新股数
     */
    public long invest(Company company, Account investor, long amountCents, Ledger ledger) {
        if (company == null || investor == null) {
            throw new IllegalArgumentException("公司或投资人不存在");
        }
        if (company.bankrupt) {
            throw new IllegalStateException("公司已破产，不能投资");
        }
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("投资金额必须大于 0");
        }
        if (investor.balanceCents() < amountCents) {
            throw new IllegalStateException("余额不足，你只有 " + Cents.format(investor.balanceCents()) + " 币");
        }
        long valuation = Math.max(1L, company.valuationCents);
        long sharePrice = Math.max(1L, valuation / Math.max(1L, company.sharesTotal));
        long newShares = Math.max(1L, Math.round((double) amountCents / (double) sharePrice));
        long totalAfter = Math.addExact(company.sharesTotal, newShares);
        long percentMilli = Math.round(newShares * 1_000_000.0 / (double) totalAfter);
        if (percentMilli > 490_000L) {
            throw new IllegalStateException("单次投资最多拿到公司 49% 股份，请减少金额");
        }
        ledger.transfer(investor, company, amountCents, "company.invest", investor.name,
                "投资「" + company.name + "」");
        company.sharesTotal = totalAfter;
        company.holders.merge(investor.uuid, newShares, Long::sum);
        company.valuationCents = Math.addExact(valuation, amountCents);
        return newShares;
    }

    /** 退市：股份按 0 结算。 */
    public void delist(Company company, long tradingDay) {
        company.listed = false;
        company.code = null;
        Log.info("公司退市：" + company.name);
    }

    public Path file() {
        return file;
    }
}
