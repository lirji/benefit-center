# 权益发放中台一期实施结果

> 实施日期：2026-08-21  
> 当前状态：一期工程基线已完成并通过本地自动化验证；生产切流仍受本文“未完成的生产门禁”约束。

## 1. 交付结论

已按 `FINAL_PLAN.md` 的方案 B 落地独立 `benefit-center` 模块化单体，并完成与现有 `drools-demo` 活动平台、`recon-platform` 自动对账平台的窄接口集成。

当前基线具备：

- 现金红包、优惠券、服务券、兑换码、实物和组合权益统一建模。
- 请求、消息、渠道 operation、回调和 remediation 多层幂等。
- 组合奖品 item 级 `BEST_EFFORT` 履约与部分成功。
- `CENTER_QUOTA`、`CENTER_STOCK`、`CHANNEL_SHADOW` 三类库存所有权隔离。
- 渠道不确定结果 `UNKNOWN → QUERYING` 收敛；未确认前禁止 fallback 和补发。
- 明确 `NOT_ISSUED` 后的等价兜底，支持 `LAZY`/`EAGER` 中台库存预占。
- 受审批约束的 `REISSUE`、`REVERSE` 和人工复核闭环。
- REST/OpenAPI、Kafka/AsyncAPI、Channel Adapter SPI 和管理 API。
- 多租户 JWT 映射、lease/CAS 多实例 worker、Inbox/Outbox、审计分录和 Prometheus 指标。
- `tenantId + homeCell + routingKey + ShardRouter` 分库分表演进接缝。

这不是“已生产上线”的声明。真实渠道、资金合规、容量与恢复目标、生产 Kafka、真实分片迁移和自动对账切流仍需环境证据与业务审批。

## 2. `benefit-center` 已完成内容

### 2.1 工程结构与契约

新增 Java 21 / Spring Boot 3.3.5 Maven 多模块工程：

| 模块 | 已实现职责 |
|---|---|
| `benefit-contract` | Java 契约、OpenAPI、AsyncAPI、稳定错误码和事件 envelope |
| `benefit-domain` | 订单、item、operation、remediation 状态机及路由规则，无 Spring/JDBC 依赖 |
| `benefit-application` | 幂等受理、库存预占、异步履约、查询确认、fallback、补发和冲正用例 |
| `benefit-adapters` | JDBC/Flyway、Kafka Inbox/Outbox、渠道 SPI、中心码池/实物、JWT tenant |
| `benefit-server` | 外部 API、内部 remediation、回调、管理接口、worker 和可观测性 |

权威契约：

- `benefit-contract/src/main/resources/openapi/benefit-center-v1.yaml`
- `benefit-contract/src/main/resources/asyncapi/benefit-center-v1.yaml`

主要公开/内部能力包括：

- `POST /openapi/v1/award-orders`：创建或幂等重放权益订单。
- 订单号和业务来源双路径查询。
- `POST /internal/v1/remediations`：受理、查询和执行补发/冲正。
- 渠道回调入口：验签、时间窗、nonce 和 inbox 防重放。
- tenant、SKU、route、库存调整管理 API；写操作受权限和审计约束。

### 2.2 幂等与一致性

- `Idempotency-Key` 必须等于 `sourceRequestId`；请求使用长度前缀规范化字段计算 SHA-256，避免分隔符碰撞。
- `(tenant, sourceSystem, sourceRequestId)` 唯一；相同键同 payload 返回原订单，不同 payload 返回稳定冲突错误。
- 幂等重放先查历史订单，再读取当前目录，因此 SKU/route 后续下线不破坏历史重放。
- 一个本地事务写入 order/items、库存 reservation、首个 operation 和 outbox。
- 外部渠道调用不持有数据库事务；多实例 worker 使用 lease owner、lease until 和 version CAS。
- worker 崩溃后的过期 `DISPATCHING/QUERYING` 可恢复；旧 lease owner 不能覆盖新状态。
- Outbox 对真实序列化 payload 计算 hash；相同 eventId 的冲突 payload 会被拒绝。
- Inbox 使用 `messageId + payloadHash` 区分安全重放与冲突消息。

### 2.3 原子 item、组合奖品和库存

