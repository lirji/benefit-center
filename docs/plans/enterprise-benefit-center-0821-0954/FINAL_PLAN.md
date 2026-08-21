# 公司级权益发放中台最终实施计划

> 状态：一期工程基线已按本计划实施并完成本地自动化验证；真实渠道、容量/SLA/RTO/RPO、Kafka 生产标准、资金合规和业务审批矩阵仍是生产切流门禁。本文保留规划时态作为设计决策记录，实际工件与验证证据见同目录 `IMPLEMENTATION_PROGRESS.md`。

## 1. 背景

现有 `drools-demo` 已能对红包折扣、买赠和加价购做服务器端决策，并有单活动 `claim→confirm→release`、不可变金额分录和 outbox 原型；但它的发放模型只覆盖单活动金额，默认 dispatcher 甚至只写日志，无法承载公司级多权益 SKU、渠道状态、兑换码/实物、组合部分成功和补发 operation。

现有 `recon-platform` 已有 MARKETING↔ACCOUNTING↔CHANNEL 两段对账、守恒、人工处置、冲正建议、Flowable审批和默认日志执行器；但 DB source 无 tenant/账期过滤，Run 也无 tenant，remediation 只建模金额冲正，现有执行服务缺 expected-state CAS，不能直接作为自动权益补发/冲正执行链。

因此需要独立 `benefit-center`：Drools 只提供 AwardIntent，中台统一履约，recon 以独立 ODS 自动发现并受控纠错。

## 2. 目标与非目标

### 2.1 目标

- 独立部署、独立数据库、可多实例的 benefit-center。
- 统一支持 CASH、COUPON、SERVICE_VOUCHER、REDEMPTION_CODE、PHYSICAL 与组合奖品。
- item 级部分成功、业务/消息/渠道/回调/remediation 全链路幂等。
- 中心强一致配额/库存 + 渠道权威库存/中心影子快照 + 明确 OOS 后中心兜底。
- 开放 REST API 与版本化 Kafka 契约；渠道能力收口到 Adapter。
- 多租户安全隔离；tenant/homeCell/routingKey 为未来 Cell/分片预留。
- Drools 服务器端重算并按版本化 binding 组装 AwardIntent。
- recon ODS 三方勾兑及 REISSUE/REVERSE/MANUAL_REVIEW 闭环。
- 可灰度、可停新流量、可恢复积压、账务事实只前滚修复。

### 2.2 一期非目标

- 跨渠道 ALL_OR_NOTHING/XA/Seata；一期组合策略固定 BEST_EFFORT。
- 在线多 Cell、跨 Cell 事务、租户搬迁和真正分库分表。
- 自动替换非等价权益；自动撤销已核销/已使用/已发货权益。
- 未取得正式文档和 sandbox 的真实渠道上线承诺。
- 把活动定价优惠按字段名猜成现金权益；无 AwardBinding 就 fail-closed。
- 迁移/重放存量 `activity_grant`，或改写既有 decision 算法/JSON。
- 把 recon 整体重构为任意度量引擎；一期只新增窄范围的非金额 `EntitlementObservation` 旁路模型，现有金额模型保持兼容。

## 3. 已确认业务规则

1. benefit-center 是发放唯一权威；Drools 是“应发什么”的生产者，recon 是差异/remediation 发起者。
2. AwardIntent 由多个原子 item 构成；一期券/码/实物每 item quantity=1，多份拆多个稳定 clientItemId。
3. 同一 intent 可部分成功，不自动回滚其它成功 item。
4. 渠道 timeout/5xx/断连属于 UNKNOWN，禁止 fallback/补发；先查询原 operation。
5. 只有明确 OOS/未发且配置等价 route 才允许中心兜底。
6. 补发只对明确未发 item；冲正只对明确成功 item；所有修复追加 operation 与 ledger，不覆盖历史。
7. 无货币含义的权益绝不构造假币种或零金额；使用 `EntitlementObservation(issueId,sku,quantity,status,providerRef)` 做存在、数量、SKU、状态和重复性核对。现金继续使用 `Money` 与金额守恒。
8. 存量 activity grant 留在 legacy；新旧对账场景分开，不 union、不双算。
9. tenant 来自已验签 JWT/aud 映射，body/header只作一致性校验。
10. 真实 Adapter 上线以渠道 contract test 为准；SPI/mock通过不等于生产渠道支持。

待验证项见 `01-requirements.md` §8；其中首批渠道、资金合规、fallback等价性和自动remediation白名单是实施 P0 gate。

## 4. 当前代码与调用链分析

### 4.1 Drools 决策

- 折扣：`DecisionPlaneController#spuDiscount` → `ActivityQueryService#spuDiscount(req,HOT_PATH)`，结果 `DiscountView` 带 decisionId、hitVersion 和逐候选 `DiscountItem`（`drools-demo/activity-decision/src/main/java/com/lrj/drools/activity/controller/DecisionPlaneController.java:108`；`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:411`）。
- 买赠：`DecisionPlaneController#gifts` → `ActivityQueryService#buyAndGetGifts`；`GiftResult` 带 activity/version/batchId/type/quantity（controller `:141`；`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/GiftResult.java:17`）。
- 加价购：`AddOnPurchaseService#quote` 会重取权威价格，但支付触发点待验证，一期 contract保留、不默认发放（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:179`）。
- `SpuDiscountRequest` 没有 orderId/sourceRequest/recipient/SKU，所以不能让只读 decision API 直接产生副作用（`.../SpuDiscountRequest.java:32`）。

### 4.2 Legacy grant

`ActivityMarketingController#claim/confirm/release` 委派 `GrantService`；grant 状态是 HELD/CONFIRMED/RELEASED，唯一 `(tenant,order,activity)`（`ActivityMarketingController.java:140`；`ActivityGrantEntity.java:41`）。`claimInventory` 用条件 UPDATE 防超卖，`confirmIfHeld`/release 用 CAS，entry 按 ISSUE/REVERSAL 追加；这些模式正确，但模型不足以作为新中台。

`GrantEvent` 只含金额型 grant 信息，幂等键 `grantNo:eventType`（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/spi/GrantEvent.java:22`），不能直接改名成 AwardIntent。Legacy grant/outbox 保留；新 connector 建独立 binding/intent outbox。

### 4.3 recon

`MarketingThreeWayScenario` 已有两段 spine，可复用于现金 `BENEFIT_CASH_3WAY`（`recon-platform/recon-scenario/src/main/java/com/lrj/recon/scenario/MarketingThreeWayScenario.java:14`）。非金额权益另建 `ENTITLEMENT_FULFILLMENT`，复用运行编排而不复用 `Money/GroupAggregator`。此外 `RunKey` 和 `SourceReadContext` 无 tenant，`KeysetRecordCursor` 全表读取无 tenant/window（`RunKey.java:11`；`SourceReadContext.java:9`；`KeysetRecordCursor.java:69`），必须先做 tenant expand 和 source 过滤。

`ReversalExecutionService#execute` 先读 CONFIRMED 再调外部、后更新状态，没有 CAS/lease（`recon-platform/recon-batch/src/main/java/com/lrj/recon/batch/service/ReversalExecutionService.java:38`）。新 benefit remediation 采用独立 command outbox；旧 reversal 路径同步加 expected-state CAS 以消除双执行窗口。

完整证据与影响面见 `02-codebase-analysis.md`。

## 5. 候选方案对比与评分

评分 1–5，5 更有利；权重为正确性25%、改动风险15%、复杂度10%、可维护性15%、扩展性15%、测试难度10%、回滚成本10%。

