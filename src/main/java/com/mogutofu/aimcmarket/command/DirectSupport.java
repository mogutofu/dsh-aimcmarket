package com.mogutofu.aimcmarket.command;

import com.mogutofu.aimcmarket.AimcmarketMod;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.CompanyStore;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.data.StockStore;
import com.mogutofu.aimcmarket.ui.Messages;
import com.mogutofu.aimcmarket.util.Cents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 代执行用的公共工具：解析/发消息/物品操作。
 *
 * <p>所有方法都围绕"玩家自己的账户"设计，权限校验由底层引擎负责，
 * 这样 AI 既能完成全部玩家操作，也不会越权。</p>
 */
public final class DirectSupport {
    private DirectSupport() {
    }

    public static AimcContext ctx() {
        return AimcmarketMod.context();
    }

    /** 把 "/ex xxx" 或 "ex xxx" 拆成 token（按空格）。 */
    public static String[] tokens(String command) {
        if (command == null) {
            return new String[0];
        }
        String body = command.trim();
        if (body.startsWith("/ex ")) {
            body = body.substring(4).trim();
        } else if (body.startsWith("ex ")) {
            body = body.substring(3).trim();
        }
        if (body.isEmpty()) {
            return new String[0];
        }
        return body.split("\\s+");
    }

    /** 剩余 token 拼回字符串（用于中文名字/公司名）。 */
    public static String join(String[] parts, int from, int toExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < toExclusive && i < parts.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(parts[i]);
        }
        return builder.toString().trim();
    }

    public static long amountCents(String text) {
        return Cents.parse(text);
    }

    public static Account account(CommandSourceStack source) {
        AimcContext context = ctx();
        if (context == null || source == null || source.getPlayer() == null) {
            return null;
        }
        return context.accounts.get(source.getPlayer().getUUID().toString());
    }

    /** 目录物品（不含注册表模糊匹配）。 */
    public static MarketStore.CatalogItem catalogItem(String text) {
        AimcContext context = ctx();
        return context == null ? null : context.market.resolve(text);
    }

    /** 股票：代码或中文名。 */
    public static StockStore.Stock stock(String text) {
        AimcContext context = ctx();
        if (context == null || text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        StockStore.Stock direct = context.stocks.stock(value);
        if (direct != null) {
            return direct;
        }
        StockStore.Stock best = null;
        int bestLength = 0;
        for (StockStore.Stock stock : context.stocks.all()) {
            if (stock.name == null) {
                continue;
            }
            if (stock.name.equalsIgnoreCase(value)) {
                return stock;
            }
            if (stock.name.length() >= 2 && (value.contains(stock.name) || stock.name.contains(value))
                    && stock.name.length() > bestLength) {
                best = stock;
                bestLength = stock.name.length();
            }
        }
        return best;
    }

    /** 任意公司（名字/id/代码/模糊）。 */
    public static CompanyStore.Company company(String text) {
        AimcContext context = ctx();
        if (context == null || text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        for (CompanyStore.Company company : context.companies.all()) {
            if (company.name.equalsIgnoreCase(value) || company.id.equalsIgnoreCase(value)
                    || (company.code != null && company.code.equalsIgnoreCase(value))) {
                return company;
            }
        }
        CompanyStore.Company byName = context.companies.findByName(value);
        if (byName != null) {
            return byName;
        }
        for (CompanyStore.Company company : context.companies.all()) {
            if (company.name.contains(value) || value.contains(company.name)) {
                return company;
            }
        }
        return null;
    }

    /** 玩家作为 CEO 的公司（只有一家时直接用）。 */
    public static CompanyStore.Company myCompany(Account account) {
        AimcContext context = ctx();
        if (context == null || account == null) {
            return null;
        }
        List<CompanyStore.Company> mine = context.companies.byCeo(account.uuid);
        return mine.size() == 1 ? mine.get(0) : null;
    }

    public static String handItemId(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            stack = player.getOffhandItem();
        }
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static int countItem(ServerPlayer player, String itemId) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (player == null || item == Items.AIR) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static void removeItem(ServerPlayer player, String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (player == null || item == Items.AIR) {
            return;
        }
        int left = count;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && left > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
    }

    /** 给物品，返回放不下的数量（会掉在脚下）。 */
    public static int giveItem(ServerPlayer player, String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (player == null || item == Items.AIR) {
            return count;
        }
        int left = count;
        while (left > 0) {
            int stackSize = Math.min(left, item.getMaxStackSize());
            ItemStack stack = new ItemStack(item, stackSize);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            left -= stackSize;
        }
        return 0;
    }

    public static void ok(CommandSourceStack source, String text) {
        Messages.sendOk(source, text);
    }

    public static void fail(CommandSourceStack source, String text) {
        Messages.sendFail(source, text);
    }

    public static void info(CommandSourceStack source, String text) {
        Messages.sendInfo(source, text);
    }

    public static void send(CommandSourceStack source, String text) {
        Messages.send(source, text);
    }

    public static List<String> tokensList(String command) {
        List<String> list = new ArrayList<>();
        for (String token : tokens(command)) {
            list.add(token);
        }
        return list;
    }
}
