package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.engine.RealStockFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealStockFeedTest {

    private static final String SAMPLE = "v_sh600519=\"1~贵州茅台~600519~1485.75~1460.21~1450.89~67229~37048~30181~1485.75~2342\";\r\n"
            + "v_usAAPL=\"200~苹果~AAPL.OQ~342.56~349.21~352.87~80692545~0~0~344.80~360\";\r\n";

    @Test
    void parsesCnAndUsQuotes() {
        var quotes = RealStockFeed.parseTencent(SAMPLE);
        assertEquals(2, quotes.size(), "应解析出两条行情");
        RealStockFeed.Quote cn = quotes.get("sh600519");
        assertNotNull(cn, "应有贵州茅台");
        assertEquals(1485.75, cn.price, 1e-6, "现价");
        assertEquals(1460.21, cn.prevClose, 1e-6, "昨收");
        assertTrue(cn.changeRatio() > 0.01, "涨跌幅应为正");

        RealStockFeed.Quote us = quotes.get("usAAPL");
        assertNotNull(us, "应有苹果");
        assertEquals(342.56, us.price, 1e-6, "现价");
        assertTrue(us.changeRatio() < 0.0, "苹果当天应为跌");
    }

    @Test
    void invalidLinesAreSkipped() {
        var quotes = RealStockFeed.parseTencent("v_bad=\"1~坏数据\";\r\nv_empty=\"\";\r\nv_usX=\"200~X~X.N~abc~1\";");
        assertTrue(quotes.isEmpty(), "坏行应全部跳过");
    }

    @Test
    void defaultConfigContainsUsAndCnStocks(@TempDir Path tempDir) {
        RealStockFeed.Config config = RealStockFeed.loadConfig(tempDir);
        assertTrue(config.enabled, "默认启用");
        assertTrue(config.intervalMinutes >= 1, "同步间隔至少 1 分钟");
        assertTrue(config.specs.size() >= 10, "默认应有几只美股+大A");
        boolean hasUs = config.specs.stream().anyMatch(spec -> spec.symbol.startsWith("us"));
        boolean hasCn = config.specs.stream().anyMatch(spec -> spec.symbol.startsWith("sh") || spec.symbol.startsWith("sz"));
        assertTrue(hasUs, "应有美股");
        assertTrue(hasCn, "应有大 A 股票");
        java.nio.file.Files.exists(tempDir.resolve("real-stocks.json"));
    }
}
