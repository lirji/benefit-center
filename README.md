# Enterprise Benefit Center

公司级权益发放中台的一期模块化单体实现，统一承载现金红包、优惠券、服务券、兑换码、实物及组合奖品。系统以 item 为最小履约单元，组合默认 `BEST_EFFORT`，因此可部分成功并对明确失败项受控补发。

架构说明、渠道接入、运维处置和生产门禁分别见 [`docs/architecture.md`](docs/architecture.md)、[`docs/channel-onboarding.md`](docs/channel-onboarding.md)、[`docs/runbook.md`](docs/runbook.md) 与 [`docs/release-gates.md`](docs/release-gates.md)。

## 模块

- `benefit-contract`：OpenAPI、AsyncAPI 与跨系统 Java 契约。
- `benefit-domain`：无 Spring/JDBC 依赖的订单、子项、operation、库存和补发状态机。
- `benefit-application`：幂等受理、库存预占、异步履约、UNKNOWN 查询确认、fallback 与 remediation 用例。
- `benefit-adapters`：JDBC/Flyway、Kafka inbox/outbox、中心码池/实物、签名 HTTP 参考渠道、JWT tenant。
- `benefit-server`：公开 API、内部接口、worker、Actuator/Prometheus。
- `benefit-console`：独立运营台（React + Vite），经 nginx 同源反代 `/openapi` `/admin` `/internal`。

## 正确性边界

1. 受理事务原子写入 order/items、中心库存 reservation、operation 和 outbox。
2. 外部渠道调用不持有数据库事务；worker 用 lease + version CAS 抢占。
3. timeout/断连进入 `UNKNOWN`，只查询同一 operation，禁止直接 fallback 或补发。
4. 只有渠道明确 `NOT_ISSUED` 时才评估等价 fallback。
5. 成功/冲正只追加 ledger；`CHANNEL_SHADOW` 不由业务事务扣减或归还。
6. 补发要求原 operation 明确 `FAILED_FINAL`；冲正要求原 operation 和 item 均已成功。
7. 非现金事件没有 currency/amount，对账使用 SKU、数量、状态和 providerRef。

## 构建与快速运行

```bash
mvn verify
bash deploy/bootstrap-dev-infra.sh
bash deploy/compose.sh up --build
```

本地运行统一使用同级 `/Users/liruijun/personal/LLM/dev-infra` 的 MySQL 8.4 与 Kafka 3.8，不再创建项目私有 MySQL/Redpanda。首次运行先从 `deploy/.env.example` 创建未提交的 `deploy/.env`，再执行幂等初始化脚本；完整资源清单、连接方式和回滚步骤见 [`docs/dev-infra.md`](docs/dev-infra.md)。

同级 `auth-platform` 工作区会自动加载中央门户端口 `BENEFIT_UI_PORT`（默认 `8083`）。独立 checkout 找不到该注册表时仍用 8083。浏览器入口是独立 console：`http://localhost:8083/`；门户探活打 console 的 `GET /healthz`（纯文本 `ok`，CORS `*`）。Java API 只绑定 `127.0.0.1:8183`，不再提供 HTML 落地页。统一门户 catalog 的本地入口是 `/login?returnTo=%2Fdashboard`。

本地前端开发：

```bash
cd benefit-console
corepack pnpm install
corepack pnpm dev    # Vite :5173，反代 API 到 localhost:8183
```

本地 compose 默认开启 header 租户开发模式。接入统一 Casdoor 时，先在 auth-platform 开通租户，再叠加 secure Compose：

```bash
# 需 Casdoor 已启动（auth-platform ./dev.sh up 或 docker compose）
BENEFIT_USER=benefit-e2e-admin PASSWORD='本地强口令' \
  bash ../auth-platform/deploy/benefit-platform-provision.sh
bash deploy/compose.sh --secure up -d --build
```

OIDC 组织固定为 `benefit-center`，派生 client_id `ragshared0client00000001-org-benefit-center`。JWT `aud` 经 `BENEFIT_AUDIENCE_TENANTS` 映射为业务租户；Casdoor `permissions`（`benefit.admin` / `benefit.award.read` / `benefit.award.write` / `benefit.remediate`）是授权真相源。未叠加 secure 时仍走本地免登录。

生产默认是 JWT 模式，所有 worker、消息消费、真实 HTTP 渠道和自动 remediation 均默认关闭。首次调用前需要通过 admin API 创建 tenant 配置、SKU、route 和中心库存。

公开入口为 `POST /openapi/v1/award-orders`，且 `Idempotency-Key` 必须等于 `sourceRequestId`。完整契约见 `benefit-contract/src/main/resources/openapi/benefit-center-v1.yaml`。

## Slice 1：模板与券包

