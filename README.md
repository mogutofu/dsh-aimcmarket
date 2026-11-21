# AIMCmarket

Forge 1.20.1 服务端模组：给服务器加一个模拟金融市场，玩家用 `/ex` 跟“市场先生”打交道——买卖物品、炒股、买基金、存贷款、开公司、IPO、做期货期权，AI 顺带扮政府和央行。纯服务端，客户端不用装任何东西。

当前版本 0.1.0，MIT 许可。

## 为什么做这个

多人服玩到中后期，常见的尴尬是钱没地方去：绿宝石除了跟村民换东西没别的用途，玩家之间交易也缺一个统一参照价。AIMCmarket 想补这块——给物品一个会波动的价格，再把股票、基金、银行、公司这些金融玩法叠上去，让赚钱和亏钱都有地方发生。

设计上做了几个取舍：

- **AI 不碰钱**：自然语言解析、新闻、财报、招股书、政策建议交给 AI；价格、余额、扣款、发货、保证金、交割、政策落地全部由模组本地算和校验。AI 返回的 JSON 要先过 schema 和边界检查，不合法的直接丢掉走本地兜底。
- **纯服务端**：没有客户端 GUI，不用 mixin，不引第三方运行时依赖（Gson 用 MC 自带的，HTTP 用 `HttpURLConnection`）。客户端不装这个 mod 也能正常进服。
- **AI 可选**：只对接本机 DSH 插件 `dsh-mc-server-ops`。插件没装、接口没开、超时、返回坏 JSON，都会落到本地解析器和内置模板，不报错也不卡服。`ai.mode=local` 或者 `ai.enabled=false` 也能玩完整套经济，只是文案没那么活。
- **三套时间**：工资、打工冷却、签到按现实时间；价格、利息、盯市按市场 tick（默认 5 现实分钟）；财报、分红、定期、期货期权到期按交易日（默认 60 现实分钟）。金融模块里的“天”一律指交易日，收入模块里指现实天。玩家最容易在这里问“为什么我的定期还没到期”，所以文档和代码注释里都写死了口径。

## 安装

要求 Forge 1.20.1 服务端 + Java 17。模组只依赖 Forge/Minecraft，不需要别的 mod。

1. 把 `aimcmarket-forge-1_20_1-v0_1_0.jar` 放进服务端 `mods/`。
2. 启动服务器。首次启动会生成：
   - `config/aimcmarket-common.toml`：配置
   - `config/aimcmarket/catalog.json`：商品目录，内置一份 135 项的常见物品表
   - `world/aimcmarket/`：账户、行情、公司、合约、账本这些数据
3. 客户端不用装，也不建议装——它没有客户端内容，装了也看不到额外界面。

AI 部分可选：装 `dsh-mc-server-ops` 0.7.0，在 DSH 设置面板里打开“AI 市场接口（AIMCmarket）”。不装的话功能都在，自然语言解析、新闻、财报、政策走本地实现。

升级直接换 jar。数据文件带 `"schema":1`，读的时候缺字段用默认值补；文件坏了会改名备份后重置，不会导致服务器启动失败。

## 快速上手

第一次进服给 500 币初始资金（可配）。先看帮助和行情：

```
/ex
/ex board
/ex news
```

做点基础操作：

```
/ex work                 # 打工：10 分钟冷却，随机 30–80 币，每天最多 20 次
/ex daily                # 每日签到：每天递增 10 币，第 3 天起 30 币封顶，断签重置
/ex buy iron_ingot 3     # 按买价买 3 个铁锭
/ex sell iron_ingot 3    # 按回收价卖回系统（现价的 60%）
/ex price iron_ingot     # 看现价、买价、回收价
/ex 我要买 64 个齿轮      # 目录没有的物品：AI 先估价上市，再按市价扣钱给物品
/ex id                   # 看主手物品的 id（服务端认不出中文名时用）
/ex 我要买 1 个手上的     # 直接买主手拿的物品（任意模组物品都行）
/ex alias 反坚守者音爆弹  # 主手拿着物品，把中文名绑上去，以后就能按名字买
/ex return 火箭燃料罐 1   # 退货：7 天内在本市场买的，按原价退钱
/ex 把背包里的火箭燃料罐收购掉  # 收购：按当前市场价卖给市场
/ex 现在行情怎么样         # 随便问，AI 只读回答（股票/行情/政策/余额）
```

也可以直接说人话：

```
/ex 买 3 个铁锭
/ex 买 10 股 BLK
/ex 存 100 币
```

自然语言是「先本地解析，解析不出来再问 AI」；`/ex ai <话>` 可以强制走 AI。结构化子命令永远不依赖 AI。

