# 现有代码库分析与影响面

> 视角：codebase-explorer。分析基于 2026-08-21 的两个独立 Git 仓库：`drools-demo` HEAD `0e62c7b`、`recon-platform` HEAD `167edbd`，两者工作区均 clean。工作区根目录本身不是 Git 仓库。未运行构建/测试，因为本任务明确只允许写规划目录，Maven/Vitest 会写 `target`/缓存。

## 1. 仓库边界

### drools-demo

Java 21 / Spring Boot 3.3.5 / Drools 8.44.2，多模块父 POM实际包含 `activity-common`、`drools-lab`、`activity-console`、`activity-decision`（`drools-demo/pom.xml:28`）。`activity-decision` 是只读决策面，`activity-console` 是写面和唯一 DDL 执行者。

### recon-platform

Java 21 / Spring Boot 3.3.5，多模块父 POM包含 core、DB/CSV source、scenario、handler、Drools rules、Flowable workflow 和 batch 组合根（`recon-platform/pom.xml:25`）。`recon-core` 被 ArchUnit 约束为纯 Java，JDBC/Spring Batch 只能在外圈。

### 结论

仓库中不存在 `benefit-center`、`AwardIntent`、权益 SKU/渠道 Adapter/兑换码池/实物履约模型。任务要求的独立中台必须作为规划中的**新增项目**，不能把现有类描述成已经支持。

## 2. 活动决策调用链

### 红包/折扣

`POST /decision/v1/spu-discount` → `DecisionPlaneController#spuDiscount` → `ActivityQueryService#spuDiscount(req, HOT_PATH)`（`drools-demo/activity-decision/src/main/java/com/lrj/drools/activity/controller/DecisionPlaneController.java:108`）。服务固定 5 次取数、资格树、六形态算额与合并；返回 `DiscountView`。

`DiscountView` 已包含 `decisionId`、命中版本、逐候选 `items[]` 和 provenance（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:411`）。每个 `DiscountItem` 有 activity/version/benefitForm/amount/applied/rejectReason（同文件 `:439`），这可作为 AwardIntent 的服务器端决策证据，但它仍是定价减免，不是可直接发放的 benefit SKU。

### 买赠

`POST /decision/v1/gifts` → `DecisionPlaneController#gifts` → `ActivityQueryService#buyAndGetGifts(req, HOT_PATH)`（controller `:141`；service `:269`）。`GiftResult` 已有 activityId/version/batchId/giftType/rightType/数量/金额（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/GiftResult.java:17`），可用 `batchId` 加版本化绑定映射权益 SKU。

### 加价购

`POST /decision/v1/addon/options|quote` → `AddOnPurchaseService#options|quote`（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:127`、`:179`）。quote 会重新取数并拒绝客户端价格；这符合服务端权威原则。但加价购是支付后的购买履约，不应在一期把“报价成功”直接等同于免费发权益，触发时点待验证。

### 决策输入

`SpuDiscountRequest` 只有 SPU、用户、地域、标签、订单金额/数量、门店和订单行（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/SpuDiscountRequest.java:32`），没有 orderId、sourceRequestId、recipientRef，也没有 benefit SKU。因此 AwardIntent 提交必须是独立写请求，不能直接在当前只读 decision controller 中产生副作用。

## 3. 现有活动发放链路

### API 与状态

`ActivityMarketingController` 暴露：

- `POST /{activityId}/claim` → `GrantService#claimInventory`（controller `:140`）；
- `POST /{activityId}/confirm` → `GrantService#confirmGrant`（controller `:159`）；
- `POST /{activityId}/release` → `GrantService#releaseGrant`（controller `:176`）；
- `GET /grants` 查询订单流水（controller `:209`）。

