# 公司级权益发放中台需求分析

> 视角：requirements-analyst。本文只确认需求、规则、边界和验收口径；仓库事实见 `02-codebase-analysis.md`。文中“已确认”只来自本次任务或仓库现状；设计默认值会显式标为“建议”，未给出的业务信息统一标为“待验证”。

## 1. 目标与一期价值

建设独立部署的 `benefit-center`，把“活动算出该给什么”与“渠道实际发出去”分离。中台对外接受版本化 `AwardIntent`，统一处理现金红包、优惠券、服务券（洗车券/加油券）、兑换码、实物和组合奖品；对内提供幂等、库存、渠道路由、重试、补发、冲正、不可变台账和对账闭环。

一期必须形成可运行闭环：

1. Drools 活动平台根据服务器端权威决策组装 `AwardIntent`，不信任客户端提交的权益 SKU、金额或渠道。
2. `benefit-center` 接单、逐奖项占库、异步履约、记录渠道凭证，并能返回 `SUCCEEDED`、`PARTIAL_SUCCESS` 或 `FAILED`。
3. 同一业务请求和同一渠道操作可安全重试，不重复发权益。
4. 中台库存与渠道库存双持；渠道明确无货时按配置切中台兜底，渠道结果未知时禁止盲目兜底。
5. `recon-platform` 从独立 ODS 对“预期奖项→中台分录→渠道/中台履约凭证”做三方对账，生成受控补发或冲正命令并回收执行结果。
6. 保留现有活动发放链路作为灰度/回滚路径，存量账不搬迁、不重放。

## 2. 已确认业务规则

以下规则由任务直接确认，或是实现这些要求不可缺少且与现有代码不变量一致的规则。

| 编号 | 已确认规则 | 结果口径 |
|---|---|---|
| R1 | `benefit-center` 是独立服务，不并入 `drools-demo` 或 `recon-platform` | 独立进程、数据库、发布与扩缩容；活动和 recon 只经契约接入 |
| R2 | 支持五种原子权益及组合奖品 | `CASH`、`COUPON`、`SERVICE_VOUCHER`、`REDEMPTION_CODE`、`PHYSICAL`；组合奖品由多个原子 item 表达 |
| R3 | 组合奖品允许部分成功 | 每个 item 独立状态；订单终态可为 `PARTIAL_SUCCESS`；一期不因一个 item 失败自动撤销其它成功 item |
| R4 | 发放必须幂等 | 业务入口、MQ 消费、渠道调用、回调、补发、冲正、事件发布分别有独立幂等键和数据库唯一约束 |
| R5 | 支持补发与冲正 | 补发只针对已明确失败/未发的 item；冲正只针对已确认成功的 item；均保留原记录并追加操作/分录 |
| R6 | 库存双持并支持中台兜底 | 中台配额/库存是本地强一致账；渠道库存是渠道权威、中心侧保存影子快照；兜底路由必须显式配置 |
| R7 | Drools 通过 `AwardIntent` 接入 | 活动平台只决定“应发”，中台决定“如何发”；活动侧必须从服务器端决策结果和版本化绑定组装 intent |
| R8 | recon 负责自动对账与纠错闭环 | recon 仍是差异判定和 remediation 发起方；benefit-center 是补发/冲正的最终安全执行方 |
| R9 | 多租户隔离 | 租户来自已验签身份，不信任请求体；所有业务唯一键、查询、库存和消息均带 `tenantId` |
| R10 | 开放 API 与渠道 Adapter | API/消息契约版本化；渠道差异只在 Adapter 内；业务编排不出现渠道私有字段 |
| R11 | 为 Cell/分库分表预留 | 一期单 Cell/单逻辑库；所有聚合按 tenant 路由，同一 award order/item/operation 不跨分片事务 |
| R12 | 账务事实不可覆盖 | ISSUE/REVERSAL/库存变更/渠道凭证追加记录，不通过改写历史金额“修账” |

## 3. 建议拍板的规则（一期按此规划，实施前业务确认）

这些不是仓库已存在规则，但为使计划可执行，给出默认决议。若业务否决，必须先更新契约和测试，再编码。

