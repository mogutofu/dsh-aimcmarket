package com.mogutofu.aimcmarket;

import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.ai.MarketAi;
import com.mogutofu.aimcmarket.command.AdminCommand;
import com.mogutofu.aimcmarket.command.MarketCommand;
import com.mogutofu.aimcmarket.config.MarketConfig;
import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.engine.MarketTicker;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Log;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AIMCmarket 主类。
 *
 * <p>纯服务端模组：不注册方块/物品/客户端资源，只做命令、事件、经济与 AI 接入。
 * 线程模型：服务器主线程只做内存运算和发消息；HTTP 调用交给 2 个 daemon 线程的池，
 * 结果用 {@code server.execute(...)} 切回主线程再动数据。</p>
 */
@Mod(AimcmarketMod.MOD_ID)
public final class AimcmarketMod {
    public static final String MOD_ID = "aimcmarket";
    private static final Logger LOGGER = LoggerFactory.getLogger("AIMCmarket");

    /** 全局状态（服务器启动时创建，停服清空）。 */
    private static volatile AimcContext context;
    private static MarketTicker ticker;
    private static ExecutorService aiPool;
    private static MinecraftServer serverRef;

    public AimcmarketMod() {
        Log.setSinks(LOGGER::info, LOGGER::warn);
        MarketConfig.register();
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
        LOGGER.info("AIMCmarket 已加载（纯服务端，无需客户端模组）");
    }

    public static AimcContext context() {
        return context;
    }

    public static MarketSettings settings() {
        return context == null ? MarketSettings.defaults() : context.settings;
    }

    /** 配置热加载（/ex admin reload 调）。返回新设置。 */
    public static MarketSettings reloadSettings() {
        AimcContext ctx = context;
        if (ctx == null) {
            return MarketSettings.defaults();
        }
        MarketConfig.reload(ctx.settings);
        ctx.clock.configure(ctx.settings.marketTickMinutes, ctx.settings.tradingDayMinutes);
        ctx.market.reloadCatalog();
        return ctx.settings;
    }

    // ── 生命周期 ──

    private void onServerStarting(ServerStartingEvent event) {
        serverRef = event.getServer();
        MarketSettings settings = MarketConfig.read();
        java.nio.file.Path serverDir = FMLPaths.GAMEDIR.get().toAbsolutePath();
        // 商品目录/配置约定在 config/aimcmarket/ 下（config/aimcmarket-common.toml 是 Forge 配置文件本身）
        java.nio.file.Path configDir = FMLPaths.CONFIGDIR.get().toAbsolutePath().resolve("aimcmarket");
        long now = System.currentTimeMillis();
        long seed = now ^ (long) serverDir.hashCode();
        AimcContext ctx = AimcContext.load(serverDir, configDir, settings, now, seed);
        MarketAi ai = null;
        if (settings.aiEnabled) {
            ai = new MarketAi(settings, ctx.market);
            ctx.ai = ai;
        }
        setupAsync(ctx, event.getServer());
        ticker = new MarketTicker();
        context = ctx;
        LOGGER.info("AIMCmarket 启动：AI=" + (ai == null ? "关闭" : settings.aiMode)
                + "，市场 tick=" + settings.marketTickMinutes + " 分钟");
    }

    private void setupAsync(AimcContext ctx, MinecraftServer server) {
        if (aiPool == null || aiPool.isShutdown()) {
            aiPool = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "aimcmarket-ai");
                thread.setDaemon(true);
                return thread;
            });
        }
        ExecutorService pool = aiPool;
        ctx.aiSubmitter = (label, call, apply) -> {
            if (pool.isShutdown()) {
                return;
            }
            try {
                pool.execute(() -> {
                    JsonObject result = null;
                    try {
                        result = call.get();
                    } catch (RuntimeException error) {
                        LOGGER.warn("[ai] {} 调用失败：{}", label, error.getMessage());
                    }
                    final JsonObject payload = result;
                    try {
                        server.execute(() -> {
                            try {
                                apply.accept(payload);
                            } catch (RuntimeException error) {
                                LOGGER.warn("[ai] {} 结果落地失败：{}", label, error.getMessage());
                            }
                        });
                    } catch (RuntimeException rejected) {
                        // 服务器已经停了，丢弃结果
                    }
                });
            } catch (RuntimeException rejected) {
                LOGGER.warn("[ai] {} 提交失败：{}", label, rejected.getMessage());
            }
        };
        ctx.broadcaster = (message, government) -> {
            if (message == null || message.isBlank()) {
                return;
            }
            try {
                Messages.broadcast(server, message, government);
            } catch (RuntimeException error) {
                LOGGER.warn("广播失败：{}", error.getMessage());
            }
        };
    }

    private void onServerStopping(ServerStoppingEvent event) {
        AimcContext ctx = context;
        if (ctx != null) {
            try {
                ctx.saveAll();
            } catch (RuntimeException error) {
                LOGGER.warn("停服保存失败：{}", error.getMessage());
            }
        }
        if (aiPool != null) {
            aiPool.shutdownNow();
            try {
                aiPool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        context = null;
        ticker = null;
        serverRef = null;
        LOGGER.info("AIMCmarket 已保存并停止");
    }

    // ── 事件 ──

    private void onRegisterCommands(RegisterCommandsEvent event) {
        MarketCommand.register(event.getDispatcher());
        AdminCommand.register(event.getDispatcher());
        LOGGER.info("AIMCmarket 命令已注册：/ex、/exchange、/market");
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AimcContext ctx = context;
        MarketTicker currentTicker = ticker;
        MinecraftServer server = event.getServer();
        if (ctx == null || currentTicker == null || server == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Account> online = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Account account = ctx.accounts.get(player.getUUID().toString());
            if (account != null) {
                account.lastSeenMs = now;
                online.add(account);
            }
        }
        ctx.onlineAccounts = online;
        try {
            currentTicker.onServerTick(ctx, now, new MarketTicker.Hooks() {
                @Override
                public void notifyPlayer(String playerName, String text) {
                    Messages.tell(server, playerName, text);
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn("市场 tick 异常：{}", error.getMessage());
        }
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        AimcContext ctx = context;
        if (ctx == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            String uuid = player.getUUID().toString();
            String name = player.getGameProfile().getName();
            long now = System.currentTimeMillis();
            boolean existed = ctx.accounts.get(uuid) != null;
            Account account = ctx.accounts.getOrCreate(uuid, name, now, ctx.settings.startingBalanceCents,
                    ctx.settings.startingBalanceCents > 0L);
            if (!existed) {
                player.sendSystemMessage(Messages.market("欢迎，" + name + "。市场给了你 "
                        + Messages.money(account.balanceCents()) + " 初始资金，输入 /ex 看市场帮助。"));
            } else {
                player.sendSystemMessage(Messages.market("欢迎回来，" + name + "。余额 "
                        + Messages.money(account.balanceCents()) + "，输入 /ex 看市场帮助。"));
            }
        } catch (RuntimeException error) {
            LOGGER.warn("玩家登录初始化失败：{}", error.getMessage());
        }
    }

    /** 当前在线玩家账户（政府福利分发用）。 */
    public static Collection<Account> onlineAccounts() {
        AimcContext ctx = context;
        return ctx == null ? java.util.Collections.emptyList() : ctx.onlineAccounts;
    }

    public static MinecraftServer server() {
        return serverRef;
    }
}