- 券、码、实物等原子 item 强制 `quantity=1`；多份奖励拆为稳定的多个 `clientItemId`。
- 单 intent 最多 20 个 item；每个 item 独立终结，订单聚合为成功、失败或部分成功。
- 所有发放先预占 `CENTER_QUOTA`。
- 中心持有的码/券/实物同时预占 `CENTER_STOCK`。
- 渠道持有库存时，`CHANNEL_SHADOW` 只做预判和告警，不参与业务扣减或归还。
- 渠道 route 配置 `EAGER` 且存在中心库存 fallback 时，接单即预占兜底库存；渠道返回 `UNKNOWN` 时继续持有，确认渠道成功后释放。
- 库存 balance 使用条件更新，所有 reserve/commit/release/return/admin-adjust 均追加 `bc_inventory_ledger`。
- 管理库存调整带 `(tenant, adminRequestId)` 幂等约束，不与正常 quota/stock 双分录冲突。

### 2.4 渠道履约与修复

- `ChannelAdapter` 收口 `issue/query/reverse/capabilities`；route 与 adapter 注册表在管理写入时校验。
- 通用 HTTP adapter 配置连接/读取超时；超时和断连映射为 `UNKNOWN`，HTTP 429 映射为可重试。
- 真实 HTTP adapter 需要 `BENEFIT_REAL_CHANNEL_ENABLED=true` 与 adapter 自身开关同时满足，默认关闭。
- 只有渠道明确返回 `NOT_ISSUED` 才允许切换已配置的等价 fallback。
- `UNKNOWN` 始终查询同一个 operationNo，不创建新 issue operation。
- `REISSUE` 只接受 item 与原 operation 均为明确最终失败；执行前再次检查，防止审批后状态漂移。
- `REVERSE` 只接受明确成功，并固定使用原发放 route，即使该 route 后续被禁用也不会误切其它渠道。
- ISSUE、REVERSAL 和库存变化只追加分录，不修改历史事实。

### 2.5 安全、高可用和分片接缝

- 生产 tenant 只能来自验签 JWT 的显式 audience 映射；未配置映射时不信任任意 `tenant_id` claim。
- header tenant 仅在显式开发模式下可用，生产用于与认证 tenant 做一致性检查。
- 管理 API operator 默认取认证 principal；开发 header 仅在开发模式回退。
- worker、Kafka consumer、Outbox relay、真实渠道和自动 remediation 均默认关闭。
- 关键扫描按 tenant 和状态建立索引；业务唯一键以 tenant 开头，ID 由应用生成。
- 订单固化 `home_cell` 和 `routing_key`，并提供 `ShardRouter`/single-cell 实现；当前没有宣称已物理分库分表。

### 2.6 数据库、部署与运维

- 提供 Flyway V1–V8：核心表、索引、worker lease、inbox 恢复、分片接缝、退避、remediation hash 和库存管理审计。
- 提供非 root Dockerfile、本地 MySQL/Kafka compose、Prometheus 规则和 GitHub Actions 基础流水线。
- 提供架构、渠道接入、数据保留、运行手册和生产发布门禁文档。

## 3. Drools 活动平台接入

活动平台保持“决策生产者”定位，不直接承担中台库存和渠道状态：

- 活动版本新增 `AwardBinding`，静态绑定决策来源与权益 SKU。
- 新增 `LEGACY`、`SHADOW`、`CENTER` 三种交付模式，可按活动版本灰度和回切。
- 触发时在服务端重新执行权威折扣/赠品决策，不信任调用方上传的金额或赠品事实。
- 多份赠品拆为多个 quantity=1 的原子 item，最大 20 个。
- 新建独立 `activity_award_intent_outbox`，不复用 legacy `GrantEvent`。
- relay 使用 lease/CAS；过期 `SENDING` 可恢复，HTTP 重试保持同一个 `sourceRequestId`。
- 写接口继续受活动平台 JWT 权限和 tenant 隔离保护。
- 提供 MySQL expand-only 连接器脚本和接入文档。

## 4. 自动对账平台接入

- 新增权益 ODS consumer，按 `eventId + payloadHash` 幂等落地 expected/internal/provider 事实。
- consumer 校验 schema major、envelope eventType 与 factType 的一致性。
- 现金新增 `BENEFIT_CASH_3WAY` 场景定义与 seed，但因通用 DB reader 尚未完整 tenant/window 化而默认 disabled。
- 非现金新增 `EntitlementObservation` 与分类器，按 issueId、SKU、数量、状态和 providerRef 对账，不伪造币种或零金额。
- remediation 采用“建议 → 审批 → command outbox → 中台再次安全校验 → result 单调收敛”。
- command outbox 使用 lease/CAS；result consumer 忽略审计性质的 `REMEDIATION_DISPATCHED`，只用 `REMEDIATION_RESULT` 收敛最终状态。
- ODS consumer、command relay 和 result consumer 均默认关闭。

