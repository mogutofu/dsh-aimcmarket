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
import java.util.List;
import java.util.Locale;

/**
 * 期权合约与持仓（欧式、现金交割）。
 *
 * <p>1 张 = 100 股；到期 7/14/30 交易日；行权价是现价 ±5%、±10% 的 3×3 网格。
 * 定价用 Black-Scholes，见 {@code engine.OptionsEngine}。</p>
 */
public final class OptionsStore {
    /** 1 张期权 = 100 股。 */
    public static final int CONTRACT_SIZE = 100;
    public static final int[] EXPIRY_DAYS = {7, 14, 30};
    /** 行权价偏移网格：-10%、-5%、0、+5%、+10%。 */
    public static final double[] STRIKE_OFFSETS = {-0.10, -0.05, 0.0, 0.05, 0.10};

    /** 一笔期权持仓。买方 {@code writer=false}，卖方 {@code writer=true}。 */
    public static final class OptionPosition {
        public String id;
        public String uuid;
        public String playerName;
        public String underlying;
        /** call | put */
        public String type = "call";
        public long strikeCents;
        public int expiryDays;
        public long expiryDay;
        public long contracts;
        /** 开仓时每张权利金（分）。 */
        public long premiumPerContractCents;
        /** 卖方保证金（分）；买方为 0。 */
        public long marginCents;
        public boolean writer;
        public long openedTick;
        public long marginCallAtMs;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("uuid", uuid);
            json.addProperty("player", playerName == null ? "" : playerName);
            json.addProperty("underlying", underlying);
            json.addProperty("type", type);
            json.addProperty("strikeCents", strikeCents);
            json.addProperty("expiryDays", expiryDays);
            json.addProperty("expiryDay", expiryDay);
            json.addProperty("contracts", contracts);
            json.addProperty("premiumPerContractCents", premiumPerContractCents);
            json.addProperty("marginCents", marginCents);
            json.addProperty("writer", writer);
            json.addProperty("openedTick", openedTick);
            json.addProperty("marginCallAtMs", marginCallAtMs);
            return json;
        }

        public static OptionPosition fromJson(JsonObject json) {
            OptionPosition position = new OptionPosition();
            position.id = JsonFile.str(json, "id", "");
            position.uuid = JsonFile.str(json, "uuid", "");
            position.playerName = JsonFile.str(json, "player", "");
            position.underlying = JsonFile.str(json, "underlying", "").toUpperCase(Locale.ROOT);
            position.type = "put".equalsIgnoreCase(JsonFile.str(json, "type", "call")) ? "put" : "call";
            position.strikeCents = JsonFile.lng(json, "strikeCents", 0L);
            position.expiryDays = JsonFile.intVal(json, "expiryDays", 7);
            position.expiryDay = JsonFile.lng(json, "expiryDay", 0L);
            position.contracts = JsonFile.lng(json, "contracts", 0L);
            position.premiumPerContractCents = JsonFile.lng(json, "premiumPerContractCents", 0L);
            position.marginCents = JsonFile.lng(json, "marginCents", 0L);
            position.writer = JsonFile.bool(json, "writer", false);
            position.openedTick = JsonFile.lng(json, "openedTick", 0L);
            position.marginCallAtMs = JsonFile.lng(json, "marginCallAtMs", 0L);
            return position;
        }

        public long notionalCents(long spotCents) {
            return Cents.mul(Cents.mul(spotCents, CONTRACT_SIZE), contracts);
        }

        /** 到期内在价值（每张，分）。 */
        public long intrinsicPerContract(long spotCents) {
            if ("call".equalsIgnoreCase(type)) {
                return Math.max(0L, Math.subtractExact(spotCents, strikeCents));
            }
            return Math.max(0L, Math.subtractExact(strikeCents, spotCents));
        }

        public long totalPremiumCents() {
            return Cents.mul(premiumPerContractCents, contracts);
        }
    }

    private final Path file;
    private final List<OptionPosition> positions = new ArrayList<>();
    private long nextId = 1L;

    public OptionsStore(Path file) {
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
                OptionPosition position = OptionPosition.fromJson(element.getAsJsonObject());
                if (!position.id.isEmpty() && position.contracts > 0L && !position.underlying.isEmpty()) {
                    positions.add(position);
                }
            }
            nextId = JsonFile.lng(root, "nextId", 1L);
        }
        for (OptionPosition position : positions) {
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
        Log.info("期权加载完成：" + positions.size() + " 笔持仓");
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("nextId", nextId);
        JsonArray array = new JsonArray();
        for (OptionPosition position : positions) {
            array.add(position.toJson());
        }
        root.add("positions", array);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 options.json 失败（下个 tick 重试）", error);
        }
    }

    public List<OptionPosition> all() {
        return positions;
    }

    public List<OptionPosition> of(String uuid) {
        List<OptionPosition> result = new ArrayList<>();
        for (OptionPosition position : positions) {
            if (position.uuid.equals(uuid)) {
                result.add(position);
            }
        }
        return result;
    }

    public OptionPosition find(String id) {
        for (OptionPosition position : positions) {
            if (position.id.equalsIgnoreCase(id)) {
                return position;
            }
        }
        return null;
    }

    public OptionPosition add(OptionPosition position) {
        if (position.id == null || position.id.isEmpty()) {
            position.id = String.format(Locale.ROOT, "o-%05d", nextId++);
        }
        positions.add(position);
        return position;
    }

    public void remove(OptionPosition position) {
        positions.remove(position);
    }

    public Path file() {
        return file;
    }
}
