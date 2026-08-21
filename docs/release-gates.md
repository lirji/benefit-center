# 一期发布与生产切流门禁

当前代码是可构建、可测试的一期工程基线，不等于已经取得生产上线许可。以下门禁必须有负责人、证据链接和审批时间；任一 P0 未通过时，相关开关保持关闭。

## 已由工程基线提供

- REST/Kafka 版本化契约、请求哈希幂等、Inbox/Outbox 至少一次交付。
- item 级 `BEST_EFFORT` 组合履约、部分成功、受控补发与冲正。
- operation 稳定请求号、lease/CAS 多实例抢占；外部 I/O 不持有数据库事务。
- timeout/断连进入 `UNKNOWN`，查询原 operation 前禁止 fallback/补发。
- `CENTER_QUOTA`、`CENTER_STOCK` 与只读 `CHANNEL_SHADOW` 所有权隔离。
- JWT tenant、管理 API 审计、Prometheus 指标和故障 runbook。
- `tenant_id + routing_key + home_cell` 分片接缝；一期仍为单 Cell/单逻辑库。

## P0 上线门禁

| 门禁 | 必需证据 | 未通过动作 |
|---|---|---|
| 资金与合规 | 现金渠道、额度、会计科目、税务/反洗钱、审计与数据保留审批 | CASH route 禁用 |
| 渠道契约 | 正式文档、sandbox 报告、稳定幂等号、query/reverse/callback 与错误码映射 | 对应 route `enabled=false` |
| fallback 等价性 | 主/备 SKU 的面额、使用范围、有效期和用户体验等价审批 | fallback 禁用 |
| remediation 白名单 | REISSUE/REVERSE 的对象、金额/次数阈值、四眼审批和人工兜底 | 自动中继关闭 |
| 身份与密钥 | JWT issuer/audience/tenant 显式白名单映射、最小权限、Vault/KMS、轮换演练 | 禁止生产流量 |
| 容量与恢复 | 峰值/突发压测、DB/Kafka/渠道限流、SLA、RTO/RPO、备份恢复演练 | 不得扩档 |
| 对账闭环 | 现金三方样本守恒、非现金存在性/数量/SKU/状态样本、结果回传 | 不得自动纠错 |
| 灰度止损 | LEGACY→SHADOW→CENTER 租户名单、停止新流量、积压收敛和回切演练 | 保持 LEGACY |

## 上线顺序

1. 先执行 expand-only migration，以所有真实 route、worker、outbox、consumer 和 remediation 关闭的状态部署。
2. 配置并复核 tenant、SKU、route、中心配额/库存；每个真实 Adapter 独立完成契约测试。验收前保持全局 `BENEFIT_REAL_CHANNEL_ENABLED=false`。
3. 开启 outbox 与 worker，仅放测试 tenant；验证幂等重放、UNKNOWN 查询、部分成功、补发审批和账实一致。
4. Drools 使用 `SHADOW` 组装意图但不发送，对比服务器端重算结果；通过后按 tenant 切 `CENTER`。
5. 对账 ODS consumer 先开只读观测，现金 `BENEFIT_CASH_3WAY` 在租户/账期过滤能力上线前保持 disabled；自动 remediation 最后开启。

## 分库分表触发条件

一期不做物理拆分。只有在归档、索引、库存分桶、读写隔离和 worker 限流仍无法满足经审批的容量指标后，才启动按 tenant homeCell、再按 `tenant + routingKey` 分片的迁移。迁移必须具备双写校验、增量追平、逐 tenant 搬迁、回切和跨分片对账演练，禁止跨分片业务事务。
