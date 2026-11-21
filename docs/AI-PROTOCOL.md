# AI 协议：AIMCmarket ↔ dsh-mc-server-ops

AIMCmarket 的 AI 能力全部走本机 DSH 插件 `dsh-mc-server-ops` 的本地 HTTP 接口，
默认地址 `http://127.0.0.1:8765`。插件是可选依赖：没装、没开、超时、返回坏 JSON，
模组都会降级到下一级，**不会卡服、不会报错到玩家面前**。

## 0. 三级降级链

模组配置 `config/aimcmarket-common.toml` 里：

```toml
[ai]
enabled = true                                  # 总开关
mode = "auto"                                   # auto | exchange | legacy | local
endpoint = "http://127.0.0.1:8765"
exchangePath = "/mc-exchange"
legacyPath = "/mc-ai"
timeoutSeconds = 5
hourlyBudget = 60
```

| 顺序 | 链路 | 什么时候用 |
| --- | --- | --- |
| 1 | `POST /mc-exchange`（严格 JSON） | 插件 0.7.0 且 `exchange.enabled=true` |
| 2 | `POST /mc-ai`（自然语言，文本回复里抠 JSON） | 插件 0.6.x / 0.7.0 但 exchange 关闭 / exchange 超时坏 JSON |
| 3 | 本地解析器 + 本地规则表 | DSH 没开、超时、预算用完、AI 关闭 |

- `mode="exchange"`：只走 1，失败直接本地。
- `mode="legacy"`：只走 2。
- `mode="local"`：不走网络。
- `mode="auto"`（默认）：1 → 2 → 3。

固定身份（不要用玩家 UUID）：

```json
{"player":"AIMCmarket","uuid":"00000000-0000-0000-0000-00000000a1c0"}
```

## 1. `POST /mc-exchange`（插件 0.7.0，默认关闭）

请求头 `Content-Type: application/json`：

```json
{"task":"ping|parse|news|report|prospectus|government",
 "player":"AIMCmarket",
 "uuid":"00000000-0000-0000-0000-00000000a1c0",
 "message":"玩家原话（≤200 字，模组先截断去换行）",
 "context":{...}}
```

状态码与响应：

| 情况 | 状态码 | 响应 |
| --- | --- | --- |
| 功能关闭（默认） | `404` | `{"ok":false,"error":"exchange disabled"}`（不建会话、不花 token） |
| 参数不合法 | `400` | `{"ok":false,"error":"..."}` |
| 模型报错 / 超时 / JSON 修复失败 | `200` | `{"ok":false,"error":"...","raw":"..."}` |
| 正常 | `200` | `{"ok":true,"data":{...},"elapsedMs":123}` |
| 限流 | `429` | `{"ok":false,"error":"rate limited"}` |

### 1.1 `ping`

```json
{"ok":true,"data":{"version":"0.7.0","tasks":["parse","news","report","prospectus","government"]}}
```

### 1.2 `parse`（自然语言 → 订单参数）

`context` 建议带：`balanceCents`、`catalogCandidates`（物品 id 数组）、`stocks`（代码数组）、`funds`（代码数组）。

`data`：

```json
{"intent":"buy","items":[{"id":"minecraft:iron_ingot","count":3}],
 "symbol":"","shares":0,"amountCents":0,"reply":"买 3 个铁锭"}
```

`intent` 枚举（模组和插件共用这张表，改动两边一起改）：

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

交叉约束：`buy/sell` 必须有 `items`（1–576 个/项，id 必须形如 `minecraft:xxx` 且在目录里 enabled）；
`stock_buy/stock_sell` 必须有 `symbol` + `shares≥1`；`pay/deposit/withdraw/fund_buy` 必须 `amountCents≥1`。

### 1.3 `news`

```json
{"headline":"...","body":"...","effects":[{"target":"minecraft:iron_ingot","multiplier":1.08,"ticks":6}]}
```

### 1.4 `report`

```json
{"revenueCents":123456,"profitCents":23456,"outlook":"up|flat|down","headline":"..."}
```

### 1.5 `prospectus`

```json
{"title":"...","summary":"...","risk":"...","useOfFunds":"..."}
```

### 1.6 `government`

```json
{"action":"none|rate_cut|rate_hike|welfare|stimulus|austerity|subsidy|tariff|liquidity|min_wage",
 "params":{"bp":25,"amountCents":100000,"sectors":["工业"],"ratio":0.02,"days":3,"target":"minecraft:iron_ingot"},
 "reason":"一句话理由","announcement":"给玩家看的公告（可为空）"}
```