| 方案 | 正确性 | 风险 | 复杂度 | 维护 | 扩展 | 测试 | 回滚 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 活动内扩展再抽离 | 2 | 2 | 4 | 2 | 2 | 3 | 3 | 2.60 |
| B 独立模块化单体 | 5 | 4 | 4 | 5 | 4 | 4 | 4 | **4.40** |
| C 事件驱动微服务 | 4 | 2 | 1 | 3 | 5 | 1 | 2 | 2.90 |
| D Cell-first | 4 | 1 | 1 | 3 | 5 | 1 | 1 | 2.65 |

A 违反独立中台目标；C 把本地原子性拆成多级 saga；D 提前承担多 Cell/全局库存/租户搬迁。最终以 B 为主线，吸收 C 的 AsyncAPI/inbox/schema gate、D 的 homeCell/routing 接缝、A 中已验证的 CAS/append-only/outbox模式。详见 `comparison.md`。

## 6. 最终架构

```text
                          ┌────────────────────────┐
Drools activity-console ─► AwardIntent REST/Kafka │
Other trusted systems   ─► benefit-center API     │
                          └──────────┬─────────────┘
                                     │ local TX
                 ┌───────────────────▼───────────────────┐
                 │ order/item + center inventory        │
                 │ operation/attempt + ledger           │
                 │ code/physical + inbox/outbox         │
                 └───────────────────┬───────────────────┘
                                     │ leased workers; no DB TX across I/O
                          ┌──────────▼──────────┐
                          │ ChannelAdapter SPI  │
                          │ channel / center    │
                          └──────────┬──────────┘
                                     │ facts/events
                 ┌───────────────────▼───────────────────┐
                 │ recon ODS expected/accounting/fulfill │
                 │ CASH_3WAY / ENTITLEMENT + remediation │
                 └───────────────────┬───────────────────┘
                                     └──► benefit remediation inbox
```

### 6.1 一致性边界

- **一个本地事务**：intent幂等、order/items、中心库存 reservation、operation、outbox。
- **外部 I/O 无长事务**：worker先 CAS lease，事务提交后调渠道，再以短事务 CAS 收敛。
- **跨系统至少一次**：outbox→Kafka/HTTP，inbox/eventId去重；不宣称 exactly-once。
- **渠道不确定性**：稳定 operationNo 作为渠道 idempotency/requestNo；UNKNOWN只 query，不换 operation。
- **分录**：成功 ISSUE 和确认 REVERSAL 追加；状态行是视图，不是账。

### 6.2 双持库存

- `CENTER_QUOTA`：所有发放先占，代表公司预算/总配额，强一致。
- `CHANNEL_SHADOW`：渠道同步快照，只用于路由预判和告警，不能证明渠道实际可扣。
- `CENTER_STOCK`：中心码池/实物/可中心履约券的兜底库存。`fallbackReserveMode=EAGER` 时接单即预占，渠道成功立即释放。
- 渠道若支持 reserve→issue，Adapter执行两阶段；不支持则 issue 本身是权威扣减。
- 任何库存变更都写 `bc_inventory_ledger`；balance 行用 CAS，不靠先查后扣。
- 补发/冲正只归还中台实际拥有的 `CENTER_QUOTA`/`CENTER_STOCK`；`CHANNEL_SHADOW` 只能由渠道快照同步作校准，业务事务不得把影子数当作真实库存返还。

### 6.3 Cell/分片接缝

- tenant 配 `homeCell`；order 创建时固化 `home_cell`，后续 query/retry/remediation 回该 Cell。
- 所有业务 unique/index 以 tenant 开头；ID 使用应用生成的全局唯一字符串，不依赖跨分片自增。
- 一个 order 的 items/operations/ledger 与其同分片；不跨 tenant 事务、不做全局扫描作为业务路径。
- 一期 `CellRouter` 只是接口 + single-cell实现；不部署全局 router，不实现 tenant搬迁。

### 6.4 显式状态机与终态

| 聚合 | 合法主路径 | 禁止/约束 |
|---|---|---|
| AwardOrder | `ACCEPTED → PROCESSING → SUCCEEDED/PARTIAL_SUCCEEDED/FAILED`；终态因补发进入 `REMEDIATING`，完成后重新聚合；因冲正进入 `REVERSING → REVERSED/PARTIALLY_REVERSED` | item 未全终结不得直接完成；不以“重试次数耗尽”把 UNKNOWN 猜成 FAILED |
| AwardItem | `PENDING → RESERVED → DISPATCHING → SUCCEEDED/FAILED_FINAL/UNKNOWN`；`UNKNOWN → QUERYING → SUCCEEDED/FAILED_FINAL/UNKNOWN`；明确失败可 `REISSUING → SUCCEEDED/FAILED_FINAL/UNKNOWN`；成功可 `REVERSING → REVERSED/REVERSAL_FAILED/REVERSAL_UNKNOWN` | UNKNOWN 禁止新 issue/fallback；REISSUE 必须生成新 operation；REVERSE 不覆盖原 ISSUE |
| FulfillmentOperation | `CREATED → LEASED → DISPATCHING → SUCCEEDED/FAILED_RETRYABLE/FAILED_FINAL/UNKNOWN`；`FAILED_RETRYABLE → LEASED`；`UNKNOWN → QUERYING → SUCCEEDED/FAILED_FINAL/UNKNOWN` | `(tenant,idempotency_key)` 唯一；旧 lease owner 不得提交；只有明确 OOS/NOT_ISSUED 才能结束为可 fallback |
| RemediationOrder | `PROPOSED → APPROVED/REJECTED`；`APPROVED → DISPATCHING → SUCCEEDED/FAILED/UNKNOWN` | 默认不自动批准；command replay 返回原结果；benefit-center 必须再次校验对象、原 operation 与渠道能力 |

`OrderStateMachine` 只根据 item 最新事实重算 order；终态不是数据库“不可变行”，但每次改变必须由新 operation/ledger/审计事实解释。状态全集在 OpenAPI、AsyncAPI、数据库 CHECK 与 Java enum 中由契约测试保持一致。

## 7. 精确模块与文件/类/方法规划

路径约定：本节表格中的 `.../` 只省略本小节已写明的唯一 Maven source root；例如 contract 的 `.../AwardItemIntent.java` 唯一展开为 `benefit-center/benefit-contract/src/main/java/com/lrj/benefit/contract/AwardItemIntent.java`。recon 的 `recon-core/...`、`recon-batch/...`、`recon-handler/...` 分别展开到对应模块的 `src/main/java/com/lrj/recon/{core,batch,handler}/`；不存在同名候选路径。实施前仍须用 `rg --files` 复核，若现有包布局变化则在 ADR 标“待验证”，不能静默另建重复类。

### 7.1 新增项目 `benefit-center/`

下列均为**计划新增**。

#### 父工程与契约模块

| 文件 | 类/内容 | 关键方法/职责 |
|---|---|---|
| `benefit-center/pom.xml` | Maven parent | Java21/SB3.3.5；modules与依赖版本 |
| `benefit-center/benefit-contract/pom.xml` | contract jar | Jackson validation/OpenAPI generated model依赖 |
| `benefit-contract/src/main/java/com/lrj/benefit/contract/AwardIntent.java` | record | schemaVersion/sourceSystem/sourceRequestId/businessNo/recipientRef/activity/decision/items/policy/trace |
| `.../AwardItemIntent.java` | record | clientItemId/benefitSkuId/benefitType/amountMinor/currency/metadata |
| `.../MessageEnvelope.java` | record | eventId/eventType/schemaVersion/tenantId/occurredAt/traceId/partitionKey/payload |
| `.../FulfillmentEvent.java` | record | order/item/operation/status/channel/reference/error |
| `.../RemediationCommand.java` | record | externalCommandId/action/awardItemNo/originalOperationNo/reason/approvalRef |
| `.../RemediationResult.java` | record | command/remediation/status/reference/error |
| `.../BenefitErrorCode.java` | enum | 稳定平台错误码，不暴露渠道私码 |
| `benefit-contract/src/main/resources/openapi/benefit-center-v1.yaml` | OpenAPI | 第9节接口权威 schema |
| `benefit-contract/src/main/resources/asyncapi/benefit-center-v1.yaml` | AsyncAPI | 第10节 topic/envelope/schema |