## 命令

`/ex`、`/exchange`、`/market` 三个根命令等价。这里列常用用法，完整参数范围看 `docs/DESIGN.md`。

### 账户与收入

| 命令 | 说明 |
| --- | --- |
| `/ex` / `/ex help` | 帮助 + 余额 + 大盘 |
| `/ex balance` | 余额、活期、定期、贷款、股票/基金市值、信用分 |
| `/ex pay <玩家> <金额>` | 给别的玩家转账 |
| `/ex work` | 打工 |
| `/ex daily` | 每日签到 |
| `/ex history` | 最近 12 条自己账户的流水 |
| `/ex top` | 资产排行前 10 |

### 现实股票同步

config/aimcmarket/real-stocks.json 默认带 7 只美股（AAPL/MSFT/NVDA/TSLA/GOOG/AMZN/META）和 5 只大 A（贵州茅台/宁德时代/比亚迪/招商银行/中国平安），每 5 分钟从腾讯行情接口拉真实价格写进游戏股市（multiplier=100 表示现实 1 块钱 = 1 币）。现实股票不参与游戏内随机波动，价格跟着现实走；也可以直接用中文名交易：/ex stock buy 宁德时代 10，或自然语言「帮我买10股宁德时代」（AI 会自己认代码）；/ex stock list 里会标 ·现实，/ex stock info AAPL 显示同步时间。手动同步：/ex admin syncstocks。

### 商品市场（任意物品按需上市）

目录里没有的物品不用改配置：AI 先查目录，没有再按稀有度/堆叠数/参考价估一个合理价，写进 config/aimcmarket/catalog.json 上市，然后按市价扣钱、给物品；之后它和内置商品一样受价格引擎（均值回归 + 随机波动 + 交易冲击 + AI 新闻事件）影响，动态商品波动率默认 5%，所以价格会明显变化。

退货/收购：自然语言说「把背包里的 X 收购掉」按当前市场价卖；说「退货 X N」/  用 /ex return <物品> [数量] 按原买入价退款（7 天内、物品还在背包里）。

客户端汉化名服务端认不出来时：把物品拿在主手，用 /ex 买手上的 N 直接买；或者 /ex id 查 id 后 /ex buy <id> N；也可以用 /ex alias <中文名> 把名字绑到主手物品上，以后按中文名买卖。

### 商品市场

| 命令 | 说明 |
| --- | --- |
| `/ex buy <物品> <数量>` | 数量 1–576，单笔 ≤ 5000 币 |
| `/ex sell <物品> <数量>` | 按买价的 60% 回收，背包里要有 |
| `/ex price <物品>` | 现价 / 买价 / 回收价 / 基准价 / 流动性 |
| `/ex board [页]` | 行情列表，每页 8 行，带快捷买入按钮 |
| `/ex news [页]` | 市场新闻 |

物品名支持 catalog 里的 id、中文名和别名：`iron_ingot`、`铁锭`、`铁` 都行。

### 股票与基金

| 命令 | 说明 |
| --- | --- |
| `/ex stock list [页]` | 股票列表，含大盘指数 |
| `/ex stock buy <代码> <股数>` / `/ex stock sell <代码> <股数>` | T+0，整数股，手续费 0.2%（最低 1 币）+ 交易税 |
| `/ex stock info <代码>` | 价格、涨跌幅、持仓、分红、最近财报 |
| `/ex fund list [页]` | 基金列表和净值 |
| `/ex fund buy <代码> <金额>` | 按下一 tick 净值申购，费率 0.5% |
| `/ex fund sell <代码> <份额>` | 赎回，份额可带小数 |
| `/ex fund info <代码>` | 基金详情和自己的持仓 |

NPC 股票有 10 只：`BLK` 方块重工、`RED` 红石科技、`NTL` 下界能源、`END` 末地物流、`EMR` 绿宝石银行、`GRN` 绿野农业、`MED` 药水制药、`ARS` 红石军械、`BOK` 附魔书屋、`ORB` 轨道航运。基金有 `FND01` 稳健、`FND02` 平衡、`FND03` 激进。

### 银行

| 命令 | 说明 |
| --- | --- |
| `/ex bank` / `/ex bank balance` / `/ex bank info` | 活期、定期、贷款、信用分 |
| `/ex bank deposit <金额>` / `withdraw <金额>` | 存取活期 |
| `/ex bank term <天数> <金额>` | 定期，天数只支持 7 或 30 交易日，到期自动回活期 |
| `/ex bank loan take [金额]` | 贷款；不写金额就借额度内最多 1000 币 |
| `/ex bank loan repay <金额>` | 还款 |

