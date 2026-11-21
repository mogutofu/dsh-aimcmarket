package com.mogutofu.aimcmarket;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.engine.PriceEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PriceEngine} 商品/股票价格引擎的单元测试。
 *
 * <p>随机数种子固定（42），所以只断言区间、方向和不变量，不断言具体价格。</p>
 */
class PriceEngineTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final long SEED = 42L;
    private static final String IRON = "minecraft:iron_ingot";

    @TempDir
    Path tempDir;

    private AimcContext newContext() {
        return AimcContext.load(tempDir, tempDir.resolve("config"), MarketSettings.defaults(), NOW_MS, SEED);
    }

    /** 断言全部启用商品的当前价格都在 [base×0.25, base×4] 内且为正。 */
    private static void assertAllPricesInBounds(AimcContext ctx, String stage) {
        for (MarketStore.CatalogItem item : ctx.market.enabledItems()) {
            long price = ctx.market.price(item.id);
            long floor = Math.max(1L, (long) Math.ceil(item.basePriceCents * 0.25));
            long ceiling = item.basePriceCents * 4L;
            assertTrue(price > 0L, stage + "：" + item.id + " 价格必须为正，实际 " + price);
            assertTrue(price >= floor, stage + "：" + item.id + " 价格低于下边界，实际 "
                    + price + " < " + floor + "（基准价 " + item.basePriceCents + "）");
            assertTrue(price <= ceiling, stage + "：" + item.id + " 价格高于上边界，实际 "
                    + price + " > " + ceiling + "（基准价 " + item.basePriceCents + "）");
        }
    }

    @Test
    void pricesStayInBoundsFor1000Ticks() {
        AimcContext ctx = newContext();
        for (int i = 0; i < 1000; i++) {
            PriceEngine.tickPrices(ctx);
        }
        assertAllPricesInBounds(ctx, "1000 tick 后");
    }

    @Test
    void pricesDoNotDivergeAfter10000Ticks() {
        AimcContext ctx = newContext();
        for (int i = 0; i < 10_000; i++) {
            PriceEngine.tickPrices(ctx);
        }
        assertAllPricesInBounds(ctx, "10000 tick 后");
    }

    @Test
    void meanReversionPullsPricesBackToBase() {
        AimcContext ctx = newContext();
        MarketStore.CatalogItem item = ctx.market.item(IRON);
        assertNotNull(item, "铁锭应在商品目录里");
        long base = item.basePriceCents;

        // 3 倍基准价：300 tick 后应明显回落
        ctx.market.setPrice(item.id, base * 3L);
        long high = ctx.market.price(item.id);
        assertEquals(base * 3L, high, "应先能把价格设到 3 倍基准价");
        for (int i = 0; i < 300; i++) {
            PriceEngine.tickPrices(ctx);
        }
        long afterHigh = ctx.market.price(item.id);
        assertTrue(afterHigh < high * 0.99,
                "高价应明显回落：设成 " + high + " 后 300 tick 仍为 " + afterHigh);

        // 1/3 基准价：300 tick 后应明显回升
        long low = Math.max(1L, base / 3L);
        ctx.market.setPrice(item.id, low);
        for (int i = 0; i < 300; i++) {
            PriceEngine.tickPrices(ctx);
        }
        long afterLow = ctx.market.price(item.id);
        assertTrue(afterLow > low * 1.01,
                "低价应明显回升：设成 " + low + " 后 300 tick 仍为 " + afterLow);
    }

    @Test
    void impactPushesPriceDownWithinOneTickAndIsCapped() {
        AimcContext ctx = newContext();
        // 挑波动率最小的商品，把随机噪声压到最小，这样"价格下降"是确定性的方向断言。
        MarketStore.CatalogItem target = null;
        for (MarketStore.CatalogItem item : ctx.market.enabledItems()) {
            if (target == null || item.volatility < target.volatility) {
                target = item;
            }
        }
        assertNotNull(target, "商品目录不应为空");
        ctx.market.setPrice(target.id, target.basePriceCents);
        long before = ctx.market.price(target.id);

        // 净买入额 = 流动性 ×10，冲击会被夹在单 tick ±8% 内。
        ctx.market.addImpact(target.id, target.liquidity * 10L);
        PriceEngine.tickPrices(ctx);
        long after = ctx.market.price(target.id);

        assertTrue(after < before, "净买入冲击后价格应下降：" + before + " → " + after
                + "（" + target.id + "，波动率 " + target.volatility + "）");
        assertTrue(after >= Math.round(before * 0.75),
                "单 tick 跌幅不应过大（宽松上限 25%）：" + before + " → " + after);
        assertTrue(ctx.market.price(target.id) > 0L, "冲击后价格仍必须为正");
    }

    @Test
    void eventMultiplierAppliesAndExpires() {
        AimcContext ctx = newContext();
        MarketStore.CatalogItem item = ctx.market.item(IRON);
        assertNotNull(item, "铁锭应在商品目录里");

        assertEquals(1.0, ctx.market.eventMultiplier(item.id), 1.0e-12, "没有事件时乘数应为 1.0");
        ctx.market.applyEvent(item.id, 1.2, 3, "unit-test");
        double multiplier = ctx.market.eventMultiplier(item.id);
        assertTrue(multiplier >= 0.85 && multiplier <= 1.20,
                "事件乘数应落在 [0.85, 1.20]，实际 " + multiplier);
        assertEquals(1.2, multiplier, 1.0e-9, "施加 1.2 的事件后乘数应为 1.2");

        ctx.market.tickEvents();
        ctx.market.tickEvents();
        assertEquals(1.2, ctx.market.eventMultiplier(item.id), 1.0e-9, "3 tick 事件在第 2 次 tickEvents 后仍有效");
        ctx.market.tickEvents();
        assertEquals(1.0, ctx.market.eventMultiplier(item.id), 1.0e-12, "3 次 tickEvents 后事件应消失");
        assertTrue(ctx.market.activeEvents().isEmpty(), "事件列表应被清空");
    }

    @Test
    void stocksAndIndexStayInBounds() {
        AimcContext ctx = newContext();
        for (int i = 0; i < 100; i++) {
            PriceEngine.tickStocks(ctx);
        }
        for (StockStore.Stock stock : ctx.stocks.all()) {
            long initial = stock.initialPriceCents;
            assertTrue(stock.priceCents > 0L, stock.code + " 股价必须为正，实际 " + stock.priceCents);
            assertTrue(stock.priceCents >= Math.max(1L, initial / 10L),
                    stock.code + " 股价跌破下限：实际 " + stock.priceCents + "，发行价 " + initial);
            assertTrue(stock.priceCents <= initial * 10L,
                    stock.code + " 股价超过上限：实际 " + stock.priceCents + "，发行价 " + initial);
        }
        assertTrue(ctx.stocks.indexCentiPoints() > 0L, "大盘指数必须为正，实际 " + ctx.stocks.indexCentiPoints());
    }
}
