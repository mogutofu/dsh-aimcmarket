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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 期货合约与持仓。
 *
 * <p>标的：铁锭、金锭、钻石、绿宝石、小麦；1 手 = 64 个物品，现金交割。
 * 合约价格 {@code F = S × (1 + carry × daysLeft/365)}，到期时 {@code F = S}。</p>
 */
public final class FuturesStore {
    /** 标的代码 → 商品目录 id。 */
    public static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    public static final int LOT_SIZE = 64;
    /** 可选到期天数（交易日）。 */
    public static final int[] EXPIRY_DAYS = {7, 14, 30};

    static {
        SYMBOLS.put("IRON", "minecraft:iron_ingot");
        SYMBOLS.put("GOLD", "minecraft:gold_ingot");
        SYMBOLS.put("DIAMOND", "minecraft:diamond");
        SYMBOLS.put("EMERALD", "minecraft:emerald");
        SYMBOLS.put("WHEAT", "minecraft:wheat");
    }

    /** 一笔持仓。 */
    public static final class FuturePosition {
        public String id;
        public String uuid;
        public String playerName;
        public String symbol;
        public boolean longPosition = true;
        public long lots;
        /** 开仓价（每个物品，分）。 */
        public long entryPriceCents;
        public int expiryDays;
        /** 到期交易日。 */
        public long expiryDay;
        /** 当前权益 = 初始保证金 + 未实现盈亏 - 已扣费用。 */
        public long marginCents;
        public long initialMarginCents;
        /** 上次盯市价格。 */
        public long lastMarkPriceCents;
        public long openedTick;
        /** 追保通知时间（毫秒）；0 表示没被追保。 */
        public long marginCallAtMs;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("uuid", uuid);
            json.addProperty("player", playerName == null ? "" : playerName);
            json.addProperty("symbol", symbol);
            json.addProperty("long", longPosition);
            json.addProperty("lots", lots);
            json.addProperty("entryPriceCents", entryPriceCents);
            json.addProperty("expiryDays", expiryDays);
            json.addProperty("expiryDay", expiryDay);
            json.addProperty("marginCents", marginCents);
            json.addProperty("initialMarginCents", initialMarginCents);
            json.addProperty("lastMarkPriceCents", lastMarkPriceCents);
            json.addProperty("openedTick", openedTick);
            json.addProperty("marginCallAtMs", marginCallAtMs);
            return json;
        }

        public static FuturePosition fromJson(JsonObject json) {
            FuturePosition position = new FuturePosition();
            position.id = JsonFile.str(json, "id", "");
            position.uuid = JsonFile.str(json, "uuid", "");
            position.playerName = JsonFile.str(json, "player", "");
            position.symbol = JsonFile.str(json, "symbol", "").toUpperCase(Locale.ROOT);
            position.longPosition = JsonFile.bool(json, "long", true);
            position.lots = JsonFile.lng(json, "lots", 0L);
            position.entryPriceCents = JsonFile.lng(json, "entryPriceCents", 0L);
            position.expiryDays = JsonFile.intVal(json, "expiryDays", 7);
            position.expiryDay = JsonFile.lng(json, "expiryDay", 0L);
            position.marginCents = JsonFile.lng(json, "marginCents", 0L);
            position.initialMarginCents = JsonFile.lng(json, "initialMarginCents", 0L);
            position.lastMarkPriceCents = JsonFile.lng(json, "lastMarkPriceCents", position.entryPriceCents);
            position.openedTick = JsonFile.lng(json, "openedTick", 0L);
            position.marginCallAtMs = JsonFile.lng(json, "marginCallAtMs", 0L);
            return position;
        }

        /** 名义价值（分）。 */
        public long notionalCents(long priceCents) {
            return Cents.mul(Cents.mul(priceCents, LOT_SIZE), lots);
        }

        /** 相对开仓价的未实现盈亏（分）。 */
        public long pnlCents(long markPriceCents) {
            long diff = Math.subtractExact(markPriceCents, entryPriceCents);
            long value = Cents.mul(Cents.mul(diff, LOT_SIZE), lots);
            return longPosition ? value : -value;
        }
    }

    private final Path file;
    private final List<FuturePosition> positions = new ArrayList<>();
    private long nextId = 1L;

    public FuturesStore(Path file) {
        this.file = file;
    }

    public void load() {
        positions.clear();
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            for (JsonElement element : JsonFile.array(root, "positions")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                FuturePosition position = FuturePosition.fromJson(element.getAsJsonObject());
                if (!position.id.isEmpty() && position.lots > 0L && SYMBOLS.containsKey(position.symbol)) {
                    positions.add(position);
                }
            }
            nextId = JsonFile.lng(root, "nextId", 1L);
        }
        for (FuturePosition position : positions) {
            String digits = position.id.replaceAll("[^0-9]", "");
            try {
                long parsed = Long.parseLong(digits);
                if (parsed >= nextId) {
                    nextId = parsed + 1L;
                }
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
        Log.info("期货加载完成：" + positions.size() + " 笔持仓");
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("nextId", nextId);
        JsonArray array = new JsonArray();
        for (FuturePosition position : positions) {
            array.add(position.toJson());
        }
        root.add("positions", array);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 futures.json 失败（下个 tick 重试）", error);
        }
    }

    public List<FuturePosition> all() {
        return positions;
    }

    public List<FuturePosition> of(String uuid) {
        List<FuturePosition> result = new ArrayList<>();
        for (FuturePosition position : positions) {
            if (position.uuid.equals(uuid)) {
                result.add(position);
            }
        }
        return result;
    }

    public FuturePosition find(String id) {
        for (FuturePosition position : positions) {
            if (position.id.equalsIgnoreCase(id)) {
                return position;
            }
        }
        return null;
    }

    public FuturePosition add(FuturePosition position) {
        if (position.id == null || position.id.isEmpty()) {
            position.id = String.format(Locale.ROOT, "f-%05d", nextId++);
        }
        positions.add(position);
        return position;
    }

    public void remove(FuturePosition position) {
        positions.remove(position);
    }

    /** 合约价格：F = S × (1 + carry × daysLeft/365)。 */
    public static long contractPrice(long spotCents, long daysLeft, double carry) {
        double factor = 1.0 + carry * Math.max(0L, daysLeft) / 365.0;
        return Math.max(1L, Math.round(spotCents * factor));
    }

    public Path file() {
        return file;
    }
}
