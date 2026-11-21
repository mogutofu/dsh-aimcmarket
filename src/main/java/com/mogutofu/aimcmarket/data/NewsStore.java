package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 新闻：市场先生写的市场新闻、财报摘要、政策公告、IPO/破产快讯。
 *
 * <p>新闻本身只是文本 + 影响列表（{@code effects}），真正改价格的是 PriceEngine 读这些 effects。</p>
 */
public final class NewsStore {
    public static final class NewsItem {
        public String id = "";
        public String headline = "";
        public String body = "";
        /** market | company | policy | ipo | bankrupt */
        public String category = "market";
        public long atMs;
        public long day;
        /** 影响列表：[{"target":"minecraft:iron_ingot","multiplier":1.08,"ticks":6}] */
        public JsonArray effects = new JsonArray();

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("headline", headline);
            json.addProperty("body", body);
            json.addProperty("category", category);
            json.addProperty("at", atMs);
            json.addProperty("day", day);
            json.add("effects", effects);
            return json;
        }

        public static NewsItem fromJson(JsonObject json) {
            NewsItem item = new NewsItem();
            item.id = JsonFile.str(json, "id", "");
            item.headline = JsonFile.str(json, "headline", "");
            item.body = JsonFile.str(json, "body", "");
            item.category = JsonFile.str(json, "category", "market");
            item.atMs = JsonFile.lng(json, "at", 0L);
            item.day = JsonFile.lng(json, "day", 0L);
            item.effects = JsonFile.array(json, "effects");
            return item;
        }
    }

    private final Path file;
    private final List<NewsItem> items = new ArrayList<>();
    private long nextId = 1L;
    private long lastGeneratedAtMs;

    public NewsStore(Path file) {
        this.file = file;
    }

    public void load() {
        items.clear();
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            for (JsonElement element : JsonFile.array(root, "items")) {
                if (element.isJsonObject()) {
                    NewsItem item = NewsItem.fromJson(element.getAsJsonObject());
                    if (!item.headline.isEmpty()) {
                        items.add(item);
                    }
                }
            }
            nextId = JsonFile.lng(root, "nextId", 1L);
            lastGeneratedAtMs = JsonFile.lng(root, "lastGeneratedAtMs", 0L);
        }
        Log.info("新闻加载完成：" + items.size() + " 条");
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("nextId", nextId);
        root.addProperty("lastGeneratedAtMs", lastGeneratedAtMs);
        JsonArray array = new JsonArray();
        for (NewsItem item : items) {
            array.add(item.toJson());
        }
        root.add("items", array);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 news.json 失败（下个 tick 重试）", error);
        }
    }

    public NewsItem add(String headline, String body, String category, long nowMs, long day, JsonArray effects) {
        NewsItem item = new NewsItem();
        item.id = String.format(Locale.ROOT, "n-%05d", nextId++);
        item.headline = headline == null ? "" : headline;
        item.body = body == null ? "" : body;
        item.category = category == null ? "market" : category;
        item.atMs = nowMs;
        item.day = day;
        item.effects = effects == null ? new JsonArray() : effects;
        items.add(item);
        while (items.size() > 100) {
            items.remove(0);
        }
        return item;
    }

    public List<NewsItem> recent(int limit) {
        List<NewsItem> result = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0 && result.size() < limit; i--) {
            result.add(items.get(i));
        }
        return result;
    }

    public List<NewsItem> all() {
        return items;
    }

    public NewsItem latest() {
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    public long lastGeneratedAtMs() {
        return lastGeneratedAtMs;
    }

    public void markGenerated(long nowMs) {
        lastGeneratedAtMs = nowMs;
    }

    public Path file() {
        return file;
    }
}