`ActivityGrantEntity` 状态只有 `HELD/CONFIRMED/RELEASED`，唯一键为 `(tenant_id, order_id, activity_id)`，并记录 amount/decisionId/grantNo/currency（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivityGrantEntity.java:41`）。它适合单活动金额占用，不支持一个 order 下的异构 item、渠道状态、部分成功或补发操作。

### 并发与幂等可复用模式

- `GrantService#claimInventory` 先插 grant 再用单条条件 UPDATE 扣库存（`:158`、`:224`），避免 check-then-act。
- `ActivityGrantRepository#confirmIfHeld` 以 `WHERE state='HELD'` CAS；重复确认 first-write-wins（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivityGrantRepository.java:34`）。
- `releaseGrant` 分别 CAS HELD/CONFIRMED 后再归还库存（`GrantService.java:341`）。
- ISSUE/REVERSAL 在 `ActivityGrantEntryEntity` 追加，唯一 `(grant_no, entry_type)`（`.../ActivityGrantEntryEntity.java:38`），不覆盖历史。

这些是新中台应复用的**模式**，不是可直接复用的跨项目类。

### 库存现状

库存和每人限领字段仍在版本化 `activity_manage` 行（`ActivityManageEntity.java:90`）。`ActivityManageRepository#decrementInventory` 用活动状态/时间窗/余量的单 UPDATE 防超发（`.../ActivityManageRepository.java:94`）；`incrementInventory` 无时间窗以支持迟到退款（同文件 `:113`）。这只是活动库存，不是公司级 SKU 中心库存，也没有渠道库存影子。

### Outbox 现状

`confirmGrant/releaseGrant` 可在同事务写 `activity_grant_outbox`；`GrantOutboxRelay` 事务外投递、每条短事务置态，支持退避、DEAD 和 redrive（`drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/GrantOutboxRelay.java:23`）。

`GrantEvent` 只有 grant/order/activity、ISSUE/REVERSAL、amount/currency 和 JSON（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/spi/GrantEvent.java:22`）；幂等键为 `grantNo:eventType`。默认 dispatcher 只打日志，Webhook 才真实 POST（`.../WebhookGrantEventDispatcher.java:14`）。它不能表达 AwardIntent item、SKU、recipient、渠道路由、部分成功或补发，故不能直接改名复用。

### 安全与多租户

活动平台已有 JWT 验签、aud→tenant、信封一致性和 Hibernate `@TenantId` 隔离。`ActivityResourceServerConfig` 对 create/status/claim/confirm/release 明确列写权限（`.../ActivityResourceServerConfig.java:61`）；`JwtTenantFilter` 不信任 `X-Tenant-Id`，且 finally 清 ThreadLocal（`.../JwtTenantFilter.java:41`）。新中台应保持这一机制边界，但不能复制 dev 配置中的 `auth=false/dev-default=true` 到生产。

## 4. 活动配置模型缺口

`ActivityCreateRequest` 有活动形态、红包参数、gift rows、inventory、currency，但没有 `benefitSkuId` 或版本化 AwardBinding（`drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCreateRequest.java:17`）。

`ActivityMarketingService#create/createInternal/updateByVersion` 负责版本化写入；`saveManage`、`saveGifts`、`saveManualBindings` 分别保存主表/赠品/商品绑定（`drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:149`、`:991`、`:1082`、`:1101`）。新 AwardBinding 应跟随 activity version 保存和复制，否则旧决策会映射到新 SKU。

## 5. 现有 recon 调用链与数据模型

### 三方场景

`MarketingThreeWayScenario` 已实现两段：

- SEG1 MARKETING↔ACCOUNTING，match=issue_id、group=order_no；
- SEG2 ACCOUNTING↔CHANNEL，match/group=channel_serial_no。

证据见 `recon-platform/recon-scenario/src/main/java/com/lrj/recon/scenario/MarketingThreeWayScenario.java:14`。默认三张源表是 `recon_src_marketing/accounting/channel`（同文件 `:167`）。这套责任链只直接复用于现金 `BENEFIT_CASH_3WAY`，并需更换 ODS 表、增加租户与账期过滤；非金额权益不能复用其金额聚合器。

### 源读取缺口

`DbSourceAdapter` 由 descriptor 映射表/列（`recon-platform/recon-source-db/src/main/java/com/lrj/recon/source/db/DbSourceAdapter.java:46`）。`KeysetRecordCursor#fetchNextPage` 当前 SQL 是全表 `SELECT * ... WHERE id > ?`，无 tenant 或账期条件（`.../KeysetRecordCursor.java:69`）；接入手册也明确源表只应放当账期数据（`recon-platform/docs/deploy/marketing-3way-onboarding.md:78`）。公司级多租户 ODS 不能沿用这个假设，必须为 SourceReadContext/DbSourceConfig 增加参数化 tenant 和时间窗谓词。

