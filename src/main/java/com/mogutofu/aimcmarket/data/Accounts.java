package com.mogutofu.aimcmarket.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mogutofu.aimcmarket.util.JsonFile;
import com.mogutofu.aimcmarket.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 玩家账户库，落盘在 {@code world/aimcmarket/accounts.json}。
 */
public final class Accounts {
    private final Path file;
    private final Ledger ledger;
    private final Map<String, Account> byUuid = new LinkedHashMap<>();

    public Accounts(Path file, Ledger ledger) {
        this.file = file;
        this.ledger = ledger;
    }

    public void load() {
        byUuid.clear();
        JsonObject root = JsonFile.readObject(file);
        if (root != null) {
            JsonObject accounts = JsonFile.object(root, "accounts");
            for (Map.Entry<String, JsonElement> entry : accounts.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                try {
                    Account account = Account.fromJson(entry.getValue().getAsJsonObject());
                    if (account.uuid.isEmpty()) {
                        continue;
                    }
                    byUuid.put(account.uuid, account);
                } catch (RuntimeException error) {
                    Log.warn("加载账户失败（" + entry.getKey() + "）", error);
                }
            }
        }
        for (Account account : byUuid.values()) {
            ledger.register(account);
            ledger.register(account.bankAccount());
        }
        Log.info("账户加载完成：" + byUuid.size() + " 个");
    }

    /**
     * 取账户；不存在就建（顺便发初始资金）。
     *
     * @return 账户；如果 {@code giveStartingBalance} 且是新建的，会发 {@code startingBalanceCents}
     */
    public Account getOrCreate(String uuid, String name, long nowMs, long startingBalanceCents, boolean giveStartingBalance) {
        if (uuid == null || uuid.isEmpty()) {
            throw new IllegalArgumentException("uuid 不能为空");
        }
        Account account = byUuid.get(uuid);
        if (account == null) {
            account = new Account(uuid);
            account.name = name == null ? "" : name;
            account.createdAtMs = nowMs;
            account.lastSeenMs = nowMs;
            byUuid.put(uuid, account);
            ledger.register(account);
            ledger.register(account.bankAccount());
            if (giveStartingBalance && startingBalanceCents > 0L) {
                ledger.issue(account, startingBalanceCents, "account.create", account.name,
                        "新玩家初始资金 " + com.mogutofu.aimcmarket.util.Cents.format(startingBalanceCents) + " 币");
            }
            Log.info("新账户 " + account.name + "（" + uuid + "）");
            return account;
        }
        if (name != null && !name.isBlank()) {
            account.name = name;
        }
        account.lastSeenMs = nowMs;
        return account;
    }

    public Account get(String uuid) {
        return uuid == null ? null : byUuid.get(uuid);
    }

    public Collection<Account> all() {
        return byUuid.values();
    }

    public List<Account> allList() {
        return new ArrayList<>(byUuid.values());
    }

    public Optional<Account> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toLowerCase(Locale.ROOT);
        for (Account account : byUuid.values()) {
            if (account.name != null && account.name.toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(account);
            }
        }
        return Optional.empty();
    }

    /** 名称模糊匹配（命令补全 / 转账用）。 */
    public List<Account> searchByName(String fragment) {
        List<Account> result = new ArrayList<>();
        if (fragment == null || fragment.isBlank()) {
            return result;
        }
        String needle = fragment.trim().toLowerCase(Locale.ROOT);
        for (Account account : byUuid.values()) {
            if (account.name != null && account.name.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(account);
            }
        }
        return result;
    }

    public long totalWalletCents() {
        long sum = 0L;
        for (Account account : byUuid.values()) {
            sum = Math.addExact(sum, account.balanceCents());
        }
        return sum;
    }

    public long totalBankCents() {
        long sum = 0L;
        for (Account account : byUuid.values()) {
            sum = Math.addExact(sum, account.bank.totalDepositsCents());
        }
        return sum;
    }

    /** 在线玩家余额中位数（政府指标用）。 */
    public long medianBalanceCents(Collection<Account> online) {
        List<Long> values = new ArrayList<>();
        for (Account account : online) {
            values.add(account.balanceCents());
        }
        if (values.isEmpty()) {
            for (Account account : byUuid.values()) {
                values.add(account.balanceCents());
            }
        }
        if (values.isEmpty()) {
            return 0L;
        }
        values.sort(Long::compareTo);
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        }
        return Math.addExact(values.get(middle - 1), values.get(middle)) / 2L;
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonObject accounts = new JsonObject();
        // 按名字排序，方便人看文件
        List<Account> sorted = new ArrayList<>(byUuid.values());
        sorted.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        for (Account account : sorted) {
            accounts.add(account.uuid, account.toJson());
        }
        root.add("accounts", accounts);
        try {
            JsonFile.write(file, root);
        } catch (IOException error) {
            Log.warn("保存 accounts.json 失败（下个 tick 重试）", error);
        }
    }

    public Path file() {
        return file;
    }

    public boolean fileExists() {
        return Files.isRegularFile(file);
    }
}