活期利率 = 政策利率 − 1.5%（下限 0.1%），按分钟计息；贷款年化 = 政策利率 + 8%，按交易日计息；信用分初始 600，按时还款 +5，逾期 −30。

### 公司、IPO 与再融资

| 命令 | 说明 |
| --- | --- |
| `/ex company create <名字> <行业>` | 费用 5000 币；名字 2–16 字；行业五选一 |
| `/ex company list` / `/ex company info [公司]` | 公司列表 / 详情；破产历史显示在列表末尾 |
| `/ex company deposit <公司> <金额>` / `/ex company withdraw <公司> <金额>` | CEO 向公司注入或取出资金 |
| `/ex company hire <公司> <人数>` / `/ex company fire <公司> <人数>` | 招聘 200 币/人，日薪 20 币/人；裁员不退招聘费 |
| `/ex company levelup <公司>` | 升级，需要累计营收门槛和升级费 |
| `/ex company sector <公司> <行业>` | 换行业，2000 币，7 交易日冷却 |
| `/ex company dividend <公司> <0-80>` | 设分红比例，按百分比写，比如 30 |
| `/ex company raise <公司> <金额>` | 上市后增发再融资，按当前股价 9 折，冷却 7 交易日 |
| `/ex ipo status [公司]` / `/ex ipo apply <0.10-0.49>` / `/ex ipo confirm` | IPO 状态 / 申请（流通比例写小数，0.25 = 25%）/ 确认上市 |

行业是 `采矿`、`农业`、`制造`、`贸易`、`服务`，创建公司时必须选一个。公司每交易日结算营收，现金为负且连续 3 个交易日亏损会破产；CEO 持股默认 100%，IPO 后锁定 30 交易日。

### 期货与期权

| 命令 | 说明 |
| --- | --- |
| `/ex fut list` | 五个标的的现货价；1 手 = 64 个物品 |
| `/ex fut quote <标的> <天数>` | 看各到期日的合约价，天数参数只是高亮用 |
| `/ex fut long <标的> <手数> <天数>` / `/ex fut short <标的> <手数> <天数>` | 开多/开空，天数只能是 7/14/30 交易日 |
| `/ex fut close <id>` | 平仓 |
| `/ex fut positions` | 自己的持仓和保证金 |
| `/ex fut margin add <id> <金额>` / `/ex fut margin withdraw <id> <金额>` | 追加 / 提取保证金 |
| `/ex opt list [页]` | 可用作期权标的的股票 |
| `/ex opt quote <代码> <行权价> <天数>` | BS 报价，含 call/put、delta |
| `/ex opt buy <call 或 put> <代码> <行权价> <天数> <张数>` | 买方付权利金；天数 7/14/30 |
| `/ex opt sell <call 或 put> <代码> <行权价> <天数> <张数>` | 卖方收权利金并缴保证金；天数 7/14/30 |
| `/ex opt close <id>` / `/ex opt positions` | 提前平仓 / 持仓 |

期货标的是 `IRON`、`GOLD`、`DIAMOND`、`EMERALD`、`WHEAT`，现金交割。期权标的是所有已上市股票，1 张 = 100 股，行权价必须在现价 ±10% 以内，到期 7/14/30 交易日，欧式现金交割。

### 政府、AI 与管理

| 命令 | 说明 |
| --- | --- |
| `/ex gov status` | 利率、税率、预算、通胀、大盘、就业、压力分、货币总量 |
| `/ex gov budget` / `/ex gov policies` / `/ex gov history` | 预算 / 生效中政策 / 最近政策记录 |
| `/ex ai <自然语言>` | 强制走 AI 解析 |
| `/ex <自然语言>` | 先本地解析，失败再 AI |
| `/ex admin setprice <物品> <价格>` | OP：改现价 |
| `/ex admin give <玩家> <金额>` / `/ex admin take <玩家> <金额>` | OP：发钱 / 扣钱，都写账本 |
| `/ex admin freeze true` / `false` | OP：冻结新下单 / 恢复 |
| `/ex admin reload` | OP：热加载 TOML 和商品目录 |
| `/ex admin save` | OP：立刻落盘 |
| `/ex admin stats` | OP：总货币、账本平衡、重放核对、资产 Top 10、AI 链路 |
| `/ex admin selftest` | OP：跑全模块自检，输出 `SELFTEST PASS/FAIL` |
| `/ex admin gov set <字段> <值>` | OP：改 `policyRate` / `tradeTax` / `companyTax` / `budget` |