参数边界（插件服务端校验一遍，模组 `GovernmentEngine` 再校验一遍，任何一边不合法就拒绝）：

| action | 边界 |
| --- | --- |
| rate_cut / rate_hike | `bp ∈ {25,50,75,100}` |
| welfare | `amountCents` 1–50000（每人 ≤500 币） |
| stimulus | `amountCents ≥ 1`、`sectors` 1–10 个字符串、`days` 1–30 |
| austerity | `ratio` 0–0.02 |
| subsidy / tariff | `target` 物品 id、`ratio` 0–0.2、`days` 1–30 |
| liquidity | `ratio` 0–1 |
| min_wage | `amountCents` 1000–5000 |
| none | `params` 必须是 `{}` |

## 2. `POST /mc-ai`（旧链路，0.6.x 也有）

请求：

```json
{"player":"AIMCmarket","uuid":"00000000-0000-0000-0000-00000000a1c0","text":"..."}
```

响应：

```json
{"ok":true,"reply":"...","elapsedMs":123}
```

回复可能是自然语言，模组自己从里面抠 JSON：先剥 ` ```json ` 包裹，再取第一个括号配平的 `{...}`，
然后走和 `/mc-exchange` 一样的 schema 校验。抠不出来/校验不过 → 本地兜底。

`text` 模板（模组侧拼）：

```
你是 AIMCmarket 的市场先生。只输出一个 JSON 对象，不要解释、不要 markdown。
任务：<task>
上下文：<json>
输出格式：<schema>
```

## 3. 本地兜底

- **解析**：`ai/LocalParser` 支持中文数字（一二三四五六七八九十、两、半、百、千）、
  常见动词（买/卖/存/取/股/基金/开公司/上市/做多/做空/打工/签到/新闻/大盘/余额/价格）、
  catalog 中文名与别名最长匹配。解析不出来返回 `unknown`，提示 `/ex buy iron_pickaxe 3`。
- **政府**：`GovernmentEngine.localFallback` 按规则表兜底（通胀 >8% 加息 50bp；<0 降息 50bp；
  指数 7 日跌 >15% 流动性支持；预算 >5000 币且指数跌 → 刺激；就业 <0.4 → 福利）。
- **新闻/财报/招股书**：`NewsEngine.generateLocalNews`、`CompanyEngine.localReportData`、
  `IpoEngine` 内置模板。

## 4. 预算与安全

- 每玩家 AI 解析 1 次/2 秒（`aiPlayerMinIntervalMs`）；全局 60 次/小时（`hourlyBudget`）。
- 超出预算 → 只用本地逻辑，`/ex admin stats` 能看到最近命中的链路。
- 玩家消息进 AI 前截断 200 字符、去换行；AI 返回只当"建议"，必须过 schema 校验才生效。
- **AI 不能改钱**：价格、余额、扣款、发货、保证金、交割、政策落地全部在本地算并校验。
- 插件只监听 `127.0.0.1`，不接受外网请求；模组只请求配置的 endpoint。
- 日志只记 task、耗时、是否修复、成功/失败，不记玩家完整原话。

## 5. 怎么开（生产）

1. DSH 设置面板 → 插件 `dsh-mc-server-ops` → 「AI 市场接口（AIMCmarket）」→ 打开开关并保存（热生效，不用重启）。
2. `curl -s -X POST http://127.0.0.1:8765/mc-exchange -H 'content-type: application/json' -d '{"task":"ping"}'`
   应返回 `{"ok":true,"data":{"version":"0.7.0",...}}`。
3. 模组侧 `ai.enabled=true`、`ai.mode=auto`（默认），`/ex admin reload` 或重启服务器。
4. 关掉：设置面板关开关即可，`/mc-exchange` 恢复 404，模组自动回落到 `/mc-ai`。

## 6. 自测

插件仓库里带了一个纯 Node、无第三方依赖的测试脚本：

```bash
node test/mc-exchange.mjs
```

关闭状态应看到「404 exchange disabled」PASS、`/mc-ai` 路由仍在 PASS，HTTP 部分 SKIP；
打开后在面板里开 exchange 再跑一遍，应 0 FAIL。模组侧的对应用例在
`/ex admin selftest` 的「AI：20 组坏样本全部被拒」和「AI：正常样本与 markdown 包裹能解析」两项。
