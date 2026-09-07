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

SKU DRAFT ─ submit-for-approval + start outbox ─► workflow-platform
    ▲                                               │ action.requested
    └─ action.applied + 状态 CAS + 模板事件 outbox ─┘
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

## SKU 首次上线审批

- `POST /admin/v1/skus/{skuId}:submit-for-approval` 在同一事务完成 `DRAFT → PENDING_APPROVAL` 与 `workflow.command.start.v1` outbox；幂等周期键为 `tenantId|skuId|提交前版本`。
- PENDING 模板禁止编辑或由客户端直接改成 ACTIVE。workflow-platform 只请求 `SKU_GO_LIVE_APPROVE` / `SKU_GO_LIVE_REJECT`，权益中台仍以当前 tenant、sku、version 做最终 CAS 裁决。
- 业务落地后，权益中台发送 `workflow.action.applied.v1`；只有 workflow 收到 `benefitSkuGoLiveApplied` 回执，流程才从 `PENDING_BUSINESS` 结束。重复 requested/actionId 由 inbox 与命令幂等表收敛。
- APPROVE 使模板进入 ACTIVE、失效本机 L1 并发布 `SKU_TEMPLATE_CHANGED`；REJECT 回到 DRAFT。任何状态或版本已变化的请求返回业务拒绝回执，不覆盖新版本。

## WalletEntry 命令边界

- freeze/redeem/refund 以 `entryId + action + Idempotency-Key` 绑定规范化请求哈希；首次 `{entryId,status,version}` 持久化后永久用于同键重放。
- 状态与 version 使用数据库 CAS。REDEEM/REFUND 的现金分录和履约事实与状态改变同事务写入；金额、币种、SKU 世代均取发放时快照，不回读当前模板。
- 冻结只改变使用资格，不扣减或归还现金；当前没有 unfreeze。过期检查拒绝命令，但不会借机把资产状态隐式改成 EXPIRED。
- 钱包命令发布 `eventType=FULFILLMENT_WALLET`、payload 为 `WalletFulfillmentEvent`。它与发奖履约的 EXPECTED/INTERNAL/PROVIDER payload 不是同一结构；下游必须按 AsyncAPI 的事件判别字段分流，不能假定所有 `benefit.fulfillment-event.v1` 消息都含 `factType`、`benefitType`。

## 分库分表接缝

- 所有业务主键、唯一键和高频索引以 `tenant_id` 开头。
- `routing_key` 已固化到订单；应用 ID 不依赖数据库自增。
- 一个订单、其 items、operations、ledger 和 remediation 必须同分片。
- `ShardRouter` 当前是单分片实现；未来路由应先确定 tenant homeCell，再以 `tenant + routingKey` 选择物理库。
- 禁止跨分片同步事务、全局自增 ID 和业务路径全表扫描。

建议触发拆分的证据包括：单库写入或存储达到审批水位、热点 SKU 行锁无法通过库存分桶缓解、worker 与 API 连接预算冲突、归档后仍无法满足查询 SLA。真正拆分前需要完成双写校验、按 tenant 搬迁、增量追平、回切和跨分片对账演练。

## 明确不确定性

外部渠道最终一致性无法消除。没有稳定幂等号与查询接口的渠道不得自动重试 UNKNOWN；没有反向接口或已核销/发货的权益不得自动冲正。
