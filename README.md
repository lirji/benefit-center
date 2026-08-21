# Enterprise Benefit Center

公司级权益发放中台的一期模块化单体实现，统一承载现金红包、优惠券、服务券、兑换码、实物及组合奖品。系统以 item 为最小履约单元，组合默认 `BEST_EFFORT`，因此可部分成功并对明确失败项受控补发。

架构说明、渠道接入、运维处置和生产门禁分别见 [`docs/architecture.md`](docs/architecture.md)、[`docs/channel-onboarding.md`](docs/channel-onboarding.md)、[`docs/runbook.md`](docs/runbook.md) 与 [`docs/release-gates.md`](docs/release-gates.md)。

## 模块

- `benefit-contract`：OpenAPI、AsyncAPI 与跨系统 Java 契约。
- `benefit-domain`：无 Spring/JDBC 依赖的订单、子项、operation、库存和补发状态机。
- `benefit-application`：幂等受理、库存预占、异步履约、UNKNOWN 查询确认、fallback 与 remediation 用例。
- `benefit-adapters`：JDBC/Flyway、Kafka inbox/outbox、中心码池/实物、签名 HTTP 参考渠道、JWT tenant。
- `benefit-server`：公开 API、内部接口、worker、Actuator/Prometheus。

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
docker compose -f deploy/docker-compose.yml up --build
```

本地 compose 显式开启 header 租户开发模式；生产默认是 JWT 模式，所有 worker、消息消费、真实 HTTP 渠道和自动 remediation 均默认关闭。首次调用前需要通过 admin API 创建 tenant 配置、SKU、route 和中心库存。

公开入口为 `POST /openapi/v1/award-orders`，且 `Idempotency-Key` 必须等于 `sourceRequestId`。完整契约见 `benefit-contract/src/main/resources/openapi/benefit-center-v1.yaml`。

## 当前发布边界

中心兑换码与中心实物适配器可用于参考闭环；通用 HTTP Adapter 仍是签名协议参考实现。任何真实渠道必须完成 `docs/channel-onboarding.md` 的 sandbox 契约测试后才能启用。真正多 Cell、租户搬迁和物理分库分表属于容量触发后的演进项，当前仅保留 `homeCell/routingKey/ShardRouter` 接缝。
