# 候选方案 B：独立模块化单体 + 异步履约 + Outbox/Inbox

> 架构意图：新建独立 `benefit-center` Maven 多模块项目；单一部署单元内保持领域、应用、持久化、渠道、消息和 API 边界。API 事务只接单和占中心库存，外部渠道由带 lease 的 worker 异步执行。活动和 recon 通过版本化契约接入。

## 1. 架构与模块职责

```text
Drools / OpenAPI / Kafka AwardIntent
                 │
          benefit-center-server
                 │
     benefit-contract ─ benefit-application
                              │
                         benefit-domain
                              │
          benefit-adapters (JDBC/Kafka/channel/security)
                 │
         MySQL + Kafka + channel APIs
                 │ events/remediation
            recon-platform ODS
```

- `benefit-contract`：OpenAPI/MQ DTO、schema version、错误码；无持久化实体。
- `benefit-domain`：order/item/operation/remediation/库存状态与不变量，纯 Java。
- `benefit-application`：`AcceptAwardIntentUseCase`、`FulfillmentWorker`、`RemediationUseCase`、查询和聚合；只依赖端口。
- `benefit-adapters`：JdbcTemplate/Flyway、Kafka outbox/inbox、JWT tenant、渠道 SPI/实现、兑换码 KMS、物理 Adapter。
- `benefit-server`：Spring Boot 组合根、controllers、schedulers、metrics、health。

一期是一套可独立部署的 API+worker；可用 profile 分角色部署相同 artifact，未来再拆进程而不改领域契约。

## 2. 核心流程

1. 入口按 `(tenant,sourceSystem,sourceRequestId)` 和 payload hash 幂等。
2. 一个本地事务写 order/items、CAS 预占中心配额/兜底库存、operation task 与 outbox。
3. Worker CAS 获取 operation lease，按 route 调渠道。外部 I/O 不持数据库事务。
4. 渠道成功后短事务提交库存、写不可变 ISSUE/receipt、更新 item/order、写 outbox。
5. 明确 OOS 走中心 fallback；未知结果只查询原 operation。
6. recon ODS 消费 expected/ledger/receipt，生成 remediation command；中台 inbox 幂等执行并回传 result。

## 3. 数据一致性策略

- 本地强一致：订单/奖项/中心库存/任务/outbox。
- 跨渠道最终一致：稳定 operation id + 渠道幂等/查询 + 回调 inbox + 状态 CAS。
- 中心库存用条件 UPDATE + version；渠道库存是 shadow snapshot，不能作为最终扣减证据。
- 账务和库存全部追加 ledger，状态行只作当前视图。
- 不使用 XA/Seata；失败靠 saga 状态和明确补偿收敛。

## 4. 改动范围

- 新建完整 `benefit-center/` 项目及 deploy/contracts/docs。
- `drools-demo` 只增加版本化 AwardBinding、服务器端 AwardIntent assembler/outbox/connector 和灰度配置；不改 decision 算法和 legacy grant。
- `recon-platform` 增 tenant-aware ODS、现金 `BENEFIT_CASH_3WAY`、非金额 `ENTITLEMENT_FULFILLMENT` 旁路模型，以及通用 remediation suggestion/command outbox；保留 `MARKETING_3WAY`。

## 5. 优势

- 满足独立服务、开放 API、多租户、Adapter 和一期可落地要求。
- 单库本地事务把最危险的订单/库存/outbox 保持原子，测试难度明显低于全微服务 saga。
- 模块边界和 operation/tenant 路由键为未来按角色拆分、Cell 和分片预留。
- legacy 活动发放可保持完全不动，灰度和回滚清晰。

## 6. 风险评审

### 兼容性

- 新旧链路并存时最大的风险是同一业务同时调用 legacy confirm 和 AwardIntent。必须按 tenant/source/activity 只选一个 authority，并用 source request id 监控双入账。
- activity 现有折扣不是权益 SKU；必须以版本化 binding fail-closed，禁止按 `benefitForm` 猜类型。

### 事务、并发、幂等

- Worker lease 过期与慢渠道响应可能并发；渠道 request id 必须稳定，短事务用 expected status CAS，迟到响应不能覆盖新终态。
- 预占中心 fallback 会提高库存占用时间；一期建议 eager reserve 保证兜底，需监控 reservation age 并有超时释放。
- MySQL 热点库存行可能成为瓶颈；一期先做分桶 account 或按 SKU 实测，不能提前声称无限横扩。

### 性能与可用性

- 单逻辑库是容量上限；API 与 worker 共库可能争抢连接。用角色 profile、独立线程池/连接池预算、短事务和 backlog 限制。
- Kafka 不可用不影响已接单本地状态，但会积压 outbox；必须有 outbox age/readiness 告警和 backpressure。

### 安全

- 兑换码密文/KMS、渠道密钥、cash 管理权限是新攻击面。严禁 payload 全量日志，动态金额必须由 SKU guard 校验。
- callback 必须签名、时间窗、防重放；tenant 由 channel route/account 映射，不信任 callback body。

### 数据迁移、灰度与回滚

- 不迁存量把风险降到最低，但短期有两个客服/对账来源。
- 关闭 CENTER 只能阻止新请求；已 ACCEPTED 的中台订单必须继续收敛。若把它们重新发给 legacy，会双发。
- DB migration 必须 expand-only；回滚 jar 不删除表/列，未识别的新状态只由中台新版本处理。

## 7. 扩展性与实施成本

- 初期成本：中高，但一次形成正确边界。
- 维护性：高；业务不依赖 Kafka/JDBC/渠道类。
- Cell：按 tenantId/homeCell 路由，已有订单固定 homeCell；未来复制同一部署单元到多个 Cell。
- 分库分表：聚合和唯一键包含 tenant；没有跨租户业务事务；大表可按 tenant/hash + time 分片。
- 渠道：增加 Adapter、能力描述和契约测试，无需修改 order 状态机。

## 8. 测试设计

- domain property/state-machine tests；库存 CAS 和 ledger 守恒测试。
- H2 只做快速集成，MySQL 8 Testcontainers 验证唯一键、`SKIP LOCKED`/lease、死锁和隔离级别。
- WireMock/MockWebServer 渠道契约：success/OOS/timeout/unknown/callback race/reverse unsupported。
- Kafka 容器：重复、乱序、replay、consumer rebalance、outbox publish 后置态失败。
- activity 契约与 legacy regression；recon 三方和 remediation E2E。
- 灰度演练：shadow、center、kill worker、broker outage、DB failover、回滚新流量。

## 9. 适用结论

最平衡的一期方案。弱点是一期仍有单库/单逻辑服务容量上限和 eventual consistency 运维复杂度；它不是“微服务少就简单”，而是把复杂度集中在可验证的 operation/outbox 状态机里。
