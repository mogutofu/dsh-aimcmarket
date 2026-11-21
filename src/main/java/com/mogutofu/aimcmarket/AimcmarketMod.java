package com.mogutofu.aimcmarket;

import net.minecraftforge.fml.common.Mod;

/**
 * AIMCmarket 主类。
 *
 * <p>纯服务端模组：不注册方块/物品/客户端资源，只做命令、事件、经济与 AI 接入。
 * 这个类现在是骨架，后续逐步接入配置、数据、引擎、命令。</p>
 */
@Mod(AimcmarketMod.MOD_ID)
public final class AimcmarketMod {
    public static final String MOD_ID = "aimcmarket";

    public AimcmarketMod() {
        // 骨架阶段：先保证空模组能构建、能加载。
    }
}