控制台能跑只读和管理命令（`board`、`news`、`stock list`、`stock info`、`fund list`、`gov status`、`admin stats`、`admin selftest` 等）；需要玩家身份的命令会提示“只能游戏内用”。

## 配置

配置在 `config/aimcmarket-common.toml`，改完跑 `/ex admin reload` 生效，不用重启。常改的几项：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `general.enabled` | `true` | 模组总开关，关掉后 `/ex` 只剩只读命令 |
| `ai.enabled` | `true` | 是否允许调用 AI |
| `ai.mode` | `auto` | `auto` / `exchange` / `legacy` / `local` |
| `ai.endpoint` | `http://127.0.0.1:8765` | DSH 插件地址 |
| `ai.timeoutSeconds` | `5` | 单次 AI 调用超时（秒） |
| `ai.hourlyBudget` | `60` | 全局 AI 预算（次/小时） |
| `ai.governmentEnabled` | `true` | 是否让 AI 参与政府决策 |
| `ai.governmentCheckMinutes` | `5` | 两次 AI 政府决策的最小间隔（现实分钟） |
| `market.tickMinutes` | `5` | 市场 tick 间隔（现实分钟） |
| `market.tradingDayMinutes` | `60` | 一个交易日的现实分钟数 |
| `market.askSpread` | `0.02` | 买价 = 现价 ×(1+askSpread) |
| `market.bidRatio` | `0.60` | 回收价 = 现价 × bidRatio |
| `market.maxOrderValue` | `500000` | 单笔订单上限（分，500000 = 5000 币） |
| `economy.startingBalance` | `50000` | 新玩家初始资金（分，50000 = 500 币） |
| `economy.salaryMinutes` | `30` | 在线工资间隔（现实分钟） |
| `economy.salaryAmount` | `5000` | 每次在线工资（分，5000 = 50 币） |
| `economy.policyRate` | `0.03` | 初始政策利率（0.03 = 3%） |
| `finance.fundSubscribeFee` | `0.005` | 基金申赎费率 |
| `finance.futuresMarginRatio` | `0.10` | 期货初始保证金比例 |
| `finance.futuresMaintenanceRatio` | `0.05` | 期货维持保证金比例 |
| `finance.optionsMarginRatio` | `0.20` | 期权卖方保证金比例 |
| `company.createFeeCents` | `500000` | 创建公司费用（分，500000 = 5000 币） |
| `admin.freeze` | `false` | 冻结新下单 |

打工金额、签到阶梯、公司日结公式这类数值写死在 `MarketSettings` 里，TOML 里没有，要改得改代码重新编译。0.1.0 先把常调的参数暴露出来，冷门参数等真有人去调再说。

## AI 接入

模组有三条链路，依次降级：

1. `POST /mc-exchange`：插件 0.7.0 且开关打开时用，严格 JSON，`task` 分 `parse` / `news` / `report` / `prospectus` / `government`；
2. `POST /mc-ai`：插件 0.6.x 或 exchange 关闭/失败时用，自然语言，模组自己从回复里抠 JSON；
3. 本地解析器 + 本地规则表：不联网也能用。

`ai.mode` 可以锁死在某条链路上，默认 `auto` 按上面的顺序试。

打开 exchange 的方式：DSH 设置 →「MC 服务器」→「AI 市场接口（AIMCmarket）」→ 打开。热生效，不用重启 DSH。验证一下：

```bash
curl -s http://127.0.0.1:8765/mc-exchange -H 'content-type: application/json' -d '{"task":"ping"}'
```

模组侧不用动配置，默认就开着。关掉插件开关后模组自动回落到 `/mc-ai`；两样都不通就走本地。

接口契约、字段、状态码和降级细节在 [`docs/AI-PROTOCOL.md`](docs/AI-PROTOCOL.md)。这里只强调一条设计底线：AI 只提供自然语言到订单参数的翻译、新闻/财报/招股书文案、政府政策建议，所有涉及钱和库存的操作都在模组里重新算一遍。

## 数据与安全

所有运行期数据在 `world/aimcmarket/`，共 11 个文件：

```text
accounts.json    market.json    stocks.json    funds.json    bank.json
companies.json   futures.json   options.json   government.json   news.json
ledger.jsonl
```

