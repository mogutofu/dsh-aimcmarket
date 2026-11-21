package com.mogutofu.aimcmarket.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mogutofu.aimcmarket.util.Log;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 物品解析：让 /ex 能认"任意已注册物品"，包括内置目录里没有的模组物品（例如"齿轮"）。
 *
 * <p>中文名来自各模组 jar 里的 {@code assets/<ns>/lang/zh_cn.json}（服务端本身不加载中文语言文件，
 * 所以启动后异步扫一遍 mods/*.jar 建索引）；英文名用 {@code ItemStack#getHoverName}。
 * 索引没建好时退化为按物品 id / 注册路径匹配。</p>
 */
public final class ItemResolver {
    /** 解析结果。 */
    public static final class Found {
        public final String id;
        public final String nameZh;
        public final String nameEn;

        Found(String id, String nameZh, String nameEn) {
            this.id = id;
            this.nameZh = nameZh;
            this.nameEn = nameEn;
        }

        public String displayName() {
            if (nameZh != null && !nameZh.isBlank()) {
                return nameZh;
            }
            return nameEn == null || nameEn.isBlank() ? id : nameEn;
        }
    }

    private static volatile Map<String, String> nameToId = Map.of();
    private static volatile Map<String, String> idToZh = Map.of();
    private static volatile boolean building;
    private static volatile boolean ready;

    private ItemResolver() {
    }

    /** 后台扫描 mods 目录里的中文语言文件，构建"中文名/英文名 → 物品 id"索引。 */
    public static synchronized void startBuildAsync() {
        if (building || ready) {
            return;
        }
        building = true;
        Thread thread = new Thread(ItemResolver::buildIndex, "aimcmarket-item-index");
        thread.setDaemon(true);
        thread.start();
    }

    private static void buildIndex() {
        Map<String, String> byName = new HashMap<>();
        Map<String, String> zhById = new HashMap<>();
        try {
            Path mods = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("mods");
            if (Files.isDirectory(mods)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
                    for (Path jar : stream) {
                        scanJar(jar, byName, zhById);
                    }
                }
            }
        } catch (Exception error) {
            Log.warn("扫描物品语言文件失败（不影响按 id 买卖）：" + error.getMessage());
        }
        // 服务器上的 KubeJS 自定义翻译 / 资源包也扫一遍，这些名字不在 mods jar 里
        try {
            Path root = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
            for (String dir : new String[]{"kubejs/assets", "resourcepacks"}) {
                Path base = root.resolve(dir);
                if (!Files.isDirectory(base)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> stream = Files.walk(base)) {
                    stream.filter(path -> path.toString().replace('\\', '/').endsWith("/lang/zh_cn.json")
                                    || path.toString().replace('\\', '/').endsWith("/lang/en_us.json"))
                            .forEach(path -> scanLangFile(path, byName, zhById));
                }
            }
        } catch (Exception error) {
            Log.warn("扫描 KubeJS/资源包语言文件失败：" + error.getMessage());
        }
        nameToId = byName;
        idToZh = zhById;
        ready = true;
        building = false;
        Log.info("物品名称索引完成：" + byName.size() + " 条（含中文名）");
    }

    private static void scanLangFile(Path file, Map<String, String> byName, Map<String, String> zhById) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            addLangEntries(JsonParser.parseString(text).getAsJsonObject(), byName, zhById);
        } catch (Exception ignored) {
            // 单个文件坏了跳过
        }
    }

    private static void addLangEntries(JsonObject root, Map<String, String> byName, Map<String, String> zhById) {
        for (Map.Entry<String, com.google.gson.JsonElement> pair : root.entrySet()) {
            String key = pair.getKey();
            if (!(key.startsWith("item.") || key.startsWith("block."))) {
                continue;
            }
            String rest = key.substring(key.indexOf('.') + 1);
            int dot = rest.indexOf('.');
            String id = (dot > 0 ? rest.substring(0, dot) + ":" + rest.substring(dot + 1) : rest)
                    .toLowerCase(Locale.ROOT);
            if (id.isEmpty() || !pair.getValue().isJsonPrimitive()) {
                continue;
            }
            String value = pair.getValue().getAsString();
            if (value.isBlank()) {
                continue;
            }
            zhById.putIfAbsent(id, value.trim());
            byName.putIfAbsent(normalize(value), id);
        }
    }

    private static void scanJar(Path jar, Map<String, String> byName, Map<String, String> zhById) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith("assets/") || !entryName.endsWith("/lang/zh_cn.json")) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    addLangEntries(JsonParser.parseString(text).getAsJsonObject(), byName, zhById);
                } catch (Exception ignored) {
                    // 单个文件坏了跳过
                }
            }
        } catch (Exception ignored) {
            // 单个 jar 读不了跳过
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        value = value.replace("'", "").replace("\"", "");
        return value;
    }

    /** 是否能解析。 */
    public static boolean isReady() {
        return ready;
    }

    /** 把玩家输入的文本解析成已注册物品；解析不出返回 null。 */
    public static Found resolve(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String raw = text.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        // 1) 完整 id（含命名空间）
        if (lower.contains(":")) {
            Found direct = fromId(lower);
            if (direct != null) {
                return direct;
            }
        }
        // 2) minecraft:<path>
        Found vanilla = fromId("minecraft:" + lower);
        if (vanilla != null) {
            return vanilla;
        }
        // 3) 注册路径精确匹配（例如 cogwheel → create:cogwheel）
        String path = lower.replace(' ', '_');
        for (ResourceLocation location : BuiltInRegistries.ITEM.keySet()) {
            if (location.getPath().equals(path)) {
                return fromId(location.toString());
            }
        }
        // 4) 中文名/英文名精确匹配
        Map<String, String> names = nameToId;
        String id = names.get(normalize(raw));
        if (id != null) {
            Found found = fromId(id);
            if (found != null) {
                return found;
            }
        }
        // 5) 名字包含匹配（最长优先，避免"金锭"命中"金块"）
        String bestId = null;
        int bestLength = 0;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String name = entry.getKey();
            if (name.length() >= 2 && lower.contains(name) && name.length() > bestLength) {
                bestId = entry.getValue();
                bestLength = name.length();
            }
        }
        if (bestId != null) {
            Found found = fromId(bestId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Found fromId(String id) {
        try {
            ResourceLocation location = new ResourceLocation(id);
            if (!BuiltInRegistries.ITEM.containsKey(location)) {
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(location);
            if (item == null) {
                return null;
            }
            String nameEn = new ItemStack(item).getHoverName().getString();
            String nameZh = idToZh.get(location.toString().toLowerCase(Locale.ROOT));
            return new Found(location.toString(), nameZh, nameEn);
        } catch (Exception error) {
            return null;
        }
    }

    /** 物品最大堆叠数（本地估价用）。 */
    public static int maxStackSize(String id) {
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(id));
            return item == null ? 64 : item.getMaxStackSize();
        } catch (Exception error) {
            return 64;
        }
    }

    /** 稀有度序号（0=common … 3=epic，本地估价用）。 */
    public static int rarity(String id) {
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(id));
            return item == null ? 0 : item.getRarity(new ItemStack(item)).ordinal();
        } catch (Exception error) {
            return 0;
        }
    }
}
