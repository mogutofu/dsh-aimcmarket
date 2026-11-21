package com.mogutofu.aimcmarket.engine;

import com.mogutofu.aimcmarket.config.MarketSettings;
import com.mogutofu.aimcmarket.data.Account;
import com.mogutofu.aimcmarket.data.AimcContext;
import com.mogutofu.aimcmarket.data.BalanceAccount;
import com.mogutofu.aimcmarket.data.FuturesStore;
import com.mogutofu.aimcmarket.data.FuturesStore.FuturePosition;
import com.mogutofu.aimcmarket.data.Ledger;
import com.mogutofu.aimcmarket.data.MarketStore;
import com.mogutofu.aimcmarket.util.Cents;
import com.mogutofu.aimcmarket.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 期货引擎：开仓 / 平仓 / 报价 / 追加与提取保证金 / 每 tick 盯市 + 追保 + 强平 + 到期现金交割。
 *
 * <p>纯 Java 静态方法，不 import 任何 Minecraft 类；所有可预期失败返回
 * {@link ActionResult#fail(String)}，意外异常也在这里吃掉（记日志 + 返回失败）。</p>
 *
 * <h2>价格与合约口径</h2>
 * <ul>
 *   <li>现价 S = {@link TradeService#effectiveAskCents(AimcContext, String)}（商品买价，含点差与政府补贴/关税）；</li>
 *   <li>合约价 {@code F = S × (1 + futuresCarry × daysLeft/365)}，其中
 *       {@code daysLeft = expiryDay - ctx.tradingDay()}（开仓时等于到期天数），到期时 F 收敛到现货 S；</li>
 *   <li>1 手 = {@link FuturesStore#LOT_SIZE}（64）个物品；名义价值 = F × 64 × 手数；</li>
 *   <li>开仓：保证金 = 名义 × {@code futuresMarginRatio}，手续费 = 名义 × {@code futuresFeeRatio}，
 *       交易税 = 名义 × {@code government.effectiveTradeTax()}；</li>
 *   <li>到期：现金交割，不交实物，按现货 S 结算；</li>
 *   <li>盯市只改 {@code position.marginCents}（= 初始保证金 + 累计浮盈，钱一直躺在 {@code market:maker}
 *       账户里），只有平仓 / 强平 / 到期才真正动真钱。</li>
 * </ul>
 *
 * <h2>期货零和口径（不变量 3.16）</h2>
 * <p>本引擎把 {@code market:maker} 当作所有持仓的对手方（清算所）：</p>
 * <ul>
 *   <li>玩家权益 {@code E = 初始保证金 + 累计浮盈 PnL_p} 记在 {@code position.marginCents} 上，
 *       真钱始终在 maker 账户里；</li>
 *   <li>平仓 / 强平 / 到期时，maker 按 {@code E} 付款给玩家，因此
 *       {@code Δ(maker) = -Δ(玩家权益)}，即 <b>Σ(所有玩家已实现 PnL) + Σ(maker 因持仓产生的 PnL) = 0</b>；</li>
 *   <li>手续费进 {@code fee:pool}、交易税进 {@code gov:budget}，不计入零和；它们只从玩家一侧扣，
 *       maker 不因此多出或少掉本金（maker 只做代收代付）；</li>
 *   <li>如果亏损超过保证金（跳空），玩家权益最多扣到 0，超出部分由 maker 承担（maker 账户允许为负），
 *       这仍然满足上面的等式；<b>不允许直接改余额字段，所有资金变动都走 {@link Ledger} 的腿。</b></li>
 * </ul>
 */
public final class FuturesEngine {
    /** 1 手 = 64 个物品（与 {@link FuturesStore#LOT_SIZE} 同源，避免两处写死）。 */
    public static final long LOT_SIZE = FuturesStore.LOT_SIZE;
    /** 追保宽限期：5 现实分钟，过期强平。 */
    public static final long MARGIN_CALL_GRACE_MS = 5L * 60_000L;
    /** 强平费：名义价值的 0.1%。 */
    public static final double LIQUIDATION_FEE_RATIO = 0.001;
    /** 单笔手数上限（纯防溢出，不是经济平衡参数）。 */
    public static final long MAX_LOTS = 100_000L;

    private static final String SYMBOL_HELP =
            "IRON（铁锭）、GOLD（金锭）、DIAMOND（钻石）、EMERALD（绿宝石）、WHEAT（小麦）";

    private FuturesEngine() {
    }

    // ══════════════════════════════════════════════════════════════
    // 开仓
    // ══════════════════════════════════════════════════════════════

    /**
     * 开仓（做多 / 做空）。
     *
     * <p>扣款 = 保证金 + 手续费 + 交易税，全部一次性从钱包扣：保证金转到 {@code market:maker}，
     * 手续费进 {@code fee:pool}，交易税进 {@code gov:budget}。持仓创建后由 {@link #onMarketTick}
     * 每 tick 盯市。</p>
     *
     * @param symbol       标的代码（IRON/GOLD/DIAMOND/EMERALD/WHEAT，也接受目录 id 和中文别名）
     * @param longPosition true = 做多，false = 做空
     * @param lots         手数（1 手 = 64 个物品）
     * @param expiryDays   到期天数：7 / 14 / 30 个交易日
     */
    public static ActionResult open(AimcContext ctx, Account account, String symbol, boolean longPosition,
                                    long lots, int expiryDays) {
        if (ctx == null || account == null) {
            return ActionResult.fail("市场数据或账户还没加载好，稍后再试");
        }
        try {
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            if (!settings.enabled) {
                return ActionResult.fail("市场已关闭，暂时不能开仓");
            }
            if (settings.adminFreeze) {
                return ActionResult.fail("管理员冻结了市场交易，稍后再试");
            }
            String code = resolveSymbol(ctx, symbol);
            if (code == null) {
                return ActionResult.fail("不支持的期货标的：" + clean(symbol) + "。可选 " + SYMBOL_HELP);
            }
            if (!isExpiryDays(expiryDays)) {
                return ActionResult.fail("到期期限只能选 7 / 14 / 30 个交易日（当前 " + expiryDays + "），到期越短 carry 越少");
            }
            if (lots <= 0L) {
                return ActionResult.fail("手数必须是正整数（1 手 = " + LOT_SIZE + " 个物品）");
            }
            if (lots > MAX_LOTS) {
                return ActionResult.fail("单笔最多 " + MAX_LOTS + " 手，请拆成多笔");
            }
            String itemId = FuturesStore.SYMBOLS.get(code);
            MarketStore.CatalogItem item = ctx.market.item(itemId);
            if (item == null) {
                return ActionResult.fail("商品目录里没有 " + code + " 的报价，暂时不能开仓");
            }
            if (!item.enabled) {
                return ActionResult.fail(displayName(item) + " 暂停交易，暂时不能开仓");
            }
            long spot = spotCents(ctx, code);
            if (spot <= 0L) {
                return ActionResult.fail("暂时拿不到 " + code + " 的现货报价，开仓取消");
            }

            long entry = FuturesStore.contractPrice(spot, expiryDays, settings.futuresCarry);
            long notional = Cents.mul(Cents.mul(entry, LOT_SIZE), lots);
            long margin = Cents.percent(notional, settings.futuresMarginRatio);
            long fee = Cents.percent(notional, settings.futuresFeeRatio);
            long tax = Cents.percent(notional, ctx.government.effectiveTradeTax());
            long total = Math.addExact(Math.addExact(margin, fee), tax);
            if (total <= 0L) {
                return ActionResult.fail("名义价值 " + Cents.format(notional) + " 币太小，算不出保证金，多开几手吧");
            }
            long balance = account.balanceCents();
            if (balance < total) {
                return ActionResult.fail("余额不足：开 " + lots + " 手 " + code + " 需要 " + Cents.format(total)
                        + " 币（保证金 " + Cents.format(margin) + " + 手续费 " + Cents.format(fee)
                        + " + 交易税 " + Cents.format(tax) + "），钱包只有 " + Cents.format(balance) + " 币");
            }

            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
            BalanceAccount feePool = ledgerAccount(ctx, Ledger.FEE_POOL);
            BalanceAccount budget = ledgerAccount(ctx, Ledger.GOV_BUDGET);
            String direction = longPosition ? "做多" : "做空";

            // 四腿：玩家 -总扣款，maker +保证金，fee:pool +手续费，gov:budget +税；相加为 0。
            List<Ledger.Leg> legs = new ArrayList<>(4);
            legs.add(Ledger.leg(account, -total));
            legs.add(Ledger.leg(maker, margin));
            if (fee > 0L) {
                legs.add(Ledger.leg(feePool, fee));
            }
            if (tax > 0L) {
                legs.add(Ledger.leg(budget, tax));
            }
            ctx.ledger.post("futures.open", actorName(account),
                    direction + " " + code + " " + lots + " 手 × " + expiryDays + " 交易日",
                    legs.toArray(new Ledger.Leg[0]));

            FuturePosition position = new FuturePosition();
            position.uuid = account.uuid;
            position.playerName = actorName(account);
            position.symbol = code;
            position.longPosition = longPosition;
            position.lots = lots;
            position.entryPriceCents = entry;
            position.expiryDays = expiryDays;
            position.expiryDay = ctx.tradingDay() + (long) expiryDays;
            position.marginCents = margin;
            position.initialMarginCents = margin;
            position.lastMarkPriceCents = entry;
            position.openedTick = ctx.tick();
            position.marginCallAtMs = 0L;
            ctx.futures.add(position);

            return ActionResult.ok("开仓成功 #" + position.id + "：" + direction + " " + code + " " + lots
                    + " 手 × " + expiryDays + " 交易日；合约价 " + Cents.format(entry) + " 币/个（现货 "
                    + Cents.format(spot) + " 币），名义 " + Cents.format(notional) + " 币；保证金 "
                    + Cents.format(margin) + " 币（" + Cents.formatPercent(settings.futuresMarginRatio)
                    + "），手续费 " + Cents.format(fee) + " 币，交易税 " + Cents.format(tax) + " 币（"
                    + Cents.formatPercent(ctx.government.effectiveTradeTax()) + "），共扣 " + Cents.format(total)
                    + " 币；余额 " + Cents.format(account.balanceCents()) + " 币；第 " + position.expiryDay
                    + " 交易日到期（现金交割）。");
        } catch (RuntimeException error) {
            Log.warn("期货开仓失败（" + actorName(account) + " " + clean(symbol) + " " + lots + " 手）", error);
            return ActionResult.fail("开仓失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 报价
    // ══════════════════════════════════════════════════════════════

    /**
     * 报价文本：列出 7 / 14 / 30 三个期限的合约价 F、一手名义价值、点差与费用口径。
     *
     * @param expiryDays 想重点看的期限（7/14/30）；传别的值也照样列出全部，只是不标星
     */
    public static ActionResult quote(AimcContext ctx, String symbol, int expiryDays) {
        if (ctx == null) {
            return ActionResult.fail("市场数据还没加载好，稍后再试");
        }
        try {
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            String code = resolveSymbol(ctx, symbol);
            if (code == null) {
                return ActionResult.fail("不支持的期货标的：" + clean(symbol) + "。可选 " + SYMBOL_HELP);
            }
            String itemId = FuturesStore.SYMBOLS.get(code);
            MarketStore.CatalogItem item = ctx.market.item(itemId);
            if (item == null) {
                return ActionResult.fail("商品目录里没有 " + code + " 的报价，暂时没有行情");
            }
            long spot = spotCents(ctx, code);
            if (spot <= 0L) {
                return ActionResult.fail("暂时拿不到 " + code + " 的现货报价");
            }
            boolean highlight = isExpiryDays(expiryDays);

            List<String> lines = new ArrayList<>(6);
            lines.add(code + " " + displayName(item) + " 期货报价（现货买价 " + Cents.format(spot)
                    + " 币/个，1 手 = " + LOT_SIZE + " 个）");
            for (int days : FuturesStore.EXPIRY_DAYS) {
                long futuresPrice = FuturesStore.contractPrice(spot, days, settings.futuresCarry);
                long notional = Cents.mul(Cents.mul(futuresPrice, LOT_SIZE), 1L);
                long margin = Cents.percent(notional, settings.futuresMarginRatio);
                lines.add((highlight && days == expiryDays ? " * " : "   ") + days + " 交易日：F = "
                        + Cents.format(futuresPrice) + " 币/个，一手名义 " + Cents.format(notional)
                        + " 币，一手保证金 " + Cents.format(margin) + " 币");
            }
            lines.add("点差：现价用商品买价（已含 " + Cents.formatPercent(settings.askSpread)
                    + " 点差）；期货是现金交割的差价合约，不交割实物，也没有额外买卖点差。");
            lines.add("保证金 " + Cents.formatPercent(settings.futuresMarginRatio) + "，维持 "
                    + Cents.formatPercent(settings.futuresMaintenanceRatio) + "；开/平仓手续费 "
                    + Cents.formatPercent(settings.futuresFeeRatio) + " 名义 + 交易税 "
                    + Cents.formatPercent(ctx.government.effectiveTradeTax())
                    + "；到期按现货价现金交割。");
            return ActionResult.ok(String.join("\n", lines));
        } catch (RuntimeException error) {
            Log.warn("期货报价失败（" + clean(symbol) + "）", error);
            return ActionResult.fail("报价失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 平仓
    // ══════════════════════════════════════════════════════════════

    /**
     * 平仓：按当前合约价 F_now 结算。
     *
     * <p>PnL = (F_now - 开仓价) × 64 × 手数（空头相反）；权益 = 盯市后的 marginCents + 从上次盯市到现在的
     * PnL 增量；退回 = max(0, 权益 - 平仓手续费 - 交易税)。手续费和税由 maker 代付到
     * {@code fee:pool} / {@code gov:budget}，玩家实际到账是「退回」。</p>
     */
    public static ActionResult close(AimcContext ctx, Account account, String positionId) {
        if (ctx == null || account == null) {
            return ActionResult.fail("市场数据或账户还没加载好，稍后再试");
        }
        try {
            MarketSettings settings = ctx.settings;
            if (settings == null) {
                return ActionResult.fail("市场配置还没加载好，稍后再试");
            }
            FuturePosition position = ctx.futures.find(clean(positionId));
            if (position == null) {
                return ActionResult.fail("找不到这个期货持仓：" + clean(positionId) + "（用 /ex fut positions 查编号）");
            }
            if (!account.uuid.equals(position.uuid)) {
                return ActionResult.fail("这不是你的持仓，不能平仓");
            }
            long spot = spotCents(ctx, position.symbol);
            if (spot <= 0L) {
                return ActionResult.fail("暂时拿不到 " + position.symbol + " 的行情，稍后再平仓");
            }
            long daysLeft = Math.max(0L, position.expiryDay - ctx.tradingDay());
            long futuresPrice = FuturesStore.contractPrice(spot, daysLeft, settings.futuresCarry);

            long pnlTotal = position.pnlCents(futuresPrice);
            long pendingPnl = Math.subtractExact(pnlTotal, position.pnlCents(position.lastMarkPriceCents));
            long equity = Math.addExact(position.marginCents, pendingPnl);
            long notional = position.notionalCents(futuresPrice);
            long fee = Cents.percent(notional, settings.futuresFeeRatio);
            long tax = Cents.percent(notional, ctx.government.effectiveTradeTax());
            long settlement = Math.max(0L, Math.subtractExact(Math.subtractExact(equity, fee), tax));

            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
            BalanceAccount feePool = ledgerAccount(ctx, Ledger.FEE_POOL);
            BalanceAccount budget = ledgerAccount(ctx, Ledger.GOV_BUDGET);

            // 腿：maker 付玩家退回、付手续费池、付政府税；加起来为 0（settlement/fee/tax 都是非负）。
            List<Ledger.Leg> legs = new ArrayList<>(6);
            if (settlement > 0L) {
                legs.add(Ledger.leg(maker, -settlement));
                legs.add(Ledger.leg(account, settlement));
            }
            if (fee > 0L) {
                legs.add(Ledger.leg(maker, -fee));
                legs.add(Ledger.leg(feePool, fee));
            }
            if (tax > 0L) {
                legs.add(Ledger.leg(maker, -tax));
                legs.add(Ledger.leg(budget, tax));
            }
            if (!legs.isEmpty()) {
                ctx.ledger.post("futures.close", actorName(account),
                        "平仓 #" + position.id + " " + directionText(position) + " " + position.symbol + " "
                                + position.lots + " 手",
                        legs.toArray(new Ledger.Leg[0]));
            }
            ctx.futures.remove(position);

            return ActionResult.ok("平仓成功 #" + position.id + "：" + directionText(position) + " "
                    + position.symbol + " " + position.lots + " 手；开仓 " + Cents.format(position.entryPriceCents)
                    + " → 平仓 " + Cents.format(futuresPrice) + " 币/个，PnL " + Cents.formatSigned(pnlTotal)
                    + " 币；平仓手续费 " + Cents.format(fee) + " 币，交易税 " + Cents.format(tax)
                    + " 币，退回结算 " + Cents.format(settlement) + " 币；余额 "
                    + Cents.format(account.balanceCents()) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("期货平仓失败（" + actorName(account) + " " + clean(positionId) + "）", error);
            return ActionResult.fail("平仓失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 追加 / 提取保证金
    // ══════════════════════════════════════════════════════════════

    /** 追加保证金：钱包 → {@code market:maker}，同时抬高持仓的 marginCents（权益）。 */
    public static ActionResult addMargin(AimcContext ctx, Account account, String positionId, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("市场数据或账户还没加载好，稍后再试");
        }
        try {
            if (amountCents <= 0L) {
                return ActionResult.fail("追加保证金金额必须大于 0");
            }
            FuturePosition position = ctx.futures.find(clean(positionId));
            if (position == null) {
                return ActionResult.fail("找不到这个期货持仓：" + clean(positionId));
            }
            if (!account.uuid.equals(position.uuid)) {
                return ActionResult.fail("这不是你的持仓，不能操作");
            }
            if (spotCents(ctx, position.symbol) <= 0L) {
                return ActionResult.fail("暂时拿不到 " + position.symbol + " 的行情，稍后再试");
            }
            if (account.balanceCents() < amountCents) {
                return ActionResult.fail("余额不足：想追加 " + Cents.format(amountCents) + " 币，钱包只有 "
                        + Cents.format(account.balanceCents()) + " 币");
            }
            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
            ctx.ledger.transfer(account, maker, amountCents, "futures.margin.add", actorName(account),
                    "#" + position.id + " 追加保证金");
            position.marginCents = Math.addExact(position.marginCents, amountCents);

            long futuresPrice = currentMarkPriceCents(ctx, position);
            long maintenance = maintenanceCents(ctx, position, futuresPrice);
            boolean cleared = false;
            if (position.marginCallAtMs > 0L && position.marginCents >= maintenance) {
                position.marginCallAtMs = 0L;
                cleared = true;
            }
            return ActionResult.ok("已追加保证金 " + Cents.format(amountCents) + " 币到 #" + position.id + "（"
                    + directionText(position) + " " + position.symbol + " " + position.lots + " 手）；权益 "
                    + Cents.format(position.marginCents) + " 币，维持保证金 " + Cents.format(maintenance)
                    + " 币" + (cleared ? "，追保已解除" : "") + "；余额 "
                    + Cents.format(account.balanceCents()) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("追加保证金失败（" + actorName(account) + " " + clean(positionId) + "）", error);
            return ActionResult.fail("追加保证金失败：" + brief(error));
        }
    }

    /**
     * 提取保证金：{@code market:maker} → 钱包，降低持仓的 marginCents（权益）。
     *
     * <p>提取后权益不能低于维持保证金（名义价值 × futuresMaintenanceRatio），被追保中时通常可提额为 0。</p>
     */
    public static ActionResult withdrawMargin(AimcContext ctx, Account account, String positionId, long amountCents) {
        if (ctx == null || account == null) {
            return ActionResult.fail("市场数据或账户还没加载好，稍后再试");
        }
        try {
            if (amountCents <= 0L) {
                return ActionResult.fail("提取保证金金额必须大于 0");
            }
            FuturePosition position = ctx.futures.find(clean(positionId));
            if (position == null) {
                return ActionResult.fail("找不到这个期货持仓：" + clean(positionId));
            }
            if (!account.uuid.equals(position.uuid)) {
                return ActionResult.fail("这不是你的持仓，不能操作");
            }
            if (spotCents(ctx, position.symbol) <= 0L) {
                return ActionResult.fail("暂时拿不到 " + position.symbol + " 的行情，稍后再试");
            }
            long futuresPrice = currentMarkPriceCents(ctx, position);
            long maintenance = maintenanceCents(ctx, position, futuresPrice);
            long available = Math.max(0L, Math.subtractExact(position.marginCents, maintenance));
            if (available <= 0L) {
                return ActionResult.fail("当前没有可提取的保证金：#" + position.id + " 权益 "
                        + Cents.format(position.marginCents) + " 币，维持保证金 " + Cents.format(maintenance)
                        + " 币" + (position.marginCallAtMs > 0L ? "（追保中，先补足缺口）" : ""));
            }
            if (amountCents > available) {
                return ActionResult.fail("最多只能提取 " + Cents.format(available) + " 币（权益 "
                        + Cents.format(position.marginCents) + " 币，维持保证金 " + Cents.format(maintenance) + " 币）");
            }
            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
            ctx.ledger.transfer(maker, account, amountCents, "futures.margin.withdraw", actorName(account),
                    "#" + position.id + " 提取保证金");
            position.marginCents = Math.subtractExact(position.marginCents, amountCents);

            return ActionResult.ok("已从 #" + position.id + "（" + directionText(position) + " " + position.symbol
                    + " " + position.lots + " 手）提取保证金 " + Cents.format(amountCents) + " 币；权益 "
                    + Cents.format(position.marginCents) + " 币（维持保证金 " + Cents.format(maintenance)
                    + " 币）；余额 " + Cents.format(account.balanceCents()) + " 币。");
        } catch (RuntimeException error) {
            Log.warn("提取保证金失败（" + actorName(account) + " " + clean(positionId) + "）", error);
            return ActionResult.fail("提取保证金失败：" + brief(error));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 每个 market tick：盯市 / 追保 / 强平 / 到期交割
    // ══════════════════════════════════════════════════════════════

    /**
     * 每个 market tick 调一次：遍历所有持仓做盯市、追保与到期交割。
     *
     * <p>盯市只改 {@code position.marginCents}（= 初始保证金 + 浮盈）和 {@code lastMarkPriceCents}，
     * 不动真钱；真钱只在平仓、强平、到期时由 maker ↔ 玩家转账。</p>
     */
    public static void onMarketTick(AimcContext ctx) {
        if (ctx == null || ctx.futures == null) {
            return;
        }
        List<FuturePosition> snapshot;
        try {
            snapshot = new ArrayList<>(ctx.futures.all());
        } catch (RuntimeException error) {
            Log.warn("读取期货持仓失败（跳过本 tick 的期货结算）", error);
            return;
        }
        long nowMs = ctx.nowMs();
        long day = ctx.tradingDay();
        for (FuturePosition position : snapshot) {
            try {
                markOrSettle(ctx, position, day, nowMs);
            } catch (RuntimeException error) {
                // 单笔持仓出错不影响其它持仓，下个 tick 重试。
                Log.warn("期货盯市失败（#" + position.id + " " + position.symbol + "）", error);
            }
        }
    }

    private static void markOrSettle(AimcContext ctx, FuturePosition position, long day, long nowMs) {
        long spot = spotCents(ctx, position.symbol);
        if (spot <= 0L) {
            return;
        }
        long daysLeft = position.expiryDay - day;
        if (daysLeft <= 0L) {
            settleAtExpiry(ctx, position, spot);
            return;
        }
        long futuresPrice = FuturesStore.contractPrice(spot, daysLeft, ctx.settings.futuresCarry);

        // 累计浮盈（相对开仓价）和上次盯市时的累计浮盈之差 = 本次要记入权益的 PnL 增量。
        long currentPnl = position.pnlCents(futuresPrice);
        long markedPnl = position.pnlCents(position.lastMarkPriceCents);
        long equity = Math.addExact(position.marginCents, Math.subtractExact(currentPnl, markedPnl));
        position.marginCents = equity;
        position.lastMarkPriceCents = futuresPrice;

        long maintenance = maintenanceCents(ctx, position, futuresPrice);
        if (equity >= maintenance) {
            // 行情回升/有人补过保证金：解除追保。
            position.marginCallAtMs = 0L;
            return;
        }
        if (position.marginCallAtMs <= 0L) {
            position.marginCallAtMs = nowMs;
            addNotice(ctx, "【期货追保】" + playerName(ctx, position) + " 的 #" + position.id + " "
                    + directionText(position) + " " + position.symbol + " " + position.lots + " 手：权益 "
                    + Cents.format(equity) + " 币 < 维持保证金 " + Cents.format(maintenance) + " 币，缺口 "
                    + Cents.format(maintenance - equity) + " 币。5 分钟内 /ex fut margin add 补足，否则强制平仓。");
        } else if (nowMs - position.marginCallAtMs >= MARGIN_CALL_GRACE_MS) {
            forceLiquidate(ctx, position, futuresPrice);
        }
    }

    /** 到期现金交割：交割价 = 现货 S，PnL 相对开仓价，退回 = max(0, 权益)。 */
    private static void settleAtExpiry(AimcContext ctx, FuturePosition position, long spotCents) {
        Account account = ctx.accounts.get(position.uuid);
        if (account == null) {
            ctx.futures.remove(position);
            Log.warn("期货到期但账户已不存在，直接移除持仓 #" + position.id + "（" + position.uuid + "）");
            return;
        }
        long pnlTotal = position.pnlCents(spotCents);
        long pendingPnl = Math.subtractExact(pnlTotal, position.pnlCents(position.lastMarkPriceCents));
        long equity = Math.addExact(position.marginCents, pendingPnl);
        long settlement = Math.max(0L, equity);

        if (settlement > 0L) {
            BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
            ctx.ledger.post("futures.expire", actorName(account),
                    "到期交割 #" + position.id + " " + directionText(position) + " " + position.symbol + " "
                            + position.lots + " 手（交割价 " + Cents.format(spotCents) + "）",
                    Ledger.leg(maker, -settlement), Ledger.leg(account, settlement));
        }
        ctx.futures.remove(position);
        addNotice(ctx, "【期货到期】" + actorName(account) + " 的 #" + position.id + " " + directionText(position)
                + " " + position.symbol + " " + position.lots + " 手按交割价 " + Cents.format(spotCents)
                + " 币现金交割：开仓 " + Cents.format(position.entryPriceCents) + "，PnL "
                + Cents.formatSigned(pnlTotal) + " 币，退回结算 " + Cents.format(settlement) + " 币（初始保证金 "
                + Cents.format(position.initialMarginCents) + " 币）。");
        Log.info("期货到期交割 #" + position.id + " " + position.symbol + " " + directionText(position) + " "
                + position.lots + " 手 → " + actorName(account) + "，PnL " + Cents.formatSigned(pnlTotal)
                + " 币，退回 " + Cents.format(settlement) + " 币");
    }

    /**
     * 强平：按 F_now 平仓，扣 0.1% 强平费，退回 max(0, 权益 - 强平费)。
     *
     * <p>亏损吃穿保证金时玩家最多亏掉全部权益，超出部分由 maker 承担（maker 账户允许为负）。</p>
     */
    private static void forceLiquidate(AimcContext ctx, FuturePosition position, long futuresPrice) {
        Account account = ctx.accounts.get(position.uuid);
        if (account == null) {
            ctx.futures.remove(position);
            Log.warn("期货强平但账户已不存在，直接移除持仓 #" + position.id + "（" + position.uuid + "）");
            return;
        }
        long equity = position.marginCents;
        long notional = position.notionalCents(futuresPrice);
        long fee = Cents.percent(notional, LIQUIDATION_FEE_RATIO);
        long refund = equity > fee ? Math.subtractExact(equity, fee) : 0L;

        BalanceAccount maker = ledgerAccount(ctx, Ledger.MAKER);
        BalanceAccount feePool = ledgerAccount(ctx, Ledger.FEE_POOL);
        List<Ledger.Leg> legs = new ArrayList<>(4);
        if (refund > 0L) {
            legs.add(Ledger.leg(maker, -refund));
            legs.add(Ledger.leg(account, refund));
        }
        if (fee > 0L) {
            legs.add(Ledger.leg(maker, -fee));
            legs.add(Ledger.leg(feePool, fee));
        }
        if (!legs.isEmpty()) {
            ctx.ledger.post("futures.liquidate", actorName(account),
                    "强平 #" + position.id + " " + directionText(position) + " " + position.symbol + " "
                            + position.lots + " 手（强平价 " + Cents.format(futuresPrice) + "）",
                    legs.toArray(new Ledger.Leg[0]));
        }
        ctx.futures.remove(position);
        addNotice(ctx, "【期货强平】" + actorName(account) + " 的 #" + position.id + " " + directionText(position)
                + " " + position.symbol + " " + position.lots + " 手被强平（合约价 " + Cents.format(futuresPrice)
                + "）：权益 " + Cents.format(equity) + " 币，扣强平费 " + Cents.format(fee) + " 币，退回 "
                + Cents.format(refund) + " 币。");
        Log.info("期货强平 #" + position.id + " " + position.symbol + " " + directionText(position) + " → "
                + actorName(account) + "，权益 " + Cents.format(equity) + " 币，退回 " + Cents.format(refund) + " 币");
    }

    // ══════════════════════════════════════════════════════════════
    // 查询文本（给命令层）
    // ══════════════════════════════════════════════════════════════

    /**
     * {@code /ex fut positions} 的多行文本（{@code \n} 分隔）；没有持仓时返回一句提示。
     *
     * <p>只读，不抛异常：行情读不到时该行标注"行情不可用"。</p>
     */
    public static String positionsText(AimcContext ctx, Account account) {
        if (ctx == null || account == null) {
            return "期货持仓不可用（账户还没加载好）";
        }
        try {
            List<FuturePosition> positions = ctx.futures.of(account.uuid);
            if (positions.isEmpty()) {
                return "你当前没有期货持仓。用 /ex fut quote <标的> 看报价，/ex fut long|short <标的> <手数> <到期> 开仓。";
            }
            long day = ctx.tradingDay();
            long totalEquity = 0L;
            long totalPnl = 0L;
            StringBuilder text = new StringBuilder();
            text.append("期货持仓 ").append(positions.size()).append(" 笔（1 手 = ").append(LOT_SIZE).append(" 个）：");
            for (FuturePosition position : positions) {
                text.append("\n#").append(position.id).append(' ').append(directionText(position)).append(' ')
                        .append(position.symbol).append(' ').append(position.lots).append(" 手｜第 ")
                        .append(position.expiryDay).append(" 交易日到期");
                long spot = spotCents(ctx, position.symbol);
                if (spot <= 0L) {
                    text.append("｜行情不可用（开仓 ").append(Cents.format(position.entryPriceCents)).append("）");
                    continue;
                }
                long daysLeft = Math.max(0L, position.expiryDay - day);
                long futuresPrice = FuturesStore.contractPrice(spot, daysLeft, ctx.settings.futuresCarry);
                long pnlTotal = position.pnlCents(futuresPrice);
                long pendingPnl = Math.subtractExact(pnlTotal, position.pnlCents(position.lastMarkPriceCents));
                long equity = Math.addExact(position.marginCents, pendingPnl);
                long maintenance = maintenanceCents(ctx, position, futuresPrice);
                totalEquity = Math.addExact(totalEquity, equity);
                totalPnl = Math.addExact(totalPnl, pnlTotal);
                text.append(daysLeft <= 0L ? "（今日交割）" : "（还剩 " + daysLeft + " 交易日）")
                        .append("\n   开仓 ").append(Cents.format(position.entryPriceCents)).append(" → F ")
                        .append(Cents.format(futuresPrice)).append(" 币/个｜浮盈 ")
                        .append(Cents.formatSigned(pnlTotal)).append(" 币｜权益 ")
                        .append(Cents.format(equity)).append(" 币｜维持 ")
                        .append(Cents.format(maintenance)).append(" 币")
                        .append(position.marginCallAtMs > 0L ? "｜追保中" : "");
            }
            text.append("\n合计权益 ").append(Cents.format(totalEquity)).append(" 币，累计浮动盈亏 ")
                    .append(Cents.formatSigned(totalPnl)).append(" 币。");
            return text.toString();
        } catch (RuntimeException error) {
            Log.warn("读取期货持仓文本失败（" + actorName(account) + "）", error);
            return "期货持仓读取失败，稍后再试。";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════════════════════

    /** 标的代码 → 商品目录 id 的现价（分）。目录里没有该物品时返回 0。 */
    private static long spotCents(AimcContext ctx, String symbol) {
        if (ctx == null || ctx.market == null || symbol == null) {
            return 0L;
        }
        String itemId = FuturesStore.SYMBOLS.get(symbol);
        if (itemId == null) {
            return 0L;
        }
        if (ctx.market.item(itemId) == null) {
            return 0L;
        }
        return Math.max(0L, TradeService.effectiveAskCents(ctx, itemId));
    }

    /** 按当前行情算持仓的合约价 F_now（daysLeft 至少按 0 算）。 */
    private static long currentMarkPriceCents(AimcContext ctx, FuturePosition position) {
        long spot = spotCents(ctx, position.symbol);
        long daysLeft = Math.max(0L, position.expiryDay - ctx.tradingDay());
        return FuturesStore.contractPrice(spot, daysLeft, ctx.settings.futuresCarry);
    }

    /** 维持保证金 = 名义价值 × futuresMaintenanceRatio。 */
    private static long maintenanceCents(AimcContext ctx, FuturePosition position, long priceCents) {
        return Cents.percent(position.notionalCents(priceCents), ctx.settings.futuresMaintenanceRatio);
    }

    /** 标的解析：支持代码、目录 id、中文别名（如 "铁锭"）。找不到返回 null。 */
    private static String resolveSymbol(AimcContext ctx, String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (FuturesStore.SYMBOLS.containsKey(upper)) {
            return upper;
        }
        if (ctx == null || ctx.market == null) {
            return null;
        }
        MarketStore.CatalogItem item = ctx.market.resolve(raw);
        if (item == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : FuturesStore.SYMBOLS.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(item.id)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 到期天数只允许 7 / 14 / 30（与 {@link FuturesStore#EXPIRY_DAYS} 一致）。 */
    private static boolean isExpiryDays(int expiryDays) {
        for (int days : FuturesStore.EXPIRY_DAYS) {
            if (days == expiryDays) {
                return true;
            }
        }
        return false;
    }

    private static String directionText(FuturePosition position) {
        return position.longPosition ? "做多" : "做空";
    }

    private static String playerName(AimcContext ctx, FuturePosition position) {
        if (position.playerName != null && !position.playerName.isBlank()) {
            return position.playerName;
        }
        Account account = ctx == null ? null : ctx.accounts.get(position.uuid);
        return account == null ? position.uuid : actorName(account);
    }

    private static void addNotice(AimcContext ctx, String notice) {
        if (ctx != null && ctx.notices != null && notice != null && !notice.isBlank()) {
            ctx.notices.add(notice);
        }
    }

    private static BalanceAccount ledgerAccount(AimcContext ctx, String accountId) {
        BalanceAccount account = ctx.ledger.find(accountId);
        if (account == null) {
            throw new IllegalStateException("记账账户还没就绪：" + accountId);
        }
        return account;
    }

    private static String displayName(MarketStore.CatalogItem item) {
        if (item == null) {
            return "";
        }
        return item.nameZh == null || item.nameZh.isBlank() ? item.id : item.nameZh;
    }

    private static String actorName(Account account) {
        if (account == null) {
            return "系统";
        }
        return account.name == null || account.name.isBlank() ? account.uuid : account.name;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static String brief(RuntimeException error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "内部错误，请稍后再试" : message;
    }
}
