package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.TreeMap;

/**
 * 股票市场：NPC 上市公司 + 玩家 IPO 的公司，以及财报、行业景气、大盘指数。
 *
 * <p>指数按流通市值加权，基点 1000.00；每个交易日快照一次，用于算 7 交易日涨跌幅。</p>
 */
public final class StockStore {
    /** 一家上市公司。 */
    public static final class Stock {
        public String code;
        public String name;
        public String sector;
        public long initialPriceCents;
        public long priceCents;
        public double volatility;
        /** 年化股息率（0.025 = 2.5%）。 */
        public double annualDividendRate;
        /** true = 系统公司；false = 玩家 IPO 的公司。 */
        public boolean npc = true;
        /** 玩家公司的 id；NPC 为 null。 */
        public String companyId;
        /** 财报 outlook 带来的每交易日漂移（-0.03 ~ +0.03）。 */
        public double driftBias;
        public String lastOutlook = "flat";
        public String lastHeadline = "";
        public long lastReportDay = -1L;
        public long lastDividendDay = -1L;
        /** 流通股本，指数权重用。 */
        public long sharesOutstanding;
        /** 上市交易日（玩家公司用）。 */
        public long listedDay = -1L;
        /** CEO 股份锁定期到哪天为止（交易日）。 */
        public long lockUntilDay = -1L;
        public long lastRevenueCents;
        public long lastProfitCents;
        public String industryClimateKey;
        /** 股价连续低于 1.00 币的交易日数（退市判定）。 */
        public int lowPriceDays;
    }

    /** 一期财报。 */
    public static final class FinancialReport {
        public String code;
        public long day;
        public long revenueCents;
        public long profitCents;
        /** up | flat | down */
        public String outlook = "flat";
        public String headline = "";

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("code", code);
            json.addProperty("day", day);
            json.addProperty("revenueCents", revenueCents);
            json.addProperty("profitCents", profitCents);
            json.addProperty("outlook", outlook);
            json.addProperty("headline", headline);
            return json;
        }

