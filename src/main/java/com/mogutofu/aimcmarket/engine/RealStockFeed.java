package com.mogutofu.aimcmarket.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现实股票行情：从腾讯行情接口（qt.gtimg.cn）拉 A 股 / 美股实时价格。
 *
 * <p>只做"行情 → 游戏股价"的映射，纯 Java 不依赖 Minecraft，单测可直接调
 * {@link #parseTencent(String)}。配置写在 {@code config/aimcmarket/real-stocks.json}，
 * 默认给几只美股和大 A 的知名股票；删掉/加行即可增减，重启或 /ex admin syncstocks 生效。</p>
 *
 * <p>接口返回 GBK 文本，形如：{@code v_sh600519="1~贵州茅台~600519~1485.75~1460.21~..."}</p>
 */
public final class RealStockFeed {
    private static final Charset GBK = Charset.forName("GBK");
    private static final String ENDPOINT = "https://qt.gtimg.cn/q=";
    private static final int TIMEOUT_MS = 8_000;

    /** 一只现实股票配置。 */
    public static final class Spec {
        public final String code;
        public final String symbol;
        public final String nameZh;
        public final String sector;
        public final String market;
        public final double multiplier;

        Spec(String code, String symbol, String nameZh, String sector, String market, double multiplier) {
            this.code = code;
            this.symbol = symbol;
            this.nameZh = nameZh;
            this.sector = sector;
            this.market = market;
            this.multiplier = multiplier;
        }
    }

    /** 一条实时行情。 */
    public static final class Quote {
        public final String symbol;
        public final String realName;
        public final double price;
        public final double prevClose;

        Quote(String symbol, String realName, double price, double prevClose) {
            this.symbol = symbol;
            this.realName = realName;
            this.price = price;
            this.prevClose = prevClose;
        }

        public double changeRatio() {
            return prevClose <= 0.0 ? 0.0 : price / prevClose - 1.0;
        }
    }

    /** 配置加载结果。 */
    public static final class Config {
        public final boolean enabled;
        public final int intervalMinutes;
        public final List<Spec> specs;

        Config(boolean enabled, int intervalMinutes, List<Spec> specs) {
            this.enabled = enabled;
            this.intervalMinutes = intervalMinutes;
            this.specs = specs;
        }
    }

    private RealStockFeed() {
    }

    /** 读取配置；文件不存在时写一份默认配置（美股 + 大 A 知名股票）。 */
    public static Config loadConfig(Path configDir) {
        Path file = configDir.resolve("real-stocks.json");
        if (!Files.isRegularFile(file)) {
            writeDefault(file);
        }
        List<Spec> specs = new ArrayList<>();
        boolean enabled = true;
        int intervalMinutes = 5;
        try {
            JsonObject root = JsonFile.readObject(file);
            if (root != null) {
                enabled = JsonFile.bool(root, "enabled", true);
                intervalMinutes = Math.max(1, Math.min(60, JsonFile.intVal(root, "intervalMinutes", 5)));
                double defaultMultiplier = JsonFile.dbl(root, "defaultMultiplier", 100.0);
                for (JsonElement element : JsonFile.array(root, "stocks")) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject one = element.getAsJsonObject();
                    String code = JsonFile.str(one, "code", "").trim().toUpperCase(java.util.Locale.ROOT);
                    String symbol = JsonFile.str(one, "symbol", "").trim();
                    if (code.isEmpty() || symbol.isEmpty()) {
                        continue;
                    }
                    String name = JsonFile.str(one, "name", code);
                    String sector = JsonFile.str(one, "sector", "科技");
                    String market = JsonFile.str(one, "market", symbol.startsWith("us") ? "US" : "CN");
                    double multiplier = JsonFile.dbl(one, "multiplier", defaultMultiplier);
                    if (!(multiplier > 0.0)) {
                        multiplier = defaultMultiplier;
                    }
                    specs.add(new Spec(code, symbol, name, sector, market, multiplier));
                }
            }
        } catch (RuntimeException error) {
            Log.warn("读取 real-stocks.json 失败：" + error.getMessage());
        }
        return new Config(enabled, intervalMinutes, specs);
    }

    private static void writeDefault(Path file) {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", true);
        root.addProperty("intervalMinutes", 5);
        root.addProperty("defaultMultiplier", 100.0);
        root.addProperty("note", "现实股票同步：symbol 用腾讯行情代码（美股 usAAPL，A股 sh600519/sz300750）。"
                + "multiplier=100 表示现实 1 块钱对应游戏 1 币；改完重启或 /ex admin syncstocks 生效。");
        JsonArray stocks = new JsonArray();
        addDefault(stocks, "AAPL", "usAAPL", "苹果", "科技", "US");
        addDefault(stocks, "MSFT", "usMSFT", "微软", "科技", "US");
        addDefault(stocks, "NVDA", "usNVDA", "英伟达", "半导体", "US");
        addDefault(stocks, "TSLA", "usTSLA", "特斯拉", "汽车", "US");
        addDefault(stocks, "GOOG", "usGOOGL", "谷歌", "互联网", "US");
        addDefault(stocks, "AMZN", "usAMZN", "亚马逊", "电商", "US");
        addDefault(stocks, "META", "usMETA", "Meta", "社交", "US");
        addDefault(stocks, "GZMT", "sh600519", "贵州茅台", "消费", "CN");
        addDefault(stocks, "CATL", "sz300750", "宁德时代", "新能源", "CN");
        addDefault(stocks, "BYD", "sz002594", "比亚迪", "汽车", "CN");
        addDefault(stocks, "CMB", "sh600036", "招商银行", "金融", "CN");
        addDefault(stocks, "PAIC", "sh601318", "中国平安", "保险", "CN");
        addDefault(stocks, "AMD", "usAMD", "超威半导体", "半导体", "US");
        addDefault(stocks, "TSM", "usTSM", "台积电", "半导体", "US");
        addDefault(stocks, "ASML", "usASML", "阿斯麦", "半导体", "US");
        addDefault(stocks, "LLY", "usLLY", "礼来", "医疗", "US");
        root.add("stocks", stocks);
        try {
            Files.createDirectories(file.getParent());
            JsonFile.write(file, root);
            Log.info("已生成现实股票配置：" + file);
        } catch (IOException error) {
            Log.warn("写 real-stocks.json 失败：" + error.getMessage());
        }
    }

    private static void addDefault(JsonArray stocks, String code, String symbol, String name, String sector, String market) {
        JsonObject one = new JsonObject();
        one.addProperty("code", code);
        one.addProperty("symbol", symbol);
        one.addProperty("name", name);
        one.addProperty("sector", sector);
        one.addProperty("market", market);
        one.addProperty("multiplier", 100.0);
        stocks.add(one);
    }

    /** 批量拉行情：一次 HTTP 拿所有 symbol，返回 symbol → Quote。失败抛 IOException。 */
    public static Map<String, Quote> fetch(List<String> symbols) throws IOException {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        String url = ENDPOINT + String.join(",", symbols);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 aimcmarket");
        String body;
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 512 * 1024) {
                    break;
                }
            }
            body = buffer.toString(GBK);
        } finally {
            connection.disconnect();
        }
        return parseTencent(body);
    }

    /** 解析腾讯行情文本（公开 static 方便单测）。 */
    public static Map<String, Quote> parseTencent(String body) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        if (body == null) {
            return quotes;
        }
        for (String line : body.split("[;\\r\\n]+")) {
            int marker = line.indexOf("v_");
            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (marker < 0 || start < 0 || end <= start) {
                continue;
            }
            String symbol = line.substring(marker + 2, start).trim();
            // 去掉 "=" 等杂字符
            symbol = symbol.replace("=", "").trim();
            String[] fields = line.substring(start + 1, end).split("~");
            if (fields.length < 5) {
                continue;
            }
            try {
                String realName = fields[1].trim();
                double price = Double.parseDouble(fields[3].trim());
                double prevClose = Double.parseDouble(fields[4].trim());
                if (!(price > 0.0) || Double.isNaN(price) || Double.isInfinite(price)) {
                    continue;
                }
                quotes.put(symbol, new Quote(symbol, realName, price, prevClose));
            } catch (RuntimeException ignored) {
                // 单行解析失败跳过
            }
        }
        return quotes;
    }
}