#### 领域模块

| 文件 | 类 | 关键方法/职责 |
|---|---|---|
| `benefit-domain/pom.xml` | pure Java module | 禁 Spring/JDBC/Kafka；加 ArchUnit test |
| `.../domain/model/AwardOrder.java` | aggregate | `accept`、`startProcessing`、`beginRemediation`、`beginReversal`、`recompute` |
| `.../domain/model/AwardItem.java` | entity | `reserve`、`dispatch`、`markUnknown`、`succeed`、`failFinal`、`beginReissue`、`beginReverse` |
| `.../domain/model/FulfillmentOperation.java` | entity | `lease`、`markDispatching`、`confirmSuccess`、`confirmFailure`、`markUnknown`、`beginQuery` |
| `.../domain/model/BenefitSku.java` | entity | type/amount guard/capability/PII policy |
| `.../domain/model/ChannelRoute.java` | entity | priority/owner/fallback/capability/configRef |
| `.../domain/model/InventoryAccount.java` | entity | balance invariant；实际CAS在port实现 |
| `.../domain/model/RemediationOrder.java` | aggregate | approve/reject/dispatch/complete/fail |
| `.../domain/model/*Status.java` | enums | Order/Item/Operation/Remediation/Inventory/BenefitType |
| `.../domain/service/OrderStateMachine.java` | domain service | `transitionOrder`/非法边fail-fast |
| `.../domain/service/RoutePolicy.java` | domain service | `selectPrimary`、`selectFallback`；UNKNOWN禁fallback |
| `.../domain/service/AwardIntentValidator.java` | domain service | 原子item、动态金额、binding snapshot、policy校验 |
| `.../domain/service/LedgerInvariant.java` | domain service | ISSUE/REVERSAL净额与唯一operation检查 |

#### 应用模块

| 文件 | 类 | 关键方法 |
|---|---|---|
| `benefit-application/pom.xml` | module | 依赖 contract+domain |
| `.../port/in/AcceptAwardIntentUseCase.java` | input port | `AcceptResult accept(AwardIntentCommand)` |
| `.../port/in/QueryAwardOrderUseCase.java` | input port | `get(orderNo)`、`findBySource(source,requestId)` |
| `.../port/in/ExecuteFulfillmentUseCase.java` | input port | `runBatch(limit,workerId)`、`handleCallback` |
| `.../port/in/ExecuteRemediationUseCase.java` | input port | `accept(command)`、`execute(remediationNo)` |
| `.../port/out/AwardRepository.java` | output port | insert/find/lock/updateExpectedVersion |
| `.../port/out/InventoryRepository.java` | output port | `reserve`、`commit`、`release`、`returnIssued` 条件UPDATE |
| `.../port/out/OperationRepository.java` | output port | `claimLease`、`updateExpectedState`、`findDue` |
| `.../port/out/LedgerRepository.java` | output port | `appendIfAbsent`/按order,item查询 |
| `.../port/out/OutboxRepository.java`、`InboxRepository.java` | ports | enqueue/claim/mark/idempotent consume |
| `.../port/out/BenefitCatalogRepository.java` | port | SKU/route/snapshot读取 |
| `.../port/out/CodeAssetRepository.java` | port | `reserveOne`、`issue`、`release`；不返回日志明文 |
| `.../port/out/ChannelAdapter.java` | SPI | `capabilities`、`issue`、`query`、`reverse`、可选reserve |
| `.../service/AwardApplicationService.java` | use case impl | `accept`：幂等hash→order/items→reserve→outbox |
| `.../service/FulfillmentApplicationService.java` | use case impl | `claimAndExecute`、`settleResult`、`recomputeOrder` |
| `.../service/RemediationApplicationService.java` | use case impl | safe gate、new operation、结果回传 |
| `.../service/ChannelCallbackService.java` | service | callback验签后inbox、乱序CAS |

#### Adapter模块

| 文件 | 类 | 关键方法/职责 |
|---|---|---|
| `benefit-adapters/pom.xml` | module | JDBC/Flyway/Kafka/security/http/KMS依赖 |
| `.../jdbc/JdbcAwardRepository.java` | adapter | order/item insert、source unique冲突回读、version CAS |
| `.../jdbc/JdbcInventoryRepository.java` | adapter | 单SQL reserve/commit/release/return + ledger同事务 |
| `.../jdbc/JdbcOperationRepository.java` | adapter | lease_owner/lease_until/expected status CAS |
| `.../jdbc/JdbcLedgerRepository.java` | adapter | operation+entryType唯一追加 |
| `.../jdbc/JdbcOutboxRepository.java`、`JdbcInboxRepository.java` | adapters | SKIP LOCKED/短事务/DEAD/redrive |
| `.../jdbc/JdbcCatalogRepository.java` | adapter | SKU/route/capability snapshot |
| `.../jdbc/JdbcCodeAssetRepository.java` | adapter | hash唯一、密文、并发reserve |
| `.../jdbc/JdbcRemediationRepository.java` | adapter | command幂等/状态CAS |
| `.../messaging/KafkaAwardIntentConsumer.java` | adapter | topic input→同一 `AcceptAwardIntentUseCase` |
| `.../messaging/KafkaEventPublisher.java` | adapter | publish envelope，不直接改业务状态 |
| `.../messaging/OutboxRelay.java` | service | claim batch→publish→短事务mark，退避/DEAD |
| `.../messaging/KafkaRemediationConsumer.java` | adapter | command inbox→use case |
| `.../channel/ChannelAdapterRegistry.java` | registry | route channelCode→adapter，启动校验缺失实现 |
| `.../channel/ConfigurableHttpChannelAdapter.java` | reference adapter | signed HTTP issue/query/reverse；真实映射需子类/配置 |
| `.../channel/CenterCodeAdapter.java` | center adapter | reserve/issue code asset，返回密文解密后的受控delivery |
| `.../channel/PhysicalFulfillmentAdapter.java` | adapter | 只传 addressRef，异步物流callback |
| `.../channel/ChannelCallbackVerifier.java` | security | timestamp/nonce/signature/replay guard |
| `.../security/BenefitSecurityConfig.java` | config | JWT scopes、M2M、admin/remediation权限 |
| `.../security/JwtTenantFilter.java`、`TenantContext.java` | tenant | aud→tenant、header一致、finally清理 |
| `benefit-adapters/src/main/resources/db/migration/V1__benefit_center_schema.sql` | Flyway | 第8节全部基表/唯一键/索引 |
| `.../V2__benefit_center_constraints.sql` | Flyway | check/索引/初始schemaVersion元数据 |

#### Server与部署

