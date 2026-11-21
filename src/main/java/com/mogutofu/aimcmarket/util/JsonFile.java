package com.mogutofu.aimcmarket.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * JSON 读写工具：容错读 + 原子写。
 *
 * <p>规则（和 DESIGN.md 一致）：</p>
 * <ul>
 *   <li>文件不存在 → 返回 null，调用方用默认值；</li>
 *   <li>文件坏了 → 改名成 {@code xxx.json.broken-<时间戳>} 备份，返回 null，<b>绝不抛异常让服务器起不来</b>；</li>
 *   <li>写盘用"写临时文件 + 原子 move"，写失败记日志，等下一次重试。</li>
 * </ul>
 */
public final class JsonFile {
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private JsonFile() {
    }

    /** 读一个 JSON 对象；不存在或损坏返回 null。 */
    public static JsonObject readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception error) {
            quarantine(file, error);
            return null;
        }
    }

    /** 读一个 JSON 数组；不存在或损坏返回 null。 */
    public static JsonArray readArray(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        } catch (Exception error) {
            quarantine(file, error);
            return null;
        }
    }

    private static void quarantine(Path file, Exception error) {
        String stamp = Instant.now().toString().replace(':', '-').replace('.', '-');
        Path backup = file.resolveSibling(file.getFileName() + ".broken-" + stamp);
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Log.warn("数据文件损坏，已备份为 " + backup.getFileName() + "：" + error);
        } catch (IOException moveError) {
            Log.warn("数据文件损坏且备份失败（" + file + "）", moveError);
        }
    }

    /** 原子写：先写 .tmp 再 move 覆盖。 */
    public static void write(Path file, JsonElement element) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(element), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // 某些文件系统不支持 ATOMIC_MOVE，退化成 REPLACE_EXISTING。
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 追加一行（用于 ledger.jsonl）。 */
    public static void appendLine(Path file, String line) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    // ── 容错取值（缺字段/类型不对时给默认值，不抛异常）──

    public static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return new JsonObject();
        }
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static JsonArray array(JsonObject parent, String key) {
        if (parent == null) {
            return new JsonArray();
        }
        JsonElement e = parent.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static String str(JsonObject parent, String key, String def) {
        if (parent == null) {
            return def;
        }
        try {
            JsonElement e = parent.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsString();
        } catch (RuntimeException error) {
            return def;
        }
    }

    public static long lng(JsonObject parent, String key, long def) {
        if (parent == null) {
            return def;
        }
        try {
            JsonElement e = parent.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsLong();
        } catch (RuntimeException error) {
            return def;
        }
    }

    public static int intVal(JsonObject parent, String key, int def) {
        return (int) clampToInt(lng(parent, key, def));
    }

    private static long clampToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : value;
    }

    public static double dbl(JsonObject parent, String key, double def) {
        if (parent == null) {
            return def;
        }
        try {
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull()) {
                return def;
            }
            double v = e.getAsDouble();
            return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
        } catch (RuntimeException error) {
            return def;
        }
    }

    public static boolean bool(JsonObject parent, String key, boolean def) {
        if (parent == null) {
            return def;
        }
        try {
            JsonElement e = parent.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsBoolean();
        } catch (RuntimeException error) {
            return def;
        }
    }

    // ── AI 返回的 JSON 修复 ──

    /**
     * 从一段模型输出里抠出 JSON 对象：先剥 ```json 包裹，再取第一个括号配平的 {...}。
     * 配平的时候要考虑字符串里的花括号和转义。找不到返回 null。
     */
    public static JsonObject parseLooseObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripCodeFence(raw);
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = balancedEnd(text, start);
            if (end > start) {
                String candidate = text.substring(start, end + 1);
                try {
                    JsonElement parsed = JsonParser.parseString(candidate);
                    if (parsed.isJsonObject()) {
                        return parsed.getAsJsonObject();
                    }
                } catch (RuntimeException ignored) {
                    // 继续找下一个 {
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private static String stripCodeFence(String raw) {
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence < 0) {
            return text;
        }
        int afterFence = fence + 3;
        // 跳过 ```json / ```JSON
        while (afterFence < text.length() && (Character.isLetter(text.charAt(afterFence)))) {
            afterFence++;
        }
        int closing = text.indexOf("```", afterFence);
        return closing > afterFence ? text.substring(afterFence, closing) : text.substring(afterFence);
    }

    private static int balancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