### 金额模型与非现金权益

`ReconRecord` 强制持有 `Money`，`Money` 只表达三字符币种和 signed long（`recon-platform/recon-core/src/main/java/com/lrj/recon/core/domain/model/Money.java:17`）。分类器、聚合器、报表都以金额为核心（`.../DiscrepancyClassifier.java:34`）。因此非现金原子 item 不能填 `XXX/0` 伪装金额；一期新增旁路 `EntitlementObservation` 与数量/存在/状态分类器，共享 run、source cursor、分桶、差异、处置和审批基础设施，但保留现有 `ReconRecord/Money/MARKETING_3WAY` 不变。

### 批处理

`MarketingThreeWayConfig` 依次执行两段 load→partitioned match→report；`ReconLaunchService#launch/rerun` 创建 run（`recon-platform/recon-batch/src/main/java/com/lrj/recon/batch/job/ReconLaunchService.java:72`、`:114`）。当前 `RunKey` 只有 scenario/period/sequence（`recon-platform/recon-core/src/main/java/com/lrj/recon/core/domain/model/RunKey.java:11`），`SourceReadContext` 也没有 tenant（`.../SourceReadContext.java:9`），所以 recon 当前不是数据面多租户。

### 人工处置与 remediation

现有三表分离：机器差异 `discrepancy`、人工处置 `discrepancy_disposition`、冲正建议 `reversal_suggestion`（`recon-platform/recon-batch/src/main/resources/db/migration/V1__recon_schema.sql:88`、`:111`、`:128`）。重跑保护人工状态是可复用不变量。

`ReversalSuggestionHandler` 只对金额不符生成建议，且文档仍写“不自动执行”（`recon-platform/recon-handler/src/main/java/com/lrj/recon/handler/ReversalSuggestionHandler.java:19`）。另一方面仓库已有 `ReversalExecutionService#execute` 和 `ReversalExecutor`，默认实现只打日志（`recon-platform/recon-batch/src/main/java/com/lrj/recon/batch/service/ReversalExecutionService.java:38`；`LoggingReversalExecutor.java:9`）。当前执行流程先调用外部，再无条件 `updateStatus`，没有状态 CAS 或执行 lease；两个并发请求可能都通过 `CONFIRMED` 检查并双执行，因此不能直接作为自动权益冲正闭环。

Benefit remediation 同时需要 REISSUE/REVERSE/MANUAL_REVIEW，语义大于 reversal。应新增通用 remediation 模型和 command outbox，而不是把补发硬塞进 `ReversalSuggestion`。

## 6. 现有 DDL 与接入事实

活动仓库用 `mysql-grant-recon-onboarding.sql` 建 `recon_src_marketing` 视图，从 `activity_grant_entry` 投影 ISSUE/REVERSAL（`drools-demo/deploy/mysql-grant-recon-onboarding.sql:60`）。该视图不切 tenant（脚本 `:56`），只适合现有已拍板的单租户营销场景，不能作为新中台 ODS。

recon 的三张源表不由 Flyway 管理，而由上游/ETL 负责（`recon-platform/docs/deploy/marketing-3way-onboarding.md:24`）。新方案将 benefit ODS 归 recon Flyway 管理并由事件幂等落地，以避免视图直连业务库和全表当期假设。

## 7. 可复用资产

| 资产 | 可复用内容 | 不可直接复用点 |
|---|---|---|
| `GrantService` + repositories | 条件 UPDATE、状态 CAS、红蓝字追加 | 模型仅单活动金额，不能作为中台订单 |
| `GrantOutboxRelay` | 本地事务 outbox、事务外 I/O、短事务置态、退避/DEAD/redrive | 当前 JPA 多租户扫描和事件 schema 仅适合 activity |
| `GrantEventDispatcher` | transport SPI 思路 | `GrantEvent` 字段不足，Webhook 不等于 MQ |
| 活动 JWT/tenant | aud→tenant、信封校验、写权限、ThreadLocal 清理 | dev 默认不安全；中台建议显式 tenant port/SQL，不依赖隐式 JPA tenant |
| `MarketingThreeWayScenario` | 两段 spine、存在/金额/状态/时点判差、守恒 | 缺 tenant、ODS、benefit remediation |
| recon SourceAdapter | DB/CSV 插件、keyset、reject 血缘 | DB reader 无 tenant/账期过滤 |
| recon handler/Flowable | 建议与人工状态分离、审批骨架、审计 | reversal 模型太窄；执行缺 CAS/lease；默认不动真实业务 |