- SKU 模板状态为 `DRAFT → PENDING_APPROVAL → ACTIVE ↔ PAUSED → RETIRED`；`enabled` 仅为 `status == ACTIVE` 的兼容派生字段。
- 模板每次修改生成不可变 `skuVersion` 快照，并通过 `benefit.sku-template.v1` 发布世代变更；已发 `WalletEntry` 始终绑定发放时版本。
- 客服查询为 `GET /admin/v1/wallets/{subjectRef}` 与 `GET /admin/v1/wallets/{subjectRef}/entries`，沿用 `benefit.admin`。

## Slice 4c：券包冻结、核销与退款

- `POST /openapi/v1/wallet-entries/{entryId}:freeze|redeem|refund` 沿用 `benefit.admin`，要求 `Idempotency-Key`，并在返回 202 前同步提交状态 CAS、现金流水和 outbox。
- 状态机仅允许 `UNUSED→FROZEN→USED`、`UNUSED→USED`、`USED→REVERSED`；本版本没有 unfreeze。过期资产采用“拒绝命令但不隐式改 EXPIRED”的策略。
- 核销金额、币种和 `skuVersion` 只取 `WalletEntry` 入账快照；`:refund` 追加 `REFUND`，不调用履约 Remediation 的 `reverseByItem`/`REVERSAL`。
- `bc_command_idempotency.result_payload` 保存首次同步结果，确保后续状态变化后旧键仍精确重放首次 `{entryId,status,version}`。
- 所有管理写接口要求 `Idempotency-Key`；同键同载荷重放首次成功，同键异载荷返回 RFC 9457 的 `409 IDEMPOTENCY_PAYLOAD_CONFLICT`。
- 用户日/总限额以 MySQL 条件更新和占额明细为真账；Redis 只做可回源预检，`UNKNOWN` 期间保持占额。

## Slice 4a：SKU 首次上线审批

- 提交接口为 `POST /admin/v1/skus/{skuId}:submit-for-approval`；DRAFT→PENDING 与
  `workflow.command.start.v1` outbox 同事务，消息 `source=benefit-center`。
- `workflow.kafka.enabled` 本地联调可开启；生产必须显式开启 consumer。topic 使用
  `workflow.command.start.v1`、`workflow.action.requested.v1`、`workflow.action.applied.v1`。
- workflow-platform 侧需登记 `WORKFLOW_KAFKA_SOURCE_TENANT_BINDINGS=benefit-center=<tenant>`。双方的
  `WORKFLOW_KAFKA_SOURCE_SIGNING_KEYS` 需同时包含 `benefit-center=<key>,workflow-server=<key>`，每把密钥解码后
  至少 32 字节：前者签 start/applied，后者签 requested；broker 仍需 SASL/TLS 与 topic ACL。
- 审批人必须由 Casdoor 分配 `BENEFIT_SKU_REVIEWER` 候选组。流程定义、任务与 ACK message 分别为
  `benefitSkuGoLive`、`skuGoLiveReview`、`benefitSkuGoLiveApplied`。
- 回滚时先停 benefit workflow consumer（`WORKFLOW_KAFKA_ENABLED=false`），再停 start relay
  （`BENEFIT_OUTBOX_ENABLED=false`）；在途 PENDING 由审批人在 workflow 驳回，不自动换幂等键重发。

### Cursor 字段表

| 对接项 | 值 |
| --- | --- |
| 提交接口 | `POST /admin/v1/skus/{skuId}:submit-for-approval` |
| 202 字段 | `skuId`, `status`, `version` |
| `SkuView` 新字段 | `approvalProcessDefinitionKey`, `approvalBusinessKey` |
| 错误码 | `SKU_NOT_DRAFT`, `SKU_VERSION_CONFLICT`, `SKU_APPROVAL_LOCKED`, `SKU_ILLEGAL_TRANSITION`, `SKU_SUBMIT_IN_FLIGHT` |
| scope | `benefit.admin` |
| BPMN | `benefitSkuGoLive` / `skuGoLiveReview` / `BENEFIT_SKU_REVIEWER` |
| ACK message | `benefitSkuGoLiveApplied` |
| action | `SKU_GO_LIVE_APPROVE` / `SKU_GO_LIVE_REJECT` |
| delegate | `skuGoLiveActionOutboxDelegate`（不复用 `rxReview`） |

## 跨系统键

- `tenantId`：来自 token owner，拒绝请求头覆盖已认证租户。
- `subjectRef`：用户稳定引用，需与 Casdoor `sub` 或营销 subject token 建立 crosswalk。
- `campaignId + definitionVersion`：营销定义版本身份。
- `benefitSkuId + skuVersion`：产品 SKU 与不可变模板世代。
- `sourceRequestId`：跨系统幂等钥匙；Award API 的 `Idempotency-Key` 必须与之相同。

## 当前发布边界

中心兑换码与中心实物适配器可用于参考闭环；通用 HTTP Adapter 仍是签名协议参考实现。任何真实渠道必须完成 `docs/channel-onboarding.md` 的 sandbox 契约测试后才能启用。真正多 Cell、租户搬迁和物理分库分表属于容量触发后的演进项，当前仅保留 `homeCell/routingKey/ShardRouter` 接缝。