- **复式记账**：余额只能经过 `Ledger` 改，每笔变动写两条腿到 `ledger.jsonl`，所有账户（含系统发行账户）余额之和恒为 0。`/ex admin stats` 会跑账本恒等式和重放核对，`/ex admin selftest` 里也有专门一项查对账。
- **原子写**：写文件走“临时文件 + 原子 move”，每个市场 tick 和停服时落盘；写失败记日志，下个 tick 重试。
- **坏文件不崩服**：读不出来的 JSON 会改名成 `xxx.json.broken-<时间戳>` 再按默认值重置，服务器照常启动。
- **金额不用浮点**：内部用 `long` 存分（1 币 = 100 分），加减乘走 `Math.*Exact`，溢出当 bug 抛出，不静默截断。
- **防刷**：商品下单每人 1 单/3 秒、单笔上限 5000 币、单物品 576 个；AI 解析每人 1 次/2 秒、全局 60 次/小时；玩家消息进 AI 前截断 200 字符、去换行；AI 回复只当建议。
- **管理操作**：`/ex admin ...` 需要 OP（permission level 2），发钱、扣钱、改价都会写账本并带操作者名字。

备份的话，整个 `world/aimcmarket/` 目录复制走就行，都是纯 JSON/JSONL，出问题可以直接打开看。

## 已知限制

0.1.0 明确不做/简化的东西：

- 不做客户端 GUI，只有聊天和原版背包界面；不做真实联网行情、外部交易 API、数据库。
- NPC 公司的财报数字是围绕股价和行业景气合成的，不是真财务模型；基金也不真持有股票，组合收益是指数收益 + 债券收益 + 噪声。
- 期货和期权的对手方永远是做市商，不撮合玩家对玩家，所以这里说的“零和”是玩家盈亏 + 做市商盈亏之和为零。
- 债券、保险、并购、收藏品不在 0.1.0 里；限价单也没做。
- 期货、期权到期只有 7/14/30 交易日三档；期权行权价必须在现价 ±10% 以内，不能随便填。
- 公司创建时必须选行业，换行业花钱且有冷却；IPO 用你名下第一家公司的上下文。
- 这是单服内存经济：数据全在内存里，定时写 JSON。适合几十人的服，玩家规模上去以后内存和文件体积要自己评估。

## 测试状态

不想把没做过的事写成做过，所以分开说：

- **CI**：GitHub Actions 跑 `gradle build`（含 JUnit 5 单测）和 jar 元数据校验——检查 `modId`、版本、`displayTest="IGNORE_ALL_VERSION"`，以及 jar 里没有混进客户端类。单测覆盖引擎和 AI JSON 校验这些不碰 Minecraft 类的逻辑，实际结果以仓库 Actions 页为准。
- **运行期自检**：`/ex admin selftest` 覆盖价格引擎、买卖/幂等/冻结、股票、基金、银行、公司日结与破产、IPO 定价/稀释/退市、期货盯市与交割、期权 BS 定价与结算、政府政策边界与冷却、账本对账、AI 坏样本校验、落盘重载。
- **生产服**：在 1.20.1 Forge 47.2.23 的生产服上装过，跑过 `/ex admin selftest`、`/ex board`、`/ex stock list`、`/ex gov status`。同一台机器上还跑着 mc-ai，命令前缀不冲突（`/ai`、`/weather` 归它，`/ex` 归本模组）。
- **插件侧**：`/mc-exchange` 的 `ping`、`parse`（3 个样例）、`news`、`report`、`prospectus`、`government`（两次）、400 参数校验、429 限流，在真实 DSH 环境里过了一遍；`/mc-ai` 的行为没变。
- **没验的**：客户端——没有客户端代码可测，靠 `displayTest="IGNORE_ALL_VERSION"` 保证没装客户端 mod 的玩家能进服；没做连续几天的挂机长跑和高并发压力测试，AI 降级路径只有功能级验证，没有做长时间的不稳定网络测试。
- AI 链路是在本机 DSH 插件上测的，没接外部服务。模型偶尔吐不出合法 JSON，模组会拒绝并回落本地，不会因为 AI 抽风把账算错。

## 支持与许可证

| 项 | 要求 / 说明 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.2.23（loader 47.x） |
| Java | 17 |
| 客户端 mod | 不需要 |
| 其它前置 mod | 无 |
| 可选 DSH 插件 | `dsh-mc-server-ops` 0.7.0（AI 部分，默认关闭） |
| 数据目录 | `world/aimcmarket/` |
| 许可证 | MIT |

仓库：[mogutofu/dsh-aimcmarket](https://github.com/mogutofu/dsh-aimcmarket) · 问题反馈：[Issues](https://github.com/mogutofu/dsh-aimcmarket/issues) · 经济模型和公式：[`docs/DESIGN.md`](docs/DESIGN.md) · AI 协议：[`docs/AI-PROTOCOL.md`](docs/AI-PROTOCOL.md) · 配套插件：[mogutofu/dsh-mc-server-ops](https://github.com/mogutofu/dsh-mc-server-ops)