1. **原子 item**：一期每个 `AwardIntent.items[]` 是最小可独立成功、补发或冲正的单位。兑换码、券、实物一 item 的 `quantity=1`；需要 N 份时由生产者生成 N 个稳定 `clientItemId`。这样非现金权益无需虚构金额即可精确发现少发/多发。
2. **部分成功策略**：一期只支持 `BEST_EFFORT`；保留 `ALL_OR_NOTHING` 枚举但拒绝启用。跨渠道原子组合不作为一期承诺。
3. **结果未知优先于兜底**：渠道超时、断连或 5xx 只能进入 `WAITING_CONFIRMATION` 并查询原操作；只有渠道以业务码明确返回“未发且无库存”，才可切换中台兜底。否则会双发。
4. **中台兜底等价性**：只有配置为同一用户价值、同一有效期/使用范围的 SKU 才能互为兜底；现金不得自动替换为券，实物不得自动替换型号。等价关系需要产品/法务确认。
5. **非货币对账口径**：券、兑换码和实物使用独立的 `EntitlementObservation`，比较 `issue_id/sku/quantity/status/provider_ref`；金额字段不参与、也不得用 `XXX/0` 或权益估值伪装资金。现金继续使用现有 `Money` 与金额守恒模型。
6. **补发门槛**：只有 `FINAL_FAILED` 或 recon 已证明三方均无成功凭证的 item 可补发；`UNKNOWN` 禁止补发。
7. **冲正门槛**：现金/可撤券走渠道 reverse API；已核销券、已使用服务券、已发货实物进入 `MANUAL_REQUIRED`，不宣称自动可逆。
8. **数据迁移**：存量 `activity_grant` 不导入中台、不生成新渠道动作；灰度切点之后的新请求才由中台建账。存量继续走 `MARKETING_3WAY`；新现金走 `BENEFIT_CASH_3WAY`，新券/码/实物走 `ENTITLEMENT_FULFILLMENT`，避免双算。
9. **传输**：同步 OpenAPI 是一期生产入口；Kafka 消息契约同时冻结并实现可开关消费者/发布者。若组织最终不用 Kafka，需在实现前替换消息适配器，领域契约不变。
10. **物理权益隐私**：AwardIntent 只传 `recipientRef/addressRef`，不传明文手机号和地址；物流 Adapter 按引用向主数据服务取数。

## 4. 核心用例

### 4.1 发放

1. 上游以 `(tenantId, sourceSystem, sourceRequestId)` 提交 AwardIntent。
2. 中台校验 schema、租户、SKU 状态、动态金额上限、item 唯一性和 payload hash。
3. 同一幂等键同一 payload 返回首次订单；同键不同 payload 返回 409/死信，不覆盖首次请求。
4. 中台在一个本地事务中创建 order/items、预占中台配额及可选的兜底库存、写履约任务和 outbox。
5. Worker 逐 item 调渠道；成功提交库存、追加 ISSUE 分录和事件；失败释放库存或进入待确认/重试。
6. 聚合 item 状态得到订单结果。调用方可轮询，也可消费事件。

### 4.2 中台库存兜底

1. 首选渠道有可用路由时先走渠道。
2. 渠道明确 OOS/不支持且存在启用的等价中台路由时，沿同一 award item 新建 fallback operation。
3. 渠道超时/未知不得 fallback；先以稳定 operation id 查询原请求。
4. 首选渠道成功后释放先占的中台兜底库存；fallback 成功后提交兜底库存。

### 4.3 补发

1. 人工或 recon 用唯一 `externalCommandId` 请求 `REISSUE`。
2. 中台锁定 item，确认没有成功/未知操作；必要时查询渠道。
3. 新建 remediation 和新的 reissue operation；网络重试复用该 operation 的幂等键。
4. 成功后追加新的 ISSUE 分录，并把订单从 `REMEDIATING` 重算至最终状态。

### 4.4 冲正

1. 请求指定原 item 与成功 operation，不接受仅按订单“猜一笔”。
2. 中台确认可逆、未冲正，创建 reverse operation。
3. 渠道确认撤销后追加负向 REVERSAL 分录并归还相应中心库存；未知结果进入查询，不重复 reverse。
4. 不可逆权益进入人工处理，并通过事件回传 recon。

### 4.5 对账与纠错

1. AwardIntent item 进入 ODS expected；中台不可变分录进入 ODS accounting；标准化渠道/中台履约凭证进入 ODS fulfillment。
2. recon 按 tenant + operation/issue id 做两段勾兑。
3. 安全矩阵将差异映射成 `REISSUE`、`REVERSE` 或 `MANUAL_REVIEW` 建议。
4. 自动白名单可直接投递；其余经审批后由 remediation outbox 发命令。
5. benefit-center 按 command id 幂等执行并回传结果；下一账期重新勾兑闭环。

## 5. 状态机业务约束

### AwardOrder

`ACCEPTED → PROCESSING → SUCCEEDED | PARTIAL_SUCCESS | FAILED`