| 文件 | 类/内容 | 关键方法/职责 |
|---|---|---|
| `benefit-server/pom.xml` | Boot module | executable jar |
| `.../BenefitCenterApplication.java` | main | scan明确根包 |
| `.../web/AwardOrderController.java` | REST | `accept`、`get`、`findBySource` |
| `.../web/RemediationController.java` | internal REST | `reissue`、`reverse`、`get` |
| `.../web/ChannelCallbackController.java` | callback | `receive(channelCode,body,headers)` |
| `.../web/AdminCatalogController.java` | admin | SKU/route/enable，乐观锁 |
| `.../web/AdminInventoryController.java` | admin | adjust/import codes/snapshot；强审计 |
| `.../web/BenefitExceptionAdvice.java` | error | stable error/body/status |
| `.../worker/FulfillmentScheduler.java` | worker | role/profile开关，batch lease |
| `.../worker/OutboxScheduler.java` | worker | relay/redrive |
| `.../config/BenefitProperties.java` | config props | worker/outbox/security/cell/channel限额校验 |
| `benefit-server/src/main/resources/application.yml` | config | 全部默认安全：真实channel/remediation auto关闭 |
| `benefit-center/deploy/Dockerfile` | image | non-root/JRE21/readiness |
| `benefit-center/deploy/docker-compose.yml` | local | MySQL+Kafka/Redpanda+simulator+service，仅开发 |
| `benefit-center/deploy/prometheus-rules.yml` | alerts | 第14节告警 |
| `benefit-center/docs/runbook.md`、`channel-onboarding.md`、`data-retention.md` | docs | 运维/渠道/隐私 |
| `benefit-center/.github/workflows/ci.yml` | CI | compile/unit/MySQL/Kafka/contract/image scan |

### 7.2 `drools-demo` 精确修改

#### 新增文件

- `activity-common/src/main/java/com/lrj/drools/activity/domain/AwardBindingInput.java`：管理端契约值对象；固定字段，不接受表达式脚本。
- `activity-console/src/main/java/com/lrj/drools/activity/persistence/ActivityAwardBindingEntity.java`：`(tenant,activityId,version,sourceKind,sourceRef,benefitSkuId)`、deliveryMode、amountMode、item template。
- `activity-console/src/main/java/com/lrj/drools/activity/persistence/ActivityAwardBindingRepository.java`：`findByTenantIdAndActivityIdAndVersion`、`saveAll`；仅console写侧存在。
- `activity-console/src/main/java/com/lrj/drools/activity/persistence/ActivityAwardIntentOutboxEntity.java`、`ActivityAwardIntentOutboxRepository.java`：unique `(tenant,sourceSystem,sourceRequestId)`、payloadHash、PENDING/SENT/FAILED/DEAD。
- `activity-console/src/main/java/com/lrj/drools/activity/domain/ActivityAwardIntentRequest.java`：sourceRequestId/businessNo/scene/decisionContext/recipientRef/selectedItem；不含可由客户端控制的金额/SKU/channel。
- `activity-console/src/main/java/com/lrj/drools/activity/service/ActivityAwardIntentAssembler.java`：`fromDiscount`、`fromGifts`；只读 server decision + version binding。
- `activity-console/src/main/java/com/lrj/drools/activity/service/ActivityAwardIntentService.java`：`createAndEnqueue`，重算、fail-closed、幂等落 outbox。
- `activity-console/src/main/java/com/lrj/drools/activity/service/AwardIntentOutboxRelay.java`、`AwardIntentRelayScheduler.java`：复刻已验证的事务外I/O/短事务/退避/DEAD模式，不复用GrantEvent schema。
- `activity-console/src/main/java/com/lrj/drools/activity/service/BenefitCenterAwardClient.java`：调用 OpenAPI，`Idempotency-Key=sourceRequestId`；MQ publisher实现同一port。
- `activity-console/src/main/java/com/lrj/drools/activity/config/AwardIntentProperties.java`、`AwardIntentConfig.java`：mode LEGACY/SHADOW/CENTER；SHADOW只组装/计量、不发送。
- `drools-demo/deploy/mysql-award-intent-connector.sql`：显式建表/unique/index；生产不依赖ddl-auto补约束。

#### 修改现有文件/方法

- `ActivityCreateRequest.java`：canonical record末尾增 `List<AwardBindingInput> awardBindings` 并保留现有构造；`AwardBindingInput` 字段固定，不接受表达式脚本。
- `ActivityMarketingService#createInternal/updateByVersion/saveGifts/getDetail`：调用新增 `saveAwardBindings`、回显binding；版本复制不可读“当前binding”。
- `ActivityMarketingController`：注入 `ActivityAwardIntentService`，新增 `POST /activity-marketing/award-intents` → `createAwardIntent`，返回202+sourceRequest/relay status。
- `GrantService#claimInventory`：只有全局 `activity.award-intent.mode=CENTER` 且该 activity/version 的有效 binding 为 CENTER 才拒绝新 legacy claim；任一门未开保持legacy。`confirmGrant` 对已有 HELD 继续放行以排空存量；`releaseGrant` 永远允许存量释放。
- `ActivityResourceServerConfig#activitySecurityFilterChain`：新端点加入 console-write-authority。
- `activity-console/pom.xml`：依赖发布的 `com.lrj.benefit:benefit-contract`，锁版本；CI做contract compatibility。
- `activity-console/application.yml`：加 `activity.award-intent.*`，默认 `mode=LEGACY`、publisher disabled。

#### 新增/修改测试

新增 `ActivityAwardBindingTest`、`ActivityAwardIntentAssemblerTest`、`ActivityAwardIntentOutboxTest`、`ActivityBenefitCutoverTest`、`ActivityAwardIntentSecurityTest`；修改 `ActivityMarketingFlowTest`、`DecisionOutputContractTest`、`GrantLedgerTest`、`GrantOutbox*Test`、`TenantIsolationTest`，标准见 `test-plan.md` §7。

### 7.3 `recon-platform` 精确修改

#### tenant expand（修改现有）

- `RunKey.java`、`ReconRun.java`、`SourceReadContext.java` 增 tenant；`SourceReadContext` 同时增 `windowStart/windowEnd`。
- `EvaluationContext.java` 与 `Fingerprint.java` 把可信 tenant 纳入规范化 fingerprint；`Discrepancy.java`、`DiscrepancyDisposition.java`、`ReversalSuggestion.java`、`DiscrepancyAction.java`、`AlertOutbox.java`、`ReconReport.java` 显式持有 tenant，避免不同租户相同业务键串案。
- `ReconRunRepository#lockScenarioPeriod/isLatestRun`、`ReconRunSeqRepository#nextSequence` 增 tenant参数。
- `ReconJobContext#of/toJobParameters`、`ReconLaunchService#launch/rerun/buildRunId` 透传可信tenant。
- `DbSourceConfig#from` 支持 `tenantColumn` 和 window filter；`KeysetRecordCursor#fetchNextPage` 参数化 tenant/from/to 与 keyset，不拼值。
- `MarketingThreeWayConfig`、`ReconM2Config`、`GenericReconJobConfig` 及所有 `SourceReadContext` 构造调用点传 tenant/window；`CsvSourceAdapter` 明确仅允许离线管理员导入且由 job context 注入 tenant，不能从CSV行信任tenant。
- `JdbcReconRunStore`、`JdbcReconRunSeqStore`、`JdbcReconConsoleQueryStore`、所有 discrepancy/disposition/reversal/action/report/alert JDBC store、`ReconConsoleQueryService/Repository`、`DiscrepancyController`、`ReconConsoleController` 全部加 tenant ownership谓词。
- `CasdoorSecurityConfig` 增 aud→tenant validator/filter；operator仍取可信JWT。

#### 新增 benefit ODS/scenario