## 5. 验证证据

2026-08-21 在本地执行：

| 验证 | 结果 |
|---|---|
| `benefit-center: mvn -q verify` | 通过；8 个报告、16 个测试，0 failure/error |
| `drools-demo: mvn -q test` | 通过；131 个报告、524 个测试，0 failure/error，3 skipped |
| `recon-platform: mvn -q test` | 通过；91 个报告、303 个测试，0 failure/error，1 skipped |
| OpenAPI/AsyncAPI YAML 解析 | 通过 |
| OpenAPI/AsyncAPI 内部 `$ref` 解析 | 通过；40/15 个引用 |
| `docker compose ... config --quiet` | 通过 |
| MySQL 8.4 迁移验证 | V1–V8 全部执行成功，共 16 张表 |
| Drools MySQL 连接器 SQL | 在 MySQL 8.4 连续执行两次成功；表、字段、索引保持幂等 |
| `git diff --check` | Drools 与 recon 的 tracked diff 通过；新建 `benefit-center` 由编译、测试和契约验证覆盖 |
| Docker 镜像构建 | 基础镜像已解析；容器内 Maven 依赖预下载因外部仓库长时间无进展而主动终止，发布流水线必须重新执行并取得成功证据 |

重点自动化场景覆盖：

- 同 payload 幂等重放、不同 payload 冲突、目录下线后的历史重放。
- 组合 item 部分成功和原子赠品拆分。
- quota/stock 预占、明确失败释放、成功提交、冲正归还。
- EAGER 兜底库存跨 `UNKNOWN` 持有并在渠道确认成功后释放。
- UNKNOWN 原 operation 查询、过期 dispatch lease 恢复、旧 worker CAS 防覆盖。
- 明确 NOT_ISSUED fallback、补发状态漂移二次检查、原 route 冲正。
- Outbox/Inbox 重放冲突、Drools relay 崩溃恢复、对账 remediation 结果幂等。

## 6. 未完成的生产门禁

以下事项需要公司环境、业务负责人或外部渠道输入，不能由本次本地实现替代：

1. 首批真实红包、券、码、实物渠道的正式协议、sandbox、幂等/query/reverse/callback 契约测试和错误码映射。
2. 现金资金账户、会计科目、税务、反洗钱、额度、隐私、密钥托管和四眼审批矩阵。
3. 依据真实峰值进行容量/混沌/限流测试，并批准 SLA、SLO、RTO、RPO、备份恢复与积压收敛指标。
4. 生产 Kafka 的 ACL、Schema Registry/兼容策略、分区数、保留期、DLQ/redrive 和跨机房演练。
5. `BENEFIT_CASH_3WAY` 上线前完成 tenant-aware `RunKey/SourceReadContext`、账期窗口谓词和三方样本守恒。
6. 非现金 `ENTITLEMENT_FULFILLMENT` 的完整批作业、运营报表和处置 UI。
7. fallback 等价性审批及自动 REISSUE/REVERSE 的 tenant/SKU/金额/次数白名单。
8. 达到容量触发条件后，再实施真实 homeCell 路由、物理分片、逐 tenant 搬迁、双写校验和回切演练。

生产门禁、开关和切流顺序以 `benefit-center/docs/release-gates.md` 为准。

## 7. 推荐后续顺序

1. 选择一个非现金、低风险、支持 query 的 sandbox 渠道，完成 Adapter 合同测试。
2. 以测试 tenant 部署所有组件但保持真实渠道和 worker 关闭，创建 tenant/SKU/route/库存数据。
3. Drools 先切 `SHADOW` 对拍，再对单 tenant 切 `CENTER`；验证停止新流量和回切演练。
4. 对账 ODS 先只读观测，人工审批 remediation；稳定后再讨论白名单自动化。
5. 完成压测和恢复演练后逐步扩大租户，不因“未来可能分片”提前引入跨分片事务。

## 8. 关键文档

- `benefit-center/README.md`
- `benefit-center/docs/architecture.md`
- `benefit-center/docs/channel-onboarding.md`
- `benefit-center/docs/runbook.md`
- `benefit-center/docs/release-gates.md`
- `drools-demo/docs/benefit-center-connector.md`
- `recon-platform/docs/benefit-center-integration.md`