        public static FinancialReport fromJson(JsonObject json) {
            FinancialReport report = new FinancialReport();
            report.code = JsonFile.str(json, "code", "");
            report.day = JsonFile.lng(json, "day", 0L);
            report.revenueCents = JsonFile.lng(json, "revenueCents", 0L);
            report.profitCents = JsonFile.lng(json, "profitCents", 0L);
            report.outlook = JsonFile.str(json, "outlook", "flat");
            report.headline = JsonFile.str(json, "headline", "");
            return report;
        }
    }

    /** 10 家 NPC 上市公司（行业景气/财报/分红都按交易日走）。 */
    public static final String[][] NPC_COMPANIES = {
            // 代码, 名称, 行业, 初始价（币）, 波动 sigma, 年化股息率
            {"BLK", "方块重工", "工业", "120", "0.018", "0.025"},
            {"RED", "红石科技", "科技", "300", "0.035", "0.000"},
            {"NTL", "下界能源", "能源", "80", "0.025", "0.040"},
            {"END", "末地物流", "物流", "45", "0.020", "0.010"},
            {"EMR", "绿宝石银行", "金融", "200", "0.012", "0.030"},
            {"GRN", "绿野农业", "农业", "40", "0.015", "0.020"},
            {"MED", "药水制药", "医药", "150", "0.028", "0.015"},
            {"ARS", "红石军械", "军工", "90", "0.030", "0.010"},
            {"BOK", "附魔书屋", "传媒", "60", "0.022", "0.025"},
            {"ORB", "轨道航运", "航天", "260", "0.040", "0.005"},
    };

    private final Path file;
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final Map<String, Double> sectorClimate = new LinkedHashMap<>();
    private final List<FinancialReport> reports = new ArrayList<>();
    private final Map<Long, Long> indexByDay = new TreeMap<>();
    private long indexCentiPoints = 100_000L; // 1000.00 点
    private long lastClimateDay = -1L;

    public StockStore(Path file) {
        this.file = file;
    }

    public void load() {
        stocks.clear();
        sectorClimate.clear();
        reports.clear();
        indexByDay.clear();

        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            for (JsonElement element : JsonFile.array(root, "stocks")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                Stock stock = stockFromJson(element.getAsJsonObject());
                if (stock != null) {
                    stocks.put(stock.code, stock);
                }
            }
            for (JsonElement element : JsonFile.array(root, "reports")) {
                if (element.isJsonObject()) {
                    reports.add(FinancialReport.fromJson(element.getAsJsonObject()));
                }
            }
            for (Map.Entry<String, JsonElement> entry : JsonFile.object(root, "sectorClimate").entrySet()) {
                try {
                    sectorClimate.put(entry.getKey(), entry.getValue().getAsDouble());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            for (Map.Entry<String, JsonElement> entry : JsonFile.object(root, "indexByDay").entrySet()) {
                try {
                    indexByDay.put(Long.parseLong(entry.getKey()), entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            indexCentiPoints = JsonFile.lng(root, "indexCentiPoints", indexCentiPoints);
            lastClimateDay = JsonFile.lng(root, "lastClimateDay", -1L);
        }
        // 先读盘里的股票，再补齐 NPC 配置里缺的公司（版本升级/新增上市公司用）——
        // 顺序不能反，否则每次重启都会把持久化的股价/财报/分红状态覆盖回初始值。
        seedNpcIfMissing();
        for (String[] spec : NPC_COMPANIES) {
            sectorClimate.putIfAbsent(spec[2], 1.0);
        }
        if (reports.size() > 400) {
            reports.subList(0, reports.size() - 400).clear();
        }
        Log.info("股票市场加载完成：" + stocks.size() + " 只股票");
    }

    private void seedNpcIfMissing() {
        for (String[] spec : NPC_COMPANIES) {
            if (stocks.containsKey(spec[0])) {
                continue;
            }
            Stock stock = new Stock();
            stock.code = spec[0];
            stock.name = spec[1];
            stock.sector = spec[2];
            stock.initialPriceCents = Cents.parse(spec[3]);
            stock.priceCents = stock.initialPriceCents;
            stock.volatility = Double.parseDouble(spec[4]);
            stock.annualDividendRate = Double.parseDouble(spec[5]);
            stock.npc = true;
            stock.sharesOutstanding = 1_000_000L;
            stock.industryClimateKey = stock.sector;
            stocks.put(stock.code, stock);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonArray stockArray = new JsonArray();
        for (Stock stock : stocks.values()) {
            stockArray.add(stockToJson(stock));
        }
        root.add("stocks", stockArray);
        JsonArray reportArray = new JsonArray();
        for (FinancialReport report : reports) {
            reportArray.add(report.toJson());
        }
        root.add("reports", reportArray);
        JsonObject climateJson = new JsonObject();
        for (Map.Entry<String, Double> entry : sectorClimate.entrySet()) {
            climateJson.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("sectorClimate", climateJson);
        JsonObject indexJson = new JsonObject();
        for (Map.Entry<Long, Long> entry : indexByDay.entrySet()) {
            indexJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        root.add("indexByDay", indexJson);
        root.addProperty("indexCentiPoints", indexCentiPoints);
        root.addProperty("lastClimateDay", lastClimateDay);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 stocks.json 失败（下个 tick 重试）", error);
        }
    }

    private static JsonObject stockToJson(Stock stock) {
        JsonObject json = new JsonObject();
        json.addProperty("code", stock.code);
        json.addProperty("name", stock.name);
        json.addProperty("sector", stock.sector);
        json.addProperty("initialPriceCents", stock.initialPriceCents);
        json.addProperty("priceCents", stock.priceCents);
        json.addProperty("volatility", stock.volatility);
        json.addProperty("annualDividendRate", stock.annualDividendRate);
        json.addProperty("npc", stock.npc);
        if (stock.companyId != null) {
            json.addProperty("companyId", stock.companyId);
        }
        json.addProperty("driftBias", stock.driftBias);
        json.addProperty("lastOutlook", stock.lastOutlook);
        json.addProperty("lastHeadline", stock.lastHeadline);
        json.addProperty("lastReportDay", stock.lastReportDay);
        json.addProperty("lastDividendDay", stock.lastDividendDay);
        json.addProperty("sharesOutstanding", stock.sharesOutstanding);
        json.addProperty("listedDay", stock.listedDay);
        json.addProperty("lockUntilDay", stock.lockUntilDay);
        json.addProperty("lastRevenueCents", stock.lastRevenueCents);
        json.addProperty("lastProfitCents", stock.lastProfitCents);
        json.addProperty("lowPriceDays", stock.lowPriceDays);
        return json;
    }

    private static Stock stockFromJson(JsonObject json) {
        String code = JsonFile.str(json, "code", "").toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            return null;
        }
        Stock stock = new Stock();
        stock.code = code;
        stock.name = JsonFile.str(json, "name", code);
        stock.sector = JsonFile.str(json, "sector", "工业");
        stock.initialPriceCents = Math.max(1L, JsonFile.lng(json, "initialPriceCents", 1000L));
        stock.priceCents = Math.max(1L, JsonFile.lng(json, "priceCents", stock.initialPriceCents));
        stock.volatility = Cents.clampDouble(JsonFile.dbl(json, "volatility", 0.025), 0.001, 0.20);
        stock.annualDividendRate = Cents.clampDouble(JsonFile.dbl(json, "annualDividendRate", 0.0), 0.0, 0.5);
        stock.npc = JsonFile.bool(json, "npc", true);
        String companyId = JsonFile.str(json, "companyId", "");
        stock.companyId = companyId.isEmpty() ? null : companyId;
        stock.driftBias = Cents.clampDouble(JsonFile.dbl(json, "driftBias", 0.0), -0.03, 0.03);
        stock.lastOutlook = JsonFile.str(json, "lastOutlook", "flat");
        stock.lastHeadline = JsonFile.str(json, "lastHeadline", "");
        stock.lastReportDay = JsonFile.lng(json, "lastReportDay", -1L);
        stock.lastDividendDay = JsonFile.lng(json, "lastDividendDay", -1L);
        stock.sharesOutstanding = Math.max(1L, JsonFile.lng(json, "sharesOutstanding", 1_000_000L));
        stock.listedDay = JsonFile.lng(json, "listedDay", -1L);
        stock.lockUntilDay = JsonFile.lng(json, "lockUntilDay", -1L);
        stock.lastRevenueCents = JsonFile.lng(json, "lastRevenueCents", 0L);
        stock.lastProfitCents = JsonFile.lng(json, "lastProfitCents", 0L);
        stock.lowPriceDays = JsonFile.intVal(json, "lowPriceDays", 0);
        stock.industryClimateKey = stock.sector;
        return stock;
    }

    // ── 查询 ──

    public Stock stock(String code) {
        if (code == null) {
            return null;
        }
        return stocks.get(code.trim().toUpperCase(Locale.ROOT));
    }

    public Collection<Stock> all() {
        return stocks.values();
    }

    public List<Stock> npcStocks() {
        List<Stock> result = new ArrayList<>();
        for (Stock stock : stocks.values()) {
            if (stock.npc) {
                result.add(stock);
            }
        }
        return result;
    }

    public List<Stock> playerStocks() {
        List<Stock> result = new ArrayList<>();
        for (Stock stock : stocks.values()) {
            if (!stock.npc) {
                result.add(stock);
            }
        }
        return result;
    }

    public long priceOf(String code) {
        Stock stock = stock(code);
        return stock == null ? 0L : stock.priceCents;
    }

    public void addStock(Stock stock) {
        stocks.put(stock.code.toUpperCase(Locale.ROOT), stock);
    }

    public void removeStock(String code) {
        stocks.remove(code.toUpperCase(Locale.ROOT));
    }

    /** 找下一个玩家公司股票代码：P001、P002... */
    public String nextPlayerCode() {
        for (int i = 1; i <= 999; i++) {
            String code = String.format(Locale.ROOT, "P%03d", i);
            if (!stocks.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("玩家公司代码用完了");
    }

    // ── 指数 ──

    /**
     * 重算大盘指数（基点 1000.00）并记录到当日。
     *
     * @return 指数（以"点 ×100"存，100000 = 1000.00 点）
     */
    public long tickIndex(long tradingDay) {
        long baseCap = 0L;
        long nowCap = 0L;
        for (Stock stock : stocks.values()) {
            long shares = Math.max(1L, stock.sharesOutstanding);
            baseCap = Math.addExact(baseCap, Math.multiplyExact(stock.initialPriceCents, shares));
            nowCap = Math.addExact(nowCap, Math.multiplyExact(stock.priceCents, shares));
        }
        indexCentiPoints = baseCap <= 0L ? 100_000L : Cents.mulDiv(nowCap, 100_000L, baseCap);
        indexByDay.put(tradingDay, indexCentiPoints);
        long cutoff = tradingDay - 30L;
        indexByDay.keySet().removeIf(day -> day < cutoff);
        return indexCentiPoints;
    }

    public long indexCentiPoints() {
        return indexCentiPoints;
    }

    /** 7 个交易日的指数涨跌幅（-0.04 = 跌 4%）；数据不足返回 0。 */
    public double indexChange7d(long tradingDay) {
        Long now = indexByDay.get(tradingDay);
        Long before = indexByDay.get(tradingDay - 7L);
        if (now == null || before == null || before == 0L) {
            return 0.0;
        }
        return (double) now / (double) before - 1.0;
    }

    // ── 行业景气 ──

    public double sectorClimate(String sector) {
        return sectorClimate.getOrDefault(sector, 1.0);
    }

    public void setSectorClimate(String sector, double multiplier) {
        sectorClimate.put(sector, Cents.clampDouble(multiplier, 0.95, 1.05));
    }

    public void setSectorClimateBonus(String sector, double addRatio, long tradingDay) {
        double base = sectorClimate.getOrDefault(sector, 1.0);
        sectorClimate.put(sector, Cents.clampDouble(base + addRatio, 0.90, 1.10));
    }

    public Map<String, Double> allSectorClimate() {
        return sectorClimate;
    }

    public long lastClimateDay() {
        return lastClimateDay;
    }

    public void setLastClimateDay(long day) {
        lastClimateDay = day;
    }

    // ── 财报 ──

    public void addReport(FinancialReport report) {
        reports.add(report);
        if (reports.size() > 400) {
            reports.subList(0, reports.size() - 400).clear();
        }
        Stock stock = stock(report.code);
        if (stock != null) {
            stock.lastReportDay = report.day;
            stock.lastOutlook = report.outlook;
            stock.lastHeadline = report.headline;
            stock.lastRevenueCents = report.revenueCents;
            stock.lastProfitCents = report.profitCents;
            stock.driftBias = switch (report.outlook == null ? "flat" : report.outlook) {
                case "up" -> 0.005;
                case "down" -> -0.005;
                default -> 0.0;
            };
        }
    }

    public List<FinancialReport> reportsFor(String code, int limit) {
        List<FinancialReport> result = new ArrayList<>();
        for (int i = reports.size() - 1; i >= 0 && result.size() < limit; i--) {
            FinancialReport report = reports.get(i);
            if (report.code.equalsIgnoreCase(code)) {
                result.add(report);
            }
        }
        return result;
    }

    public FinancialReport latestReport(String code) {
        for (int i = reports.size() - 1; i >= 0; i--) {
            if (reports.get(i).code.equalsIgnoreCase(code)) {
                return reports.get(i);
            }
        }
        return null;
    }

    /** 指数点位显示：1000.00。 */
    public String formatIndex() {
        return Cents.format(indexCentiPoints);
    }

    public Path file() {
        return file;
    }
}
