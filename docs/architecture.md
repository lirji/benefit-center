# 架构与状态边界

## 主链路

```text
Drools / 业务系统
        │ AwardIntent REST/Kafka
        ▼
受理事务：幂等键 ─ 用户限额占额 ─ order/items ─ 库存预占 ─ operation ─ outbox
        │
        ▼ lease + CAS
履约 worker ──事务外调用──► ChannelAdapter
        │                       │
        └────短事务收敛◄────────┘
              │ ledger + WalletEntry + facts
              ▼
         recon-platform ODS
              │ 审批后的 command
              └──────────────► remediation inbox
```

## 库存所有权

| ownerType | 含义 | 业务事务可写 |
|---|---|---|
| `CENTER_QUOTA` | 公司预算或总发放配额 | reserve/commit/release/return |
| `CENTER_STOCK` | 中台真实拥有的码、实物或可兜底库存 | reserve/commit/release/return |
| `CHANNEL_SHADOW` | 渠道权威库存的最近快照 | 否，仅快照同步任务校准 |

所有余额更新使用带余额条件的单 SQL；库存流水不可变。渠道库存快照只能辅助路由和告警，不能证明某次渠道扣减成功。

## 模板世代、券包与缓存

- `bc_benefit_sku` 保存当前模板，`bc_sku_template_version` 保存每个不可变世代；订单项与券包资产都固化 `sku_version`。
- L1 Caffeine 用当前世代短 TTL（默认 20 秒）加 `tenant + sku + version` 不可变值缓存；本实例在模板事务提交后立即失效，其他实例最迟随短 TTL 收敛，`SKU_TEMPLATE_CHANGED` 同时供下游更新投影。
- L2 Redis 的用户限额键不保存账本，只缓存 `reserved + issued` 预检值；缓存 miss 或 Redis 故障回源数据库，最终由 `bc_user_limit_counter` 条件更新裁决。
- 履约成功与 `WalletEntry`、现金余额分录、订单状态和 outbox 在同一本地事务收敛；冲正追加相反现金分录，不原地改写历史流水。

## 分库分表接缝

- 所有业务主键、唯一键和高频索引以 `tenant_id` 开头。
- `routing_key` 已固化到订单；应用 ID 不依赖数据库自增。
- 一个订单、其 items、operations、ledger 和 remediation 必须同分片。
- `ShardRouter` 当前是单分片实现；未来路由应先确定 tenant homeCell，再以 `tenant + routingKey` 选择物理库。
- 禁止跨分片同步事务、全局自增 ID 和业务路径全表扫描。

建议触发拆分的证据包括：单库写入或存储达到审批水位、热点 SKU 行锁无法通过库存分桶缓解、worker 与 API 连接预算冲突、归档后仍无法满足查询 SLA。真正拆分前需要完成双写校验、按 tenant 搬迁、增量追平、回切和跨分片对账演练。

## 明确不确定性

外部渠道最终一致性无法消除。没有稳定幂等号与查询接口的渠道不得自动重试 UNKNOWN；没有反向接口或已核销/发货的权益不得自动冲正。
