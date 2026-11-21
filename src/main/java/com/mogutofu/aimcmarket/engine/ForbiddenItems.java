package com.mogutofu.aimcmarket.engine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Set;

/**
 * 创造类物品黑名单：这些物品会破坏经济（无限资源/无限能源），玩家不能买、不能卖、不能收购/退货。
 *
 * <p>判定：物品 id 含 {@code creative}，或显示名含"创造"，或命中显式名单。
 * 只作用于商品市场，不影响命令/合成等其他玩法。</p>
 */
public final class ForbiddenItems {
    /** 额外显式屏蔽的物品 id（id 命中 creative 的已自动覆盖，这里补充命名里没有 creative 的创造物品）。 */
    private static final Set<String> EXTRA_IDS = Set.of(
            "create:creative_motor",
            "create:creative_crate",
            "create:creative_fluid_tank",
            "create:creative_blaze_cake",
            "create:creative_energy",
            "createaddition:creative_energy",
            "createcasing:creative_crate",
            "ae2:creative_energy_cell",
            "ae2:creative_storage_cell",
            "ae2:creative_spatial_storage_cell",
            "mekanism:creative_energy_cube",
            "mekanism:creative_fluid_tank",
            "mekanism:creative_bin",
            "thermal:creative_energy_cell",
            "thermal:creative_fluid_tank",
            "thermal:creative_item_cell",
            "immersiveengineering:capacitor_creative",
            "enderio:creative_energy",
            "botania:creative_pool",
            "botania:creative_mana_pool",
            "mekanismgenerators:creative_generator"
    );

    private ForbiddenItems() {
    }

    /** 按 id / 显示名判断是否创造类物品。 */
    public static boolean isForbidden(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        String id = itemId.trim().toLowerCase(Locale.ROOT);
        if (EXTRA_IDS.contains(id)) {
            return true;
        }
        if (id.contains("creative") || id.contains("infinite")) {
            return true;
        }
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(id));
            if (item != null) {
                String name = new ItemStack(item).getHoverName().getString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.contains("创造") || lower.contains("creative")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 注册表还没初始化（单测环境）：只按 id 规则判断
        }
        return false;
    }

    /** 玩家可见的拒绝提示。 */
    public static String denyMessage() {
        return "这是创造类物品，市场不买卖（会破坏经济）";
    }
}
