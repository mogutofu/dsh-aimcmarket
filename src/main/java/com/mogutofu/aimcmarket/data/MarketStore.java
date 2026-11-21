package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 商品市场状态：目录（catalog）、当前价格、事件乘数、做市商账户、手续费池、成交额和 CPI 历史。
 *
 * <p>价格本身由 {@code engine.PriceEngine} 计算，这里只存状态与查询。</p>
 */
public final class MarketStore {
    /** 目录里的一项。价格单位都是分。 */
    public static final class CatalogItem {
        public final String id;
        public final String nameZh;
        public final List<String> aliases;
        public final long basePriceCents;
        public final double volatility;
        public final long liquidity;
        public final boolean enabled;

        CatalogItem(String id, String nameZh, List<String> aliases, long basePriceCents,
                    double volatility, long liquidity, boolean enabled) {
            this.id = id;
            this.nameZh = nameZh;
            this.aliases = aliases;
            this.basePriceCents = basePriceCents;
            this.volatility = volatility;
            this.liquidity = liquidity;
            this.enabled = enabled;
        }
    }

    /** 新闻/政策带来的临时价格乘数。 */
    public static final class PriceEvent {
        public String target;
        public double multiplier;
        public int ticksLeft;
        public String source;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("target", target);
            json.addProperty("multiplier", multiplier);
            json.addProperty("ticksLeft", ticksLeft);
            json.addProperty("source", source == null ? "" : source);
            return json;
        }