- `recon-scenario/src/main/java/com/lrj/recon/scenario/BenefitCashThreeWayScenario.java`：`BENEFIT_CASH_3WAY`，复用金额责任链；SEG1 中台资金分录↔账务，SEG2 账务↔支付渠道。
- `.../dsl/BenefitCashThreeWayDefinition.java`：seed现金三张 ODS descriptor，带 tenantColumn/bizTimeColumn。
- 新增 Maven 模块 `recon-entitlement`：`EntitlementObservation`、`EntitlementMatchGroup`、`EntitlementDiscrepancyType`、`EntitlementDiscrepancyClassifier`、`EntitlementFulfillmentScenario` 与 `EntitlementFulfillmentJobConfig`；`ENTITLEMENT_FULFILLMENT` 比较 expected/internal/provider 三侧的 issueId、SKU、quantity、status、providerRef，不依赖 `Money`。
- `recon-entitlement` 复用 `ReconRun`、SourceAdapter 游标、hash 分桶、差异生命周期、人工处置和 remediation 端口；不修改 `ReconRecord`、`GroupAggregator`、金额守恒报表和 `MARKETING_3WAY` 语义。
- `recon-batch/.../ods/BenefitOdsEvent.java`、`BenefitOdsIngestionService.java`、`BenefitOdsConsumer.java`：eventId+payloadHash inbox幂等，按 factType落三表。
- `recon-batch/.../persistence/JdbcBenefitOdsStore.java`、`JdbcOdsInboxStore.java`。
- `recon-batch/.../web/BenefitOdsReplayController.java`：仅 internal/admin，用于受控重放，不作普通写入口。
- 修改 `GenericReconJobConfig` 透传 tenant/window；`ScenarioDefinitionSeeder#run` seed benefit。

#### 新增通用 remediation

- `recon-core/.../domain/model/RemediationSuggestion.java`、`RemediationAction.java`、`RemediationStatus.java`。
- `recon-core/.../application/port/out/RemediationSuggestionRepository.java`、`RemediationCommandOutboxRepository.java`。
- `recon-handler/.../BenefitRemediationSuggestionHandler.java`：安全矩阵映射 REISSUE/REVERSE/MANUAL_REVIEW；默认不自动批准。
- `recon-batch/.../persistence/JdbcRemediationSuggestionStore.java`、`JdbcRemediationCommandOutboxStore.java`。
- `recon-batch/.../service/BenefitRemediationService.java`：`approve/reject/dispatchResult` expected-state CAS。
- `recon-batch/.../service/RemediationCommandRelay.java`、`.../messaging/KafkaRemediationPublisher.java`。
- `recon-batch/.../web/BenefitRemediationController.java`：approve/reject/retry/read；JWT tenant+权限。
- 修改 `HandlerConfig#discrepancyHandlerChain` 注册新 handler，仅 `BENEFIT_CASH_3WAY` 与 `ENTITLEMENT_FULFILLMENT` 支持 benefit remediation。
- 修改 `ReversalSuggestionRepository`/`JdbcReversalSuggestionStore` 增 `claimExecution(id,expectedStatuses,owner,leaseUntil)` 与 `completeExecution(id,owner,expectedVersion,target,reference,error)`；`ReversalExecutionService#execute` 先 CAS `CONFIRMED/EXECUTION_FAILED`（或已过期 `EXECUTING`）→`EXECUTING` 并提交，再用稳定 `reversal:{tenant}:{suggestionId}` 调外部，最后校验 owner/version 收敛。`ReversalStatus` 新增 `EXECUTING`，表增 `execution_owner/execution_lease_until/execution_reference`；进程在外调成功后崩溃时，租约到期仍以同幂等键查询/重试，不能二次冲正。

#### migrations/config/test

- `db/migration/V7__tenant_columns_expand.sql`：为 run/seq/record/reject/discrepancy/disposition/reversal/action/alert/report/partial 等业务表 nullable add tenant，按 run 关系回填，无法关联者回填 `__legacy__` 并产出核验计数。
- `db/schema/{h2,mysql,postgresql}/V8__tenant_constraints.sql`：tenant NOT NULL；替换 run/seq/fingerprint/disposition/reversal/action 等唯一键为tenant前缀；给 reversal 增执行lease列与 `EXECUTING` 约束，保留legacy数据。
- `db/migration/V9__benefit_ods_remediation.sql`：现金 ODS、非金额 entitlement ODS、tenant inbox、remediation、command outbox。
- `application.yml` 新增 `recon.ods.benefit.*`、Kafka topic、auto allowlist（默认空）、relay/DEAD配置。
- 新增 `BenefitCashThreeWayEndToEndTest`、`EntitlementFulfillmentEndToEndTest`、`BenefitOdsIngestionTest`、`BenefitRemediationClosureTest`、`ReconTenantIsolationTest`；扩展 source-db、rerun、A1 convergence、security、reversal并发测试。

## 8. 数据库设计

### 8.1 benefit-center 表

所有时间 `TIMESTAMP(3)`、金额 signed `BIGINT` 最小单位、数量 `BIGINT`；payload采用 TEXT/LONGTEXT并由应用校验JSON。所有业务表带 `tenant_id`，不建跨分片外键，以应用端口/一致性扫描保证引用。

| 表 | 关键字段 | 唯一键/索引与不变量 |
|---|---|---|
| `bc_tenant_config` | tenant_id, home_cell, status, version | PK tenant；order固化homeCell |
| `bc_benefit_sku` | tenant, sku_id, type, currency, face_value_minor, min/max, status, metadata | UK(tenant,sku_id), idx status |
| `bc_channel_route` | tenant, route_id, sku_id, priority, channel_code, owner_kind, fallback_route_id, reserve_mode, enabled, version | UK(tenant,sku,priority), fallback无环应用校验 |
| `bc_inventory_account` | tenant, account_id, sku, owner_type, owner_id, available,reserved,issued,version,snapshot_at | UK(tenant,sku,owner_type,owner_id)；balance非负 |
| `bc_inventory_ledger` | entry_no, account_id,item_no,operation_no,type,delta_*,created | UK(tenant,account,operation,type)；只追加 |
| `bc_code_asset` | code_asset_id, sku, code_hash, cipher_text,key_version,status,reserved_item,expires,version | UK(tenant,sku,code_hash)；明文不落库 |
| `bc_award_order` | order_no, source_system,source_request_id,business_no,recipient_ref,decision/activity snapshot,policy,status,counts,request_hash,trace,home_cell,version | UK(tenant,source_system,source_request_id), idx tenant+business |
| `bc_award_item` | item_no,order_no,client_item_id,sku,type,amount/currency,status,route,fail_code,retry_at,version | UK(tenant,order,client_item_id), idx status+retry |
| `bc_fulfillment_operation` | operation_no,item_no,type,remediation_no,status,idempotency_key,lease_owner,lease_until,attempt_count,unknown_since,version | UK(tenant,idempotency_key), idx due/lease |
| `bc_fulfillment_attempt` | attempt_no,operation_no,seq,channel,route,channel_request_no,serial,status,error,redacted request/response,time | UK(tenant,operation,seq), UK(tenant,channel,channel_request_no) |
| `bc_award_ledger_entry` | ledger_no,order/item/operation,entry_type,amount_minor,quantity_signed,currency,owner,channel,serial,biz_time | UK(tenant,operation,entry_type)；只追加 |
| `bc_remediation_order` | remediation_no,external_command_id,source,item,action,original_operation,reason,approval,status,version | UK(tenant,source,external_command_id) |
| `bc_channel_callback` | channel,callback_event_id,request_no,payload_hash,status,received/processed | UK(tenant,channel,callback_event_id) |
| `bc_outbox_event` | event_id,aggregate,event_type,schema,payload,status,attempt,next,published | PK(tenant,event_id), idx status+next |
| `bc_inbox_message` | consumer_group,message_id,payload_hash,status,received/processed | PK(tenant,consumer_group,message_id) |
| `bc_physical_fulfillment` | item_no,address_ref,shipment_ref,status,version | UK(tenant,item_no)；无明文地址 |

### 8.2 库存 SQL 不变量

reserve 必须是单条：`UPDATE ... SET available=available-?, reserved=reserved+?, version=version+1 WHERE tenant=? AND account_id=? AND available>=? AND version=?`。commit/release/return同理检查 reserved/issued足量。受影响行数是唯一写决策；成功后同事务追加 inventory ledger。

