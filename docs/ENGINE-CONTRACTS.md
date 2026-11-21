# 引擎层内部契约（给并行开发/后续维护用）

> 这份文件描述 `engine/` 与 `ai/` 各静态方法的签名与语义。命令层、tick 调度、单测都按它调用。
> 所有引擎都是**无状态静态方法**，第一个参数是 `AimcContext`；引擎层**不许 import Minecraft 类**
> （否则单测跑不了）。需要碰玩家背包/发消息的，只返回 `ActionResult`，由命令层落地。

## 公共类型

- `engine.ActionResult`：`ok` / `message`（给玩家看的中文） / `broadcast`（全服公告，可空串） /
  `newsHeadline`（要写进 news 的标题，可空串）。`ok(msg)` / `ok(msg,broadcast,news)` / `fail(msg)`。
- 金额一律 `long` 分；比率用 `double`（0.05 = 5%）。
- 时间口径：`ctx.tick()` = market tick 序号，`ctx.tradingDay()` = 交易日序号，`ctx.nowMs()` = 现实毫秒。

## PriceEngine（`engine/PriceEngine.java`）

```java
// 每个 market tick：商品价格随机游走 + 均值回归 + 冲击 + 事件乘数，并夹在 [base*0.25, base*4]
public static void tickPrices(AimcContext ctx);
// 每个 market tick：股票价格同上，叠加行业景气、财报漂移、公司事件乘数；然后重算大盘指数
public static void tickStocks(AimcContext ctx);
```

## TradeService（`engine/TradeService.java`）

```java
public static long effectiveAskCents(AimcContext ctx, String itemId);   // 含点差 + 政府补贴/关税
public static long effectiveBidCents(AimcContext ctx, String itemId);
public static ActionResult buy(AimcContext ctx, Account account, String itemId, int count);
public static ActionResult sell(AimcContext ctx, Account account, String itemId, int count);
```

- `buy`：校验 catalog/enabled、数量 1-576、金额 ≤ maxOrderValue、余额、3 秒/单/人、`adminFreeze`；
  钱从钱包付给系统（**销毁**，因为点差是货币回收），价格用 `effectiveAskCents`。
  成功返回消息里带单价、总额、余额；命令层负责发物品（背包满掉脚下）。
- `sell`：回收价 `effectiveBidCents`，系统向玩家**增发**。命令层先扣物品再调用；
  引擎按 catalog 白名单校验。成功消息带单价、总额、余额。

## IncomeService（`engine/IncomeService.java`）

```java
public static ActionResult work(AimcContext ctx, Account account);          // /ex work
public static ActionResult daily(AimcContext ctx, Account account);         // /ex daily
public static long applySalaryIfDue(AimcContext ctx, Account account);      // 每个 tick 调，返回发了多少钱
```

- 工资：每 `salaryMinutes` 分钟发 `salaryAmountCents`，每现实日最多 8 次；下线不累计。
- 打工：冷却 `workCooldownMinutes`，随机 `[workMin, workMax]`，5% 双倍，每现实日最多 20 次。
- 签到：每现实日 1 次，连续签到 +1 格，10/15/20/25/30 币封顶，断签重置；金额 = `dailyBase + (streak-1)*dailyStep` 封顶 `dailyMax`。

## FinanceService（`engine/FinanceService.java`）

```java
// 股票（T+0，整数股；手续费 0.2% 最低 1 币 + 交易税进政府预算）
public static ActionResult buyStock(AimcContext ctx, Account account, String code, long shares);
public static ActionResult sellStock(AimcContext ctx, Account account, String code, long shares);
// 基金（下个 tick 净值成交）
public static ActionResult buyFund(AimcContext ctx, Account account, String code, long amountCents);
public static ActionResult sellFund(AimcContext ctx, Account account, String code, long unitsMilli);
// 银行
public static ActionResult deposit(AimcContext ctx, Account account, long amountCents);
public static ActionResult withdraw(AimcContext ctx, Account account, long amountCents);
public static ActionResult termDeposit(AimcContext ctx, Account account, int days, long amountCents);
public static ActionResult takeLoan(AimcContext ctx, Account account, long amountCents);
public static ActionResult repayLoan(AimcContext ctx, Account account, long amountCents);
// 每个 tick：活期计息；每个交易日：定期到期 + 贷款计息
public static void accrueInterest(AimcContext ctx, long minutes);
public static void onTradingDay(AimcContext ctx);
// 分红：每 7 交易日，NPC 股票按年化股息率增发，玩家公司从公司现金按次分红
public static void payDividends(AimcContext ctx);
```

## CompanyEngine（`engine/CompanyEngine.java`）

```java
public static void onTradingDay(AimcContext ctx);                        // 日结 + 估值 + 每 7 交易日分红/财报
public static JsonObject buildReportContext(AimcContext ctx, String code);
public static void applyReport(AimcContext ctx, String code, JsonObject data);  // AI 或本地模板
public static ActionResult create(AimcContext ctx, Account ceo, String name, String sector);
public static ActionResult hire(AimcContext ctx, Account ceo, CompanyStore.Company company, int count);
public static ActionResult levelUp(AimcContext ctx, Account ceo, CompanyStore.Company company);
// 其余 company 子命令在命令层用 CompanyStore 方法直接做（存/取/分红比例/关闭）
```

## IpoEngine（`engine/IpoEngine.java`）