## 8. 受影响文件清单

以下是最终推荐方案的**现有文件影响面**；新增文件的精确规划见 `FINAL_PLAN.md`。没有列出的业务文件不应在一期顺手修改。

### drools-demo 现有文件

| 路径 | 类/方法 | 规划改动 |
|---|---|---|
| `drools-demo/pom.xml` | modules/dependencyManagement | 若采用独立 connector module则登记；最终方案不新增 activity module，只补必要依赖版本 |
| `activity-common/.../domain/ActivityCreateRequest.java` | record + `GiftInput` | 末尾增版本化 award binding 输入，保留兼容构造 |
| `activity-console/.../service/ActivityMarketingService.java` | `createInternal`、`updateByVersion`、`saveGifts`、`getDetail` | 保存/复制/回显 `activity_award_binding`；不改既有决策算法 |
| `activity-console/.../controller/ActivityMarketingController.java` | constructor、planned `createAwardIntent` | 新增服务器端重算并可靠入队端点 |
| `activity-common/.../tenant/ActivityResourceServerConfig.java` | `activitySecurityFilterChain` | 把新写端点加入 write authority 矩阵 |
| `activity-console/src/main/resources/application.yml` | `activity.*` | 新增 connector endpoint/MQ/模式/超时/重试配置，默认 LEGACY |
| `activity-console/src/main/resources/application-mysql.yml` | datasource/JPA | 仅增加 Flyway/迁移接线时调整；现状 ddl-auto 风险必须在实施前拍板 |
| `activity-console/src/main/resources/application-h2.yml` | test profile | 对齐新表/connector 测试配置 |
| `activity-console/src/test/.../ActivityMarketingFlowTest.java` | create/edit/detail | binding 随版本保存/复制/隔离 |
| `activity-console/src/test/.../DecisionOutputContractTest.java` | response contract | 保证 AwardIntent 改造不改变既有 decision JSON |
| `activity-console/src/test/.../GrantLedgerTest.java` | legacy grant | 保证 LEGACY 路径不回归、不与 CENTER 双发 |
| `activity-console/src/test/.../GrantOutboxGatingTest.java`、`GrantOutboxTest.java` | legacy outbox | 证明旧/新 outbox 开关互斥、旧事件仍兼容 |
| `activity-console/src/test/.../TenantIsolationTest.java` | tenant | 新 binding/intent outbox 不串租户 |

明确不改：`ActivityQueryService#spuDiscount/buyAndGetGifts`、`BenefitEvaluator`、`AddOnPurchaseService` 的既有决策语义；AwardIntent assembler 只消费其结果。

### recon-platform 现有文件