终态发现可修复差异时：`PARTIAL_SUCCESS|FAILED → REMEDIATING → SUCCEEDED|PARTIAL_SUCCESS|FAILED`。存在冲正时：`SUCCEEDED|PARTIAL_SUCCESS → REVERSING → REVERSED|PARTIALLY_REVERSED|REVERSAL_FAILED`。不得直接从 `REVERSED` 回到 `SUCCEEDED`。

### AwardItem

`PENDING → RESERVED → DISPATCHING → SUCCEEDED | RETRYABLE_FAILED | WAITING_CONFIRMATION | FINAL_FAILED | MANUAL_REQUIRED`；补发为 `FINAL_FAILED → REISSUING → SUCCEEDED|FINAL_FAILED|MANUAL_REQUIRED`；冲正为 `SUCCEEDED → REVERSING → REVERSED|REVERSAL_FAILED|MANUAL_REQUIRED`。

### FulfillmentOperation

`CREATED → DISPATCHING → CONFIRMED_SUCCESS | CONFIRMED_FAILURE | UNKNOWN`；`UNKNOWN → QUERYING → CONFIRMED_SUCCESS|CONFIRMED_FAILURE|UNKNOWN`。相同 operation 的重试不能产生新渠道业务号。

## 6. 边界与异常场景

| 场景 | 必须行为 |
|---|---|
| 同幂等键不同 payload | 409 / MQ poison，保留首次记录并告警 |
| 两实例同时接同一 intent | 数据库唯一键只生成一个订单；失败实例回读首次结果 |
| 库存只剩 1、并发 100 | 中心库存 CAS 最多成功 1；不得先查后扣 |
| 中心事务成功、进程在发 MQ 前崩溃 | outbox 恢复后至少一次发布 |
| 渠道成功但响应丢失 | operation=UNKNOWN，按相同 request no 查询；不得新建 fallback/reissue |
| 渠道回调先于同步响应 | callback inbox 幂等，状态 CAS 允许先到回调收敛，迟到响应不得反向覆盖 |
| 组合 3 项中 2 成功 | order=PARTIAL_SUCCESS，成功项可查询和对账，失败项可单独补发 |
| 回调重复/乱序 | callback event id 去重；终态不能被早期状态覆盖 |
| 冲正与补发并发 | item 级乐观锁/CAS 只允许一种 remediation 占有；冲突方 409 |
| 渠道 OOS 后中心兜底也无货 | item=FINAL_FAILED；释放其它预占；不影响组合内已成功项 |
| 兑换码分配后事务回滚 | code 仍为 AVAILABLE；分配、item 状态和 outbox 必须同本地事务 |
| 实物已出库后冲正 | MANUAL_REQUIRED；不能伪造 REVERSED |
| 租户信封与 JWT 不一致 | 403；禁止以请求体 tenant 作为数据源 |
| Cell 路由变化 | 已存在订单按 `home_cell` 回原 Cell；不得把重试路由到新库造第二单 |

## 7. 一期范围

### In scope

- 独立 Java 21 / Spring Boot 3.3.5 Maven 多模块服务，单 Cell、单 MySQL 逻辑库、多实例无状态 API + worker。
- 五种原子权益及组合奖品的统一领域模型、SKU/路由/库存配置、通用 Adapter SPI。
- 现金/券/服务券/兑换码/实物的参考 Adapter 与契约测试；真实渠道仅对拿到正式接口文档、测试账号的渠道承诺上线。
- 同步 OpenAPI、Kafka AsyncAPI、transactional outbox/inbox、查询和回调。
- 组合部分成功、中心库存/渠道影子库存、明确 OOS 后的中心兜底。
- Drools 活动侧的 AwardIntent 绑定、服务器端重算组装、可靠投递与灰度模式。
- recon 的现金 `BENEFIT_CASH_3WAY`、非金额 `ENTITLEMENT_FULFILLMENT` ODS、租户化运行，以及 remediation 建议/审批/命令/结果闭环。
- 安全、指标、告警、压测、灰度、回滚和 runbook。

### 一期不做

- 跨渠道 `ALL_OR_NOTHING` 分布式原子奖品。
- 未给接口文档的真实渠道“猜测式”集成；计划中的 Adapter 类不等于渠道已可生产发放。
- 自动替代非等价权益、自动撤回已核销/已使用/已发货权益。
- 跨 Cell 实时事务、在线分库分表、全局库存强一致；一期只保证结构和路由接缝。
- 将现有活动定价优惠自动解释为现金红包；必须存在版本化 benefit SKU 绑定。
- 迁移或重放存量 `activity_grant`；统一客服 UI 也不在一期。
- 把现有 recon 金额模型整体重构为任意度量引擎；一期采用旁路的窄模型 `EntitlementObservation`，共享运行编排、分桶、差异生命周期与审批能力，不修改 `MARKETING_3WAY` 的金额语义。

