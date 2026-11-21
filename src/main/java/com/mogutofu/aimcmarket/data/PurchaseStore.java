package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 玩家买入记录（退货用）。
 *
 * <p>每次在市场买入商品都记一条：物品 id、数量、单价、时间。玩家退货时按 FIFO 退，
 * 退款额 = 当时买入的单价 × 数量；只允许退 7 天内买的、且物品还在背包里的部分。</p>
 *
 * <p>纯 Java + Gson，不 import Minecraft，单测可直接用。</p>
 */
public final class PurchaseStore {
    /** 退货窗口：7 天。 */
    public static final long RETURN_WINDOW_MS = 7L * 24L * 3600L * 1000L;
    /** 每个玩家最多保留多少条买入记录（超出丢最旧的）。 */
    private static final int MAX_RECORDS_PER_PLAYER = 300;

    public static final class Record {
        public final String itemId;
        public int count;
        public final long unitCents;
        public final long atMs;

        Record(String itemId, int count, long unitCents, long atMs) {
            this.itemId = itemId;
            this.count = count;
            this.unitCents = unitCents;
            this.atMs = atMs;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("item", itemId);
            json.addProperty("count", count);
            json.addProperty("unitCents", unitCents);
            json.addProperty("at", atMs);
            return json;
        }

        static Record fromJson(JsonObject json) {
            if (json == null) {
                return null;
            }
            String item = JsonFile.str(json, "item", "");
            int count = JsonFile.intVal(json, "count", 0);
            long unit = JsonFile.lng(json, "unitCents", 0L);
            long at = JsonFile.lng(json, "at", 0L);
            if (item.isBlank() || count <= 0 || unit <= 0L || at <= 0L) {
                return null;
            }
            return new Record(item, count, unit, at);
        }
    }

    private final Path file;
    private final Map<String, List<Record>> byPlayer = new HashMap<>();

    public PurchaseStore(Path file) {
        this.file = file;
    }

    public void load() {
        try {
            JsonObject root = JsonFile.readObject(file);
            if (root == null) {
                return;
            }
            JsonObject players = JsonFile.object(root, "players");
            if (players == null) {
                return;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry : players.entrySet()) {
                if (entry.getValue() == null || !entry.getValue().isJsonArray()) {
                    continue;
                }
                List<Record> records = new ArrayList<>();
                for (com.google.gson.JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (element != null && element.isJsonObject()) {
                        Record record = Record.fromJson(element.getAsJsonObject());
                        if (record != null) {
                            records.add(record);
                        }
                    }
                }
                if (!records.isEmpty()) {
                    byPlayer.put(entry.getKey(), records);
                }
            }
        } catch (RuntimeException error) {
            Log.warn("读取 purchases.json 失败，按空记录处理", error);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        JsonObject players = new JsonObject();
        for (Map.Entry<String, List<Record>> entry : byPlayer.entrySet()) {
            JsonArray array = new JsonArray();
            for (Record record : entry.getValue()) {
                array.add(record.toJson());
            }
            players.add(entry.getKey(), array);
        }
        root.add("players", players);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 purchases.json 失败（下个 tick 重试）", error);
        }
    }

    /** 记一条买入。 */
    public synchronized void record(String playerUuid, String itemId, int count, long unitCents, long nowMs) {
        if (playerUuid == null || itemId == null || count <= 0 || unitCents <= 0L) {
            return;
        }
        List<Record> records = byPlayer.computeIfAbsent(playerUuid, key -> new ArrayList<>());
        mergeOrAppend(records, itemId, count, unitCents, nowMs);
        while (records.size() > MAX_RECORDS_PER_PLAYER) {
            records.remove(0);
        }
    }

    /** 同物品同价格 1 分钟内的相邻买入合并，避免记录爆炸。 */
    private static void mergeOrAppend(List<Record> records, String itemId, int count, long unitCents, long nowMs) {
        int size = records.size();
        if (size > 0) {
            Record last = records.get(size - 1);
            if (last.itemId.equals(itemId) && last.unitCents == unitCents
                    && nowMs - last.atMs <= 60_000L) {
                last.count += count;
                return;
            }
        }
        records.add(new Record(itemId, count, unitCents, nowMs));
    }

    /** 7 天内可退的数量。 */
    public synchronized int returnable(String playerUuid, String itemId, long nowMs) {
        List<Record> records = byPlayer.get(playerUuid);
        if (records == null || itemId == null) {
            return 0;
        }
        int total = 0;
        for (Record record : records) {
            if (!record.itemId.equals(itemId)) {
                continue;
            }
            if (nowMs - record.atMs > RETURN_WINDOW_MS || record.atMs > nowMs) {
                continue;
            }
            total += record.count;
        }
        return total;
    }

    /**
     * 退货：从最早的有效买入记录里扣掉 count 个，返回应退总额（分）。
     * 实际可退数量以 {@link #returnable} 为准；这里只退存在的部分。
     */
    public synchronized long refund(String playerUuid, String itemId, int count, long nowMs) {
        List<Record> records = byPlayer.get(playerUuid);
        if (records == null || itemId == null || count <= 0) {
            return 0L;
        }
        int remaining = count;
        long total = 0L;
        for (Iterator<Record> iterator = records.iterator(); iterator.hasNext() && remaining > 0; ) {
            Record record = iterator.next();
            if (!record.itemId.equals(itemId)) {
                continue;
            }
            if (nowMs - record.atMs > RETURN_WINDOW_MS || record.atMs > nowMs) {
                continue;
            }
            int used = Math.min(record.count, remaining);
            record.count -= used;
            remaining -= used;
            total += record.unitCents * used;
            if (record.count <= 0) {
                iterator.remove();
            }
        }
        if (records.isEmpty()) {
            byPlayer.remove(playerUuid);
        }
        return total;
    }
}