```java
public static boolean canApply(AimcContext ctx, CompanyStore.Company company, StringBuilder whyNot);
public static JsonObject buildProspectusContext(AimcContext ctx, CompanyStore.Company company, double floatRatio);
public static ActionResult apply(AimcContext ctx, Account ceo, double floatRatio, JsonObject prospectus); // 申请+公告
public static ActionResult confirm(AimcContext ctx, Account ceo);   // 1 交易日后确认上市
public static ActionResult status(AimcContext ctx, CompanyStore.Company company);
public static void onTradingDay(AimcContext ctx);                   // 解锁、退市检查
```

## FuturesEngine（`engine/FuturesEngine.java`）

```java
public static ActionResult open(AimcContext ctx, Account account, String symbol, boolean longPosition, long lots, int expiryDays);
public static ActionResult close(AimcContext ctx, Account account, String positionId);
public static ActionResult quote(AimcContext ctx, String symbol, int expiryDays);   // 文本
public static ActionResult addMargin(AimcContext ctx, Account account, String positionId, long amountCents);
public static ActionResult withdrawMargin(AimcContext ctx, Account account, String positionId, long amountCents);
public static void onMarketTick(AimcContext ctx);   // 盯市、追保、5 分钟后强平、到期现金交割
```

## OptionsEngine（`engine/OptionsEngine.java`）

```java
public static long blackScholesCents(double spotCents, double strikeCents, double rate, double sigma, double years, boolean call);
public static ActionResult quote(AimcContext ctx, String code, long strikeCents, int expiryDays);
public static ActionResult open(AimcContext ctx, Account account, boolean writer, String callOrPut, String code, long strikeCents, int expiryDays, long contracts);
public static ActionResult close(AimcContext ctx, Account account, String positionId);
public static void onMarketTick(AimcContext ctx);   // 卖方保证金盯市、追保/强平、到期结算
```

## GovernmentEngine（`engine/GovernmentEngine.java`）

```java
public static void updateIndicators(AimcContext ctx, java.util.Collection<Account> onlineAccounts);
public static int stressScore(AimcContext ctx);                                  // 0-100
public static boolean shouldAskAi(AimcContext ctx, long nowMs);                  // 间隔 + 压力分 + 黑天鹅
public static JsonObject buildRequest(AimcContext ctx);                          // task=government 的 context
public static JsonObject localFallback(AimcContext ctx);                         // 本地规则表兜底
public static ActionResult applyDecision(AimcContext ctx, JsonObject decision, boolean fromAi);  // 校验+落地+广播
public static void onPlayerMarketAction(AimcContext ctx, Account actor);         // 每次 /ex 调用做本地体检
```

## NewsEngine（`engine/NewsEngine.java`）

```java
public static JsonObject buildNewsContext(AimcContext ctx);
public static String applyAiNews(AimcContext ctx, JsonObject data);   // 写 news.json + 给 MarketStore 挂 effects，返回标题
public static String generateLocalNews(AimcContext ctx);              // AI 不可用时的内置模板
public static void publish(AimcContext ctx, String category, String headline, String body); // 财报/IPO/破产/政策快讯
```

## AI 层（`ai/`）

- `ai.AiGateway`（已存在）：`parse/news/report/prospectus/government/ping/lastRoute`，同步阻塞调用。
- `ai.MarketAi`：实现 AiGateway。三级降级：`/mc-exchange`（task 严格 JSON）→ `/mc-ai`（文本模板 + 本地抠 JSON）
  → 本地解析/本地规则（`LocalParser`）。超时/连接失败/`ok:false` 一律降级，不能抛异常到调用方。
- `ai.AiJson`：JSON 修复 + schema 校验（intent 枚举、物品 id、数量 1-576、金额正整数、政策 action/params 边界）。
- `ai.LocalParser`：中文数字、动词、catalog 别名最长匹配，把"买 3 个铁锭"解析成 `parse` 同形状 JSON。
- 所有 AI 调用必须能注入假实现（单测用），并且**异步执行**：模组侧用 `AiExecutor`（daemon 线程池）跑
  `AiGateway` 的同步方法，完成后把回调排进主线程队列执行。

### 与插件 0.7.0 对齐的枚举（2026-11-21 插件实现落地后更新，模组必须照这个来）

`parse.data.intent` 只允许：

```
buy sell price balance work daily pay
stock_buy stock_sell stock_info
fund_buy fund_sell fund_info
deposit withdraw bank_loan
company_create ipo_apply
futures_long futures_short
options_buy options_sell
gov_status help unknown
```

交叉约束：`buy/sell` 必须有 `items`（1-576 个/项，id 必须 catalog 里 enabled）；
`stock_buy/stock_sell` 必须有 `symbol` + `shares≥1`；`pay/deposit/withdraw/fund_buy` 必须 `amountCents≥1`。

`government.action` 参数边界（插件服务端也按这个校验，模组侧再校验一次）：

| action | 边界 |
| --- | --- |
| rate_cut / rate_hike | `bp ∈ {25,50,75,100}` |
| welfare | `amountCents` 1–50000 |
| stimulus | `amountCents ≥ 1`、`sectors` 1–10 个字符串、`days` 1–30 |
| austerity | `ratio` 0–0.02 |
| subsidy / tariff | `target` 物品 id、`ratio` 0–0.2、`days` 1–30 |
| liquidity | `ratio` 0–1 |
| min_wage | `amountCents` 1000–5000 |
| none | `params` 必须是 `{}` |