### 8.3 recon ODS/remediation 表

ODS landing 先保留公共血缘列 `id,event_id,tenant_id,order_no,issue_id,provider_ref,biz_status,biz_time,posting_time,award_order_no,award_item_no,benefit_sku_id,benefit_type,raw_event_ref,created_at`。现金投影额外要求 `ccy,amount_minor,entry_type,channel_serial_no`，进入 `BENEFIT_CASH_3WAY`；非金额投影要求 `quantity_signed,sku_code,provider_artifact_type`，进入 `ENTITLEMENT_FULFILLMENT`。两类投影物理分表，金额列与数量列都不通过占位值互相冒充。UK均为 `(tenant_id,event_id)`，另建 tenant+biz_time+id keyset索引。

`ods_message_inbox`：PK `(tenant_id,consumer_group,event_id)` + payload_hash/status。`remediation_suggestion`：tenant/fingerprint/target item+operation/action/status/approval/version/idempotency，UK `(tenant_id,idempotency)`；`remediation_command_outbox`：tenant/commandId/payload/status/attempt/next，UK `(tenant_id,command_id)`。

### 8.4 数据迁移

1. benefit-center 是新库，无存量；Flyway从V1建表，生产 `ddl-auto=validate/none`。
2. activity connector仅expand新表；不修改/搬迁 `activity_grant*`。切换时点前的 HELD继续legacy confirm/release直到为0。
3. recon V7先加nullable tenant并把旧行回填 `__legacy__`；应用双读兼容后，V8按方言改NOT NULL/unique；不得直接在旧唯一键上硬加tenant导致启动失败。
4. 新 ODS 只消费 cutover watermark之后事件。不得回放 legacy grant为AwardIntent；旧/新场景按 source namespace分账。
5. 回滚不drop表/列/分录；数据错误用反向ledger或新remediation前滚修复。

### 8.5 remediation 安全矩阵

自动动作的共同前置条件：已过等待窗、tenant在allowlist、审批策略满足、无未决UNKNOWN、目标 item/原 operation 与建议快照一致、未存在同指纹未终结command、额度/频率未触发熔断；任一条件不满足降级 `MANUAL_REVIEW`。

| 三方事实/差异 | 建议动作 | benefit-center 二次校验 |
|---|---|---|
| expected有、accounting无、fulfillment无，且原operation明确 `FAILED_FINAL/NOT_ISSUED` | `REISSUE` | item确未成功；原operation非UNKNOWN；配额、路由、库存仍有效；新operationNo |
| expected有、accounting无、fulfillment无，但原operation `UNKNOWN/QUERYING` | `MANUAL_REVIEW`/继续query | 禁REISSUE与fallback |
| fulfillment有、accounting无 | 财务入账修复或人工，不发权益command | 不把记账延迟误判为漏发 |
| accounting有、fulfillment无 | 等待/query或人工 | 渠道未证明NOT_ISSUED前禁补发 |
| expected无但accounting+fulfillment有，或确认重复ISSUE | `REVERSE`候选 | 两笔success均有唯一operation/serial；目标未使用/未核销/未发货且adapter支持reverse；需批准 |
| 金额、币种、状态不一致 | 默认 `MANUAL_REVIEW` | CASH金额错误不得自动猜测补差；按资金审批规则处理（待验证） |
| timing mismatch 且仍在等待窗 | `WAIT`，不建command | 窗口结束后重新分类 |

`BenefitRemediationSuggestionHandler` 只“建议”；Flowable/人工审批负责批准，`RemediationCommandRelay` 只发布已批准且CAS成功的行，benefit-center仍有最终拒绝权。

## 9. REST API 契约

### 9.1 开放 API

#### `POST /openapi/v1/award-orders`

Headers：`Authorization: Bearer`、`Idempotency-Key`（必须等于/映射sourceRequestId）、`X-Trace-Id`可选；tenant由token解析。

请求核心：

```json
{
  "schemaVersion": "1.0",
  "sourceSystem": "activity-platform",
  "sourceRequestId": "stable-request-id",
  "sourceBusinessNo": "order-123",
  "recipientRef": "user-ref",
  "decision": {"decisionId":"...","activityId":"...","activityVersion":3},
  "partialPolicy": "BEST_EFFORT",
  "items": [
    {"clientItemId":"gift-1","benefitSkuId":"SKU-COUPON-1","benefitType":"COUPON","amountMinor":null,"currency":null}
  ]
}
```

返回：首次 `202`，replay `200/202` 均带同一 `awardOrderNo/status/replay`；同键异payload `409 IDEMPOTENCY_PAYLOAD_CONFLICT`；校验失败400；无权限401/403；限流429。

#### 查询

- `GET /openapi/v1/award-orders/{awardOrderNo}`：order/items/operations的稳定公共投影，敏感码只返回受控delivery token/掩码。
- `GET /openapi/v1/award-orders/by-source?sourceSystem=&sourceRequestId=`：幂等恢复查询。

### 9.2 内部 remediation

- `POST /internal/v1/remediations/reissue`
- `POST /internal/v1/remediations/reverse`
- `GET /internal/v1/remediations/{remediationNo}`

请求必须含 externalCommandId、awardItemNo、originalOperationNo、reason、approvalRef。中台仍自行执行状态/渠道query/可逆能力安全校验，不能因 recon已审批而跳过。

### 9.3 callback/admin

- `POST /internal/v1/channel-callbacks/{channelCode}`：签名、timestamp、nonce、防重放；tenant由route/account映射。
- `POST/PUT /admin/v1/benefit-skus`、`/channel-routes`、`/inventory-adjustments`、`/code-imports`：admin scope、四眼/审计建议待验证；任何库存adjust必须写ledger。

## 10. MQ 契约

逻辑 topic（实际前缀/集群待平台确认）：

| Topic | Key | Producer→Consumer | 语义 |
|---|---|---|---|
| `benefit.award-intent.v1` | tenant:sourceRequestId | activity/others→benefit | REST的异步等价入口，至少一次 |
| `benefit.fulfillment-event.v1` | tenant:awardOrderNo | benefit→activity/query/recon | 含接单事务outbox生成的 `AWARD_ITEM_EXPECTED`，以及order/item/operation状态事实 |
| `benefit.ledger-entry.v1` | tenant:awardOrderNo | benefit→recon ODS | 不可变ISSUE/REVERSAL |
| `benefit.fulfillment-receipt.v1` | tenant:operationNo | benefit→recon ODS | Adapter结果先由benefit短事务标准化并落outbox，再发布渠道/中心凭证；Adapter不得直写Kafka |
| `benefit.remediation-command.v1` | tenant:awardItemNo | recon→benefit | REISSUE/REVERSE，commandId幂等 |
| `benefit.remediation-result.v1` | tenant:commandId | benefit→recon | 执行收敛结果 |

所有消息包 `MessageEnvelope`；eventId全局唯一、schemaVersion、occurredAt、tenantId、traceId、partitionKey必填。消费者以 `(tenant,consumerGroup,eventId)` inbox 去重，并校验 payloadHash；未知major进DLQ。Producer与DB不做双写，全部从outbox发布。

## 11. 配置变更

### benefit-center

`benefit.security.*`（issuer/jwks/audience map/scopes）、`benefit.cell.id=cell-0`、`benefit.worker.*`（role/batch/lease/retry）、`benefit.outbox.*`、`benefit.kafka.*`、`benefit.channel.<code>.*`（enabled默认false、timeout/rate/circuit/secretRef）、`benefit.remediation.auto-enabled=false`、`benefit.code.kms-key-ref`。密钥只通过Secret/Vault引用，不进yml。

### activity