        public static PriceEvent fromJson(JsonObject json) {
            PriceEvent event = new PriceEvent();
            event.target = JsonFile.str(json, "target", "");
            event.multiplier = JsonFile.dbl(json, "multiplier", 1.0);
            event.ticksLeft = JsonFile.intVal(json, "ticksLeft", 0);
            event.source = JsonFile.str(json, "source", "");
            return event;
        }
    }

    private final Path dataFile;
    private final Path catalogFile;
    private final Map<String, CatalogItem> catalog = new LinkedHashMap<>();
    private final Map<String, Long> prices = new LinkedHashMap<>();
    private final List<PriceEvent> events = new ArrayList<>();
    private final Map<Long, Long> volumeByDay = new TreeMap<>();
    private final Map<Long, Long> cpiByDay = new TreeMap<>();

    /** 本 tick 的净买入额（分）：TradeService 记，PriceEngine 读，用来算冲击。 */
    private final Map<String, Long> tickImpact = new LinkedHashMap<>();

    private long makerCents;
    private long feePoolCents;

    private final BalanceAccount maker = new BalanceAccount() {
        @Override
        public String accountId() {
            return Ledger.MAKER;
        }

        @Override
        public long balanceCents() {
            return makerCents;
        }

        @Override
        public void addBalanceCents(long delta) {
            makerCents = Math.addExact(makerCents, delta);
        }
    };

    private final BalanceAccount feePool = new BalanceAccount() {
        @Override
        public String accountId() {
            return Ledger.FEE_POOL;
        }

        @Override
        public long balanceCents() {
            return feePoolCents;
        }

        @Override
        public void addBalanceCents(long delta) {
            feePoolCents = Math.addExact(feePoolCents, delta);
        }
    };

    public MarketStore(Path dataFile, Path catalogFile) {
        this.dataFile = dataFile;
        this.catalogFile = catalogFile;
    }

    // ── 加载/保存 ──

    public void load() {
        loadCatalog();
        JsonObject root = JsonFile.readObject(dataFile);
        if (root != null) {
            JsonObject priceJson = JsonFile.object(root, "prices");
            for (Map.Entry<String, JsonElement> entry : priceJson.entrySet()) {
                if (!catalog.containsKey(entry.getKey())) {
                    continue;
                }
                try {
                    long value = entry.getValue().getAsLong();
                    if (value > 0L) {
                        prices.put(entry.getKey(), value);
                    }
                } catch (RuntimeException ignored) {
                    // 坏数据跳过，用 basePrice
                }
            }
            for (JsonElement element : JsonFile.array(root, "events")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                PriceEvent event = PriceEvent.fromJson(element.getAsJsonObject());
                if (!event.target.isEmpty() && event.ticksLeft > 0) {
                    events.add(event);
                }
            }
            makerCents = JsonFile.lng(root, "makerCents", 0L);
            feePoolCents = JsonFile.lng(root, "feePoolCents", 0L);
            for (Map.Entry<String, JsonElement> entry : JsonFile.object(root, "volumeByDay").entrySet()) {
                try {
                    volumeByDay.put(Long.parseLong(entry.getKey()), entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            for (Map.Entry<String, JsonElement> entry : JsonFile.object(root, "cpiByDay").entrySet()) {
                try {
                    cpiByDay.put(Long.parseLong(entry.getKey()), entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
        }
        // 补齐目录里新增的物品价格
        for (CatalogItem item : catalog.values()) {
            prices.putIfAbsent(item.id, item.basePriceCents);
        }
        Log.info("商品市场加载完成：目录 " + catalog.size() + " 项");
    }

    /** 目录文件不存在时从 jar 内置资源复制一份出来。 */
    private void loadCatalog() {
        catalog.clear();
        if (!Files.isRegularFile(catalogFile)) {
            try (InputStream in = MarketStore.class.getResourceAsStream("/aimcmarket/catalog.json")) {
                if (in != null) {
                    Files.createDirectories(catalogFile.getParent());
                    Files.writeString(catalogFile, new String(in.readAllBytes(), StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8);
                    Log.info("已生成默认商品目录：" + catalogFile);
                } else {
                    Log.warn("找不到内置商品目录资源 /aimcmarket/catalog.json");
                }
            } catch (IOException error) {
                Log.warn("生成默认商品目录失败", error);
            }
        }
        JsonObject root = JsonFile.readObject(catalogFile);
        if (root == null) {
            return;
        }
        for (JsonElement element : JsonFile.array(root, "items")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String id = JsonFile.str(item, "id", "").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || catalog.containsKey(id)) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (JsonElement alias : JsonFile.array(item, "aliases")) {
                try {
                    String text = alias.getAsString().trim().toLowerCase(Locale.ROOT);
                    if (!text.isEmpty()) {
                        aliases.add(text);
                    }
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            String nameZh = JsonFile.str(item, "name_zh", "");
            if (!nameZh.isEmpty()) {
                aliases.add(nameZh.toLowerCase(Locale.ROOT));
            }
            long basePrice = Math.max(1L, JsonFile.lng(item, "basePrice", 100L));
            catalog.put(id, new CatalogItem(
                    id,
                    nameZh,
                    aliases,
                    basePrice,
                    Cents.clampDouble(JsonFile.dbl(item, "volatility", 0.02), 0.0005, 0.20),
                    Math.max(1000L, JsonFile.lng(item, "liquidity", 8000L)),
                    JsonFile.bool(item, "enabled", true)));
        }
        if (catalog.isEmpty()) {
            Log.warn("商品目录为空，市场功能会拒绝所有买卖（检查 " + catalogFile + "）");
        }
    }

    /** /ex admin reload：重新读目录，保留已有价格。 */
    public void reloadCatalog() {
        loadCatalog();
        for (CatalogItem item : catalog.values()) {
            prices.putIfAbsent(item.id, item.basePriceCents);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonObject priceJson = new JsonObject();
        for (Map.Entry<String, Long> entry : prices.entrySet()) {
            priceJson.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("prices", priceJson);
        JsonArray eventArray = new JsonArray();
        for (PriceEvent event : events) {
            eventArray.add(event.toJson());
        }
        root.add("events", eventArray);
        root.addProperty("makerCents", makerCents);
        root.addProperty("feePoolCents", feePoolCents);
        JsonObject volumeJson = new JsonObject();
        for (Map.Entry<Long, Long> entry : volumeByDay.entrySet()) {
            volumeJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        root.add("volumeByDay", volumeJson);
        JsonObject cpiJson = new JsonObject();
        for (Map.Entry<Long, Long> entry : cpiByDay.entrySet()) {
            cpiJson.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        root.add("cpiByDay", cpiJson);
        try {
            JsonFile.write(dataFile, root);
        } catch (IOException error) {
            Log.warn("保存 market.json 失败（下个 tick 重试）", error);
        }
    }

    // ── 目录与价格查询 ──

    public Collection<CatalogItem> items() {
        return catalog.values();
    }

    public CatalogItem item(String id) {
        if (id == null) {
            return null;
        }
        return catalog.get(id.toLowerCase(Locale.ROOT));
    }

    /** 按 id、英文别名、中文名找物品（最长匹配优先）。找不到返回 null。 */
    public CatalogItem resolve(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String needle = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        CatalogItem byId = catalog.get(needle);
        if (byId != null) {
            return byId;
        }
        CatalogItem byIdRaw = catalog.get(text.trim().toLowerCase(Locale.ROOT));
        if (byIdRaw != null) {
            return byIdRaw;
        }
        CatalogItem best = null;
        int bestLength = 0;
        for (CatalogItem item : catalog.values()) {
            if (item.nameZh.equalsIgnoreCase(text.trim())) {
                return item;
            }
            for (String alias : item.aliases) {
                if (alias.equalsIgnoreCase(text.trim())) {
                    return item;
                }
            }
            // 模糊：物品别名是输入的子串（"买 3 个铁锭" 里抠出 "铁锭" 的场景由本地解析器负责，
            // 这里只做后缀/包含匹配，且最长的优先，避免 "金锭" 命中 "金块"）
            for (String alias : item.aliases) {
                if (alias.length() >= 2 && text.toLowerCase(Locale.ROOT).contains(alias) && alias.length() > bestLength) {
                    best = item;
                    bestLength = alias.length();
                }
            }
        }
        return best;
    }

    public List<CatalogItem> enabledItems() {
        List<CatalogItem> result = new ArrayList<>();
        for (CatalogItem item : catalog.values()) {
            if (item.enabled) {
                result.add(item);
            }
        }
        return result;
    }

    public long price(String id) {
        Long value = prices.get(id);
        return value == null ? 0L : value;
    }

    public void setPrice(String id, long cents) {
        CatalogItem item = item(id);
        if (item == null) {
            return;
        }
        prices.put(item.id, Cents.clamp(cents, 1L, item.basePriceCents * 100L));
    }

    /** 原始卖价（ask，含点差，不含政策补贴/关税乘数）。 */
    public long askRawCents(String id, double askSpread) {
        long price = price(id);
        return Cents.percent(price, 1.0 + askSpread);
    }

    /** 原始回收价（bid，不含政策乘数）。 */
    public long bidRawCents(String id, double bidRatio) {
        long price = price(id);
        return Cents.percent(price, bidRatio);
    }

    /** 所有活跃事件对目标的乘数（没有事件返回 1.0，夹在 [0.70, 1.30]）。 */
    public double eventMultiplier(String target) {
        double multiplier = 1.0;
        String needle = target == null ? "" : target.toLowerCase(Locale.ROOT);
        for (PriceEvent event : events) {
            if (event.target.equalsIgnoreCase(needle)) {
                multiplier *= event.multiplier;
            }
        }
        return Cents.clampDouble(multiplier, 0.70, 1.30);
    }

    public void applyEvent(String target, double multiplier, int ticks, String source) {
        if (target == null || target.isBlank() || ticks <= 0) {
            return;
        }
        PriceEvent event = new PriceEvent();
        event.target = target;
        event.multiplier = Cents.clampDouble(multiplier, 0.70, 1.30);
        event.ticksLeft = ticks;
        event.source = source;
        events.add(event);
    }

    /** 每个 market tick 调用：事件剩余 tick 减一。 */
    public void tickEvents() {
        events.removeIf(event -> --event.ticksLeft <= 0);
    }

    public List<PriceEvent> activeEvents() {
        return events;
    }

    // ── 成交额 / CPI ──

    public void recordVolume(long tradingDay, long cents) {
        if (cents <= 0L) {
            return;
        }
        volumeByDay.merge(tradingDay, cents, Math::addExact);
        long cutoff = tradingDay - 30L;
        volumeByDay.keySet().removeIf(day -> day < cutoff);
    }

    /** 最近 7 个交易日的成交额（分）。 */
    public long volume7Days(long today) {
        long sum = 0L;
        for (Map.Entry<Long, Long> entry : volumeByDay.entrySet()) {
            if (entry.getKey() > today - 7L && entry.getKey() <= today) {
                sum = Math.addExact(sum, entry.getValue());
            }
        }
        return sum;
    }

    /** 以流动性为权重的价格指数（10000 = 1.0）。每个交易日快照一次。 */
    public long snapshotCpi(long tradingDay) {
        long weighted = 0L;
        long weights = 0L;
        for (CatalogItem item : catalog.values()) {
            long price = price(item.id);
            long base = item.basePriceCents;
            if (price <= 0L || base <= 0L) {
                continue;
            }
            long weight = item.liquidity;
            // price / base 用万分比表示，避免浮点
            long index = Cents.mulDiv(price, 10_000L, base);
            weighted = Math.addExact(weighted, Cents.mulDiv(index, weight, 1L));
            weights = Math.addExact(weights, weight);
        }
        long cpi = weights <= 0L ? 10_000L : weighted / weights;
        cpiByDay.put(tradingDay, cpi);
        long cutoff = tradingDay - 30L;
        cpiByDay.keySet().removeIf(day -> day < cutoff);
        return cpi;
    }

    public long cpiAt(long tradingDay) {
        return cpiByDay.getOrDefault(tradingDay, 10_000L);
    }

    /** 7 个交易日的通胀率（0.02 = 2%）。数据不足时返回 0。 */
    public double inflation7d(long today) {
        Long now = cpiByDay.get(today);
        Long before = cpiByDay.get(today - 7L);
        if (now == null || before == null || before == 0L) {
            return 0.0;
        }
        return (double) now / (double) before - 1.0;
    }

    public long makerCents() {
        return makerCents;
    }

    public long feePoolCents() {
        return feePoolCents;
    }

    public BalanceAccount makerAccount() {
        return maker;
    }

    public BalanceAccount feeAccount() {
        return feePool;
    }

    /** 股票代码 → 本 tick 净买入额（分）。 */
    private final Map<String, Long> stockTickImpact = new LinkedHashMap<>();

    /** 记一笔本 tick 的净买入额（分，正数=净买入，负数=净卖出），PriceEngine 读它算冲击。 */
    public void addImpact(String itemId, long netBuyValueCents) {
        if (itemId == null || netBuyValueCents == 0L) {
            return;
        }
        tickImpact.merge(itemId, netBuyValueCents, Math::addExact);
    }

    /** 取走并清空本 tick 的商品冲击数据。 */
    public Map<String, Long> takeImpacts() {
        Map<String, Long> copy = new LinkedHashMap<>(tickImpact);
        tickImpact.clear();
        return copy;
    }

    /** 记一笔本 tick 的股票净买入额（分）。 */
    public void addStockImpact(String code, long netBuyValueCents) {
        if (code == null || netBuyValueCents == 0L) {
            return;
        }
        stockTickImpact.merge(code.toUpperCase(Locale.ROOT), netBuyValueCents, Math::addExact);
    }

    /** 取走并清空本 tick 的股票冲击数据。 */
    public Map<String, Long> takeStockImpacts() {
        Map<String, Long> copy = new LinkedHashMap<>(stockTickImpact);
        stockTickImpact.clear();
        return copy;
    }

    public long marketMakerPoolCents() {
        return makerCents;
    }

    public Path dataFile() {
        return dataFile;
    }

    public Path catalogFile() {
        return catalogFile;
    }

    /** 市场总流动性（政策 liquidity 用）。 */
    public long totalLiquidity() {
        long sum = 0L;
        for (CatalogItem item : catalog.values()) {
            sum = Math.addExact(sum, item.liquidity);
        }
        return sum;
    }
}