| 路径 | 类/方法 | 规划改动 |
|---|---|---|
| `recon-platform/pom.xml` | modules/dependencies | 登记 Kafka/ODS 适配（若实现为新模块）或统一版本；最终建议 ODS consumer 落 batch 组合根，减少模块数 |
| `recon-core/.../domain/model/RunKey.java` | record | 增 `tenantId`；唯一键变 tenant+scenario+period+seq |
| `recon-core/.../domain/model/ReconRun.java` | builder/accessors | 透传 tenant |
| `recon-core/.../spi/SourceReadContext.java` | record | 增 tenant 与 run window |
| `recon-core/.../application/port/out/ReconRunRepository.java` | lock/latest | 方法增 tenant 维度 |
| `recon-core/.../application/port/out/ReconRunSeqRepository.java` | `nextSequence` | tenant 维度分配序号 |
| `recon-source-db/.../DbSourceConfig.java` | `from` | 支持受控 `tenantColumn`/`bizTimeColumn` 过滤配置 |
| `recon-source-db/.../KeysetRecordCursor.java` | `fetchNextPage` | 参数化 tenant+窗口 WHERE，不拼请求值；保持 keyset |
| `recon-scenario/.../dsl/MarketingThreeWayDefinition.java` | `seed` | 不改现有场景；新增 benefit 定义另文件，避免回归 |
| `recon-batch/.../job/ReconJobContext.java` | record/toJobParameters | 增 tenant |
| `recon-batch/.../job/ReconLaunchService.java` | `launch`、`rerun`、`buildRunId` | 从可信身份取得 tenant，生成 tenant-scoped run |
| `recon-batch/.../config/GenericReconJobConfig.java` | source context assembly | 透传 tenant/window，装配现金 `BENEFIT_CASH_3WAY` |
| `recon-entitlement/.../EntitlementFulfillmentJobConfig.java`（新增模块） | 非金额场景 | 装配 `ENTITLEMENT_FULFILLMENT`，比较数量/存在/状态而非金额 |
| `recon-batch/.../config/ScenarioDefinitionSeeder.java` | `run` | seed benefit scenario |
| `recon-batch/.../persistence/JdbcReconRunStore.java` | claim/find/lock/latest/save | SQL 增 tenant 谓词与列 |
| `recon-batch/.../persistence/JdbcReconRunSeqStore.java` | sequence | SQL 增 tenant |
| `recon-batch/.../service/ReconConsoleQueryRepository.java`、`ReconConsoleQueryService.java` | dashboard/list/detail | 所有入口显式 tenant |
| `recon-batch/.../persistence/JdbcReconConsoleQueryStore.java` | all query methods | 所有 SQL 按 tenant/run ownership 过滤 |
| `recon-batch/.../web/DiscrepancyController.java` | launch/rerun/resolve/close/report | tenant 来自 JWT，不从 body |
| `recon-batch/.../web/ReconConsoleController.java` | reads | tenant-scoped read |
| `recon-batch/.../config/CasdoorSecurityConfig.java` | validator/filter chain | 增 tenant claim/aud 解析与 remediation 权限 |
| `recon-batch/src/main/resources/application.yml` | recon.ods/remediation/kafka | 新场景、topic、consumer、auto-remediation allowlist，默认自动执行关闭 |
| `recon-batch/src/main/resources/db/migration/*` | planned V7/V8 | expand tenant columns、benefit ODS、remediation/outbox/inbox；不改旧 V1 |
| `recon-batch/.../service/ReversalExecutionService.java` | `execute` | 旧 cash reversal 至少改状态 CAS/lease，避免与新自动流程并存时双执行 |
| `recon-core/.../application/port/out/ReversalSuggestionRepository.java` | `updateStatus` | 增 expected-state CAS 方法，保留兼容 default |
| `recon-batch/.../persistence/JdbcReversalSuggestionStore.java` | status SQL | `WHERE status=?` CAS；检查 affected rows |

### 必须扩展的现有测试

- recon core：`RunKey`/Fingerprint/状态机/ArchUnit。
- source-db：`DbSourceAdapterTest` 加 tenant/window SQL、注入防护、keyset 边界。
- batch：`MarketingThreeWayEndToEndTest` 保旧场景；新增 Benefit 端到端；`ReconJobRerunIdempotencyTest`、`DispositionConvergenceA1Test` 加 tenant；`ReversalExecutionServiceTest` 加并发 CAS；`SecurityRouteMatrixTest` 加跨租户和 remediation 权限。
- scenario/handler：现有 `MarketingThreeWayScenarioTest` 不改语义；新增 benefit scenario/remediation handler tests。

## 9. 已知基线风险

1. activity 正式配置仍是 `ddl-auto:update`（`activity-console/application.yml:15`），而新中台必须用 Flyway expand-contract；活动 connector 新表也应显式迁移，不能继续依赖 update 补唯一约束。
2. recon DB source 当前不按窗口过滤，直接接共享 ODS 会串账期和租户。
3. recon 管理台虽有 JWT 权限，但现有组织校验不是 tenant 数据隔离，必须补 tenant ownership。
4. 当前 `ReversalExecutionService` 检查状态后执行外部动作，不具备并发排他；仅有外部幂等要求不够作为平台自身保证。
5. 现有 activity grant outbox 默认关闭且 logging dispatcher 不真实发放。把它当作已上线渠道能力会产生错误验收结论。