`activity.award-intent.mode=LEGACY|SHADOW|CENTER`（默认LEGACY）、publisher=rest|kafka、endpoint/topic、timeout、relay batch/attempt/backoff。生产切CENTER还需版本化binding deliveryMode；两个条件均满足才发，防误开全租户。

### recon

`recon.ods.benefit.kafka.*`、`recon.scenario.benefit.*`三表名、tenantColumn/bizTimeColumn、`recon.remediation.auto-enabled=false`、allowlist tenant/error/action、command outbox重试。旧 `recon.m4.*` 不改。

## 12. 分阶段实施、依赖与完成标准

### Gate 0：业务与基础设施确认（编码前）

依赖：P0待验证项。

- 确认首批真实渠道、触发时点、fallback等价SKU、资金/隐私要求、remediation审批矩阵、Kafka标准。
- 冻结 OpenAPI/AsyncAPI v1 与错误码、item原子性和 UNKNOWN 规则。

完成标准：P0决议写ADR；未确认真实渠道保持route disabled，但不阻塞domain/mock闭环。

### 阶段一：数据结构与领域模型

1. 创建 benefit parent/contract/domain/application骨架和ArchUnit。
2. 完成 AwardIntent、状态机、route/inventory/ledger/remediation领域模型。
3. 完成 benefit V1/V2 Flyway与JDBC ports；MySQL迁移/回滚演练。
4. activity 新 binding/intent outbox实体与显式SQL；recon V7/V8 tenant expand、V9 ODS/remediation。

依赖：Gate0契约；先expand schema，后部署读取新列的代码。

完成标准：三项目compile；domain状态机/DDL/唯一键/CAS组件测试绿；旧数据回填与旧版本兼容验证；无渠道外调。

### 阶段二：核心业务逻辑

1. `AwardApplicationService#accept`：hash幂等、order/items、库存reserve、operation/outbox本地事务。
2. `FulfillmentApplicationService`：lease、route、UNKNOWN/query/fallback、短事务settle、部分聚合。
3. code/physical中心Adapter；ledger/inventory ledger。
4. `RemediationApplicationService`：REISSUE/REVERSE安全门、状态CAS、结果事件。
5. outbox/inbox relay、退避/DEAD/redrive。

依赖：阶段一表/ports；真实Adapter可后接。

完成标准：MySQL+channel simulator 下五种原子权益及组合奖品/reference 闭环；100并发幂等/热点库存零超卖；每个事务边界 kill 后唯一收敛；无 OpenAPI 外部暴露也可由 use case 测试驱动。

### 阶段三：接口与适配层

1. OpenAPI controllers/security/tenant、Kafka consumer/publisher、callback验签、admin API。
2. 按已确认渠道实现Adapter并跑统一contract suite；未确认route保持disabled。
3. Drools binding保存/回显、server decision assembler、intent outbox/relay、write权限、LEGACY/SHADOW/CENTER硬隔离。
4. recon tenant-aware source、`BENEFIT_CASH_3WAY`、`ENTITLEMENT_FULFILLMENT`、ODS consumer、remediation handler/审批/command relay/result。
5. deploy/health/metrics/alerts/CI。

依赖：阶段二use case稳定；activity先consumer-compatible contract，benefit consumer先上，producer后开。

完成标准：跨仓E2E通过；既有decision/legacy grant/MARKETING_3WAY回归不变；tenant隔离和权限矩阵通过；sandbox渠道有真实凭证。

### 阶段四：测试

按 `test-plan.md` 完成单元、MySQL、Kafka、Adapter、跨仓、故障注入、性能、安全和灰度演练。不得用删/弱化既有测试换绿。

完成标准：测试计划§12全部勾选；容量/SLA值用批准数据替换建议值；P0/P1缺口列入发布gate而非口头豁免。

### 阶段五：文档与最终检查

- OpenAPI/AsyncAPI发布说明、channel onboarding、数据字典、状态机、runbook、监控、灾备、灰度/回滚、数据保留。
- 对照实际diff更新本计划；检查所有新增写端点权限、所有表tenant/index/unique、所有消息inbox/outbox。
- 运行全量构建、image scan、migration validate、契约兼容和依赖漏洞扫描。

完成标准：另一个值班团队仅凭runbook可完成启停、灰度、DEAD redrive、UNKNOWN排查、暂停remediation和回滚新流量；最终验收清单全签字。

## 13. 里程碑

| 里程碑 | 建议周期 | 交付 |
|---|---:|---|
| M0 契约/ADR | 1周 | Gate0决议、OpenAPI/AsyncAPI、表/状态机评审 |
| M1 skeleton | 2周 | 新项目、领域、Flyway/JDBC、tenant expand |
| M2 core | 3周 | 幂等/库存/worker/ledger/reference adapters/remediation |
| M3 integration | 3周 | REST/Kafka、真实Adapter、Drools、ODS/recon |
| M4 quality | 2周 | E2E/故障/安全/压测/迁移演练 |
| M5 gray | 1–2周 | shadow→tenant灰度→全量、runbook验收 |

周期是架构估算，不是已承诺排期；首批渠道数量和合规审批会直接改变M3。

## 14. 风险、监控与高可用

### 14.1 主要风险与控制

| 风险 | 控制 |
|---|---|
| legacy+center双发 | activity mode+binding双门；CENTER activity拒绝新legacy claim；source key审计 |
| 渠道未知误fallback | UNKNOWN状态硬规则；query original operation；无query渠道人工 |
| 热点库存锁 | CAS短事务、稳定锁序、分桶account候选、按真实热点压测 |
| worker重复执行 | DB lease+version CAS+渠道稳定idempotency；旧owner提交拒绝 |
| outbox/Kafka积压 | age/lag/dead告警、batch/redrive、容量backpressure；不绕过outbox直发 |
| code/PII泄露 | KMS密文+hash、addressRef、日志redaction、secretRef、字段级访问 |
| recon假差异 | ODS watermark+tenant/window、事实事件水位、等待窗；UNKNOWN不自动remediate |
| auto remediation资损 | 默认关闭/allowlist空、审批、benefit二次安全门、command幂等、额度/频率熔断 |
| 单库容量 | API/worker role隔离、连接预算、读查询索引、归档；达到触发条件后拆worker/Cell |

### 14.2 指标

低基数标签仅 type/channel/status/tenant分桶，不含user/order/item：

- `benefit_award_accept_total{outcome,source}`、`benefit_award_accept_duration`
- `benefit_award_item_total{type,status}`、`benefit_partial_success_total`
- `benefit_inventory_balance{owner,type}`、`benefit_inventory_reservation_age`
- `benefit_channel_request_total{channel,operation,outcome}`、latency、circuit/rate-limit
- `benefit_operation_unknown_total{channel}`、unknown age
- `benefit_outbox_pending/dead/oldest_age`、`benefit_inbox_conflict_total`
- `benefit_remediation_total{action,status}`
- recon job failure/duration、ODS consumer lag/reject、现金/非金额场景 discrepancy 与 auto-remediation rate。

告警：库存负数/ledger invariant立即P0；UNKNOWN age、outbox oldest、DEAD、部分成功率突升、channel错误、ODS lag、recon imbalance、remediation失败按阈值分级。具体阈值必须由压测和业务SLA批准。

### 14.3 高可用

- API/worker无状态多副本，多AZ；worker lease防单点。
- MySQL主备/托管HA，binlog备份+恢复演练；RPO/RTO待SRE确认。
- Kafka多副本；broker故障时outbox留存，容量不足时API主动429/503而非接单后丢事件。
- 渠道bulkhead/circuit/rate limit按channel隔离；单渠道故障不耗尽全局线程/连接。
- readiness检查DB与必要schema，不把外部渠道短故障作为API下线条件；worker readiness可独立。