## 8. 歧义与待验证清单

| 优先级 | 待验证事项 | 未确认时的默认处理 |
|---|---|---|
| P0 | 首批真实渠道、API 文档、幂等/查询/撤销能力、限流和测试账号 | 只完成 SPI、模拟器和通用 HTTP 骨架，不宣称真实渠道验收 |
| P0 | 现金红包的资金账户、单笔/日累计限额、实名/反洗钱要求 | CASH Adapter 默认关闭生产路由 |
| P0 | 哪些券可由中心码池兜底，等价性由谁审批 | 无等价绑定则 OOS 直接失败 |
| P0 | 补发/冲正自动白名单及审批权限 | 默认全部需审批；只允许查询和生成建议 |
| P0 | AwardIntent 的业务触发点（支付前、支付后、订单完成后） | 按“支付后/资格已确认”设计，活动端重算后发 intent |
| P1 | 目标峰值 QPS、组合 item P99、日订单量、库存热点分布 | 容量验收用“实测峰值×2”占位，数值待压测输入 |
| P1 | SLA、RTO、RPO、消息最大延迟 | 建议 API 99.95%、RPO≈0（DB HA）、RTO≤30min，均待业务/SRE确认 |
| P1 | Kafka 是否为公司标准、topic 命名和 schema registry | 契约保持 transport-neutral；实现默认 Kafka，允许替换适配器 |
| P1 | 物理发货系统、地址引用协议、退货状态 | 只存 addressRef；真实物流 Adapter 待验证 |
| P1 | 兑换码 KMS/密钥轮换和数据保留期限 | 密文存储、hash 查重、日志脱敏；KMS 产品待验证 |
| P1 | 租户与 Casdoor aud 的映射规范 | 复用活动平台“验签 aud→tenant”机制，但 client family 名待确认 |
| P2 | Cell 划分维度、租户搬迁流程、分片中间件 | 一期固定 tenantId 路由和 homeCell，不实施搬迁 |

## 9. 验收标准

### 功能验收

- 五种原子权益均能通过同一 AwardIntent 契约进入，组合奖品可聚合部分成功；每类至少一个成功、明确失败、未知结果和冲正能力差异用例。
- 3 item 组合中人为制造 1 项失败，返回 PARTIAL_SUCCESS，另外 2 项各只有一份渠道凭证和 ISSUE 分录。
- 同一 HTTP/MQ intent 并发重放 100 次只生成 1 个 order；同键异 payload 被拒绝。
- 渠道超时后不得触发中心兜底；渠道明确 OOS 后仅在等价兜底配置存在时切路由。
- 补发/冲正命令重放不重复发、不重复撤、不重复写分录。
- 每个状态可由 order/item/operation 查询解释；渠道错误码经过 Adapter 映射为稳定平台码。

### 数据与对账验收

- order、item、operation、attempt、inventory ledger、award ledger、outbox/inbox 可按 trace/source/order/item 串联。
- ISSUE 与 REVERSAL 只追加，冲正后净额/净数量可重算为 0，不覆盖 ISSUE。
- `BENEFIT_CASH_3WAY` 能复现金额、币种、状态和桥断差异；`ENTITLEMENT_FULFILLMENT` 能复现缺失、额外、重复、数量、SKU 和状态差异，数量守恒成立。
- 非现金 item 不依赖虚构估值；用不同 issue id 发现少发/多发。
- recon remediation command 与 benefit execution result 按 command id 幂等闭环；下一次对账消除已修复差异。

### 并发、可靠性和安全验收

- 热库存 CAS、worker lease、回调/同步响应乱序、outbox 崩溃恢复、broker 重平衡和 DB 主从切换均有自动化或演练证据。
- 多租户交叉读写、伪造 tenant header、越权管理/补发/冲正全部失败；兑换码、手机号、地址不出日志和指标标签。
- 灰度可按 tenant/source/activity 切 `LEGACY→SHADOW→CENTER`；回滚只影响新流量，已受理订单继续由原中台实例收敛，不重复切回 legacy 发放。

### 性能验收（门槛待业务确认）

- 建议暂定：开放 API 在只落本地事务、不等待渠道时 p95≤100ms、p99≤200ms；错误率<0.1%；持续吞吐不低于业务峰值×2。
- 单热点 SKU 在超卖为 0 的前提下达到目标并发；worker backlog 在渠道恢复后于约定窗口内清空。
- 所有数值最终以批准的容量模型和压测报告替换，不把本建议当成已确认 SLA。