## 15. 灰度方案

1. **Deploy dark**：benefit schema/service上线，所有真实route disabled，auto remediation off。
2. **Contract shadow**：activity `SHADOW`只重算/组装/hash/metrics，不投递；比对binding覆盖和item数。
3. **Internal tenant**：一个测试tenant CENTER，reference/sandbox渠道；recon仅观察不remediate。
4. **1% source/activity**：按tenant+activity/version allowlist；监控双入账、UNKNOWN、partial、库存和ODS lag。
5. **10%→50%→100%**：每档至少覆盖渠道结算周期/批准观察窗；只扩大新请求。
6. **Remediation shadow→manual→allowlist auto**：先只建议，再人工审批，再开启低金额/明确未发白名单；reverse默认最后开。

每次扩档 gate：无双发、无负库存、outbox/ODS水位达标、三方守恒、channel sandbox/production凭证一致、回滚演练仍有效。

## 16. 回滚方案

### 应用/流量

- activity 从 CENTER切回LEGACY只影响**新 sourceRequestId**；已被benefit ACCEPTED的订单继续由benefit收敛。禁止把已接单请求转发legacy。
- 暂停新流量时 benefit ingress返回明确503/429，worker/outbox仍运行；必要时单独暂停某channel route或auto remediation。
- 版本回滚按 consumer-first/producer-last；契约只做向后兼容增量。旧版本不认识新major时保持DLQ，不猜处理。

### 数据

- 所有迁移expand-only；不drop新列/表，不删除ledger/ODS/inbox。
- 错发以REVERSE分录修，漏发以REISSUE operation修；状态错误用受审计的数据修复脚本前滚。
- recon tenant migration回滚期应用可读 `__legacy__`；V8约束完成后不恢复旧全局unique，避免跨tenant污染。

### 消息

- 不随意回退consumer offset；需要重放时换受控consumer group/指定eventId，依赖inbox幂等。
- DEAD redrive先修根因和验证单条，再小批量；禁止清空DLQ/outbox表。

### 判定回滚触发

任一触发即停止扩档并切新流量：确认双发、库存负数/账不守恒、跨租户、真实渠道金额/类型错误、UNKNOWN持续超SLA、outbox/ODS不可恢复积压。单渠道故障优先disable route，不必全局回滚。

## 17. 测试方案

执行 `test-plan.md` 全部内容。最低发布门禁：

1. domain状态机与无default枚举覆盖。
2. MySQL 100并发同intent唯一、热点库存零超卖、worker lease接管、callback乱序。
3. Kafka重复/乱序/rebalance/outbox崩溃恢复。
4. 每个真实Adapter统一contract suite + sandbox凭证。
5. Drools golden/contract/legacy grant/tenant/security全回归。
6. recon old `MARKETING_3WAY` + new `BENEFIT_CASH_3WAY`/`ENTITLEMENT_FULFILLMENT`、tenant/window、rerun、A1、remediation闭环。
7. 跨系统 partial→reissue→next recon clean 与 success→reverse净额0。
8. 故障、容量、安全、灰度/回滚演练有报告，不以“代码看起来支持”代替证据。

## 18. 最终验收清单

### 业务与契约

- [ ] P0待验证项已决议；真实渠道支持矩阵和不可逆边界已签字。
- [ ] OpenAPI/AsyncAPI v1发布、兼容门禁通过；AwardIntent不信任客户端金额/SKU/channel。
- [ ] 五种原子权益/reference 路径和组合部分成功可查询、可解释、可补发/冲正或明确人工。

### 数据与正确性

- [ ] 所有表tenant/index/unique和source payload hash检查完成。
- [ ] 100并发同intent一单；热点库存零超卖；UNKNOWN不fallback。
- [ ] inventory/award ledger只追加，issue+reversal净额/净数量正确。
- [ ] 存量grant不迁移不重放，legacy/new无双算；HELD排空计划完成。

### 集成与对账

- [ ] Drools版本化binding、server重算、outbox可靠投递和cutover硬门通过。
- [ ] ODS tenant/window/watermark 不漏不重；`BENEFIT_CASH_3WAY` 金额守恒和 `ENTITLEMENT_FULFILLMENT` 数量守恒分别通过。
- [ ] remediation默认关闭、审批/allowlist/二次安全门、command/result闭环通过。
- [ ] 旧 `ReversalExecutionService` 并发CAS加固，既有审批/执行回归通过。

### 安全、HA与运维

- [ ] JWT tenant、scope、跨租户、callback签名、KMS/PII redaction测试通过。
- [ ] DB/Kafka/worker/channel故障和恢复演练通过；备份恢复证据齐全。
- [ ] metrics/alerts/dashboard/runbook/DEAD redrive/UNKNOWN排查/暂停渠道均实操通过。
- [ ] 容量达到批准峰值×2，SLA/RTO/RPO从“建议”更新为批准值。

### 灰度与回滚

- [ ] dark/shadow/internal/1%/10%/50%/100% gate逐项留证。
- [ ] CENTER→LEGACY只切新流量，已接单继续收敛且无双发。
- [ ] schema/message/data回滚策略演练；无drop ledger/offset盲回退。

## 19. 所选方案的已知弱点

1. 一期单逻辑库可能在超级热点 SKU 上形成行锁瓶颈；只有压测后才能决定分桶库存或拆 inventory service。
2. API/worker虽可用role分部署，仍共享schema和发布节奏；尚未获得真正微服务的独立演进。
3. 外部渠道最终一致不可消除；无幂等/查询能力的渠道只能降低自动化等级。
4. eager中心兜底提高成功率但占用库存；需要reservation age与业务权衡。
5. legacy/new客服与对账短期双来源；一期刻意用隔离换低迁移风险。
6. Kafka、真实渠道、资金合规和Cell目标均有待验证项；计划通过port/开关保留接缝，但不能把“可扩展”误写成“已上线”。

这些弱点已纳入监控、灰度和演进触发条件，未以“未来再说”掩盖。

## 20. 资深架构师终审记录

2026-08-21 已对本计划做第二轮一致性审查并修正以下问题：

- 将 activity binding/outbox JPA 写实体与 repository 从 `activity-common` 移到 `activity-console`，避免只读 decision 运行时扫描写仓储或被迫校验连接器表。
- 将 recon tenant 从 run 维度扩展到 fingerprint、差异、处置、冲正、action、alert、report 和 ODS/inbox；所有跨期唯一键改为 tenant 前缀，防止跨租户同业务键串案。
- 把旧 reversal 的“新增 EXECUTING”补全为 expected-state CAS、owner、可过期 lease、stable idempotency key 和崩溃恢复语义，关闭外调前并发双执行窗口。
- 明确 AwardOrder/AwardItem/Operation/Remediation 四套状态机、UNKNOWN禁补发/fallback、CHANNEL_SHADOW不可由业务事务返还。
- 修正 ODS inbox/command 唯一键 tenant 范围；明确渠道回执必须先由 benefit-center 落库/outbox，Adapter不能绕过事务边界直发。
- 增加 remediation 安全矩阵，区分履约缺失、记账延迟、UNKNOWN、孤儿/重复发放和金额差异，避免以通用“自动纠错”掩盖资损风险。
- 主审阶段否决非现金 `XXX/0` 方案：改为独立 `EntitlementObservation` 与数量/状态分类器，现金继续走 `Money`；避免形式守恒掩盖券、码和实物语义错误。

终审后的剩余不确定性仅为本文明确标注的“待验证”外部业务/基础设施决策；在这些 P0 gate 未决前，真实route和自动remediation必须保持关闭。本文未修改任何业务代码，也未执行可能在仓库外生成构建产物的测试命令。
