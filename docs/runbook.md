# Benefit Center Runbook

## 安全启动顺序

1. 部署 expand-only Flyway migration，确认 `/actuator/health/readiness` 为 `UP`。
2. 以所有真实 route disabled、worker/outbox/consumer disabled 启动 API。
3. 校验 tenant homeCell、SKU、库存账户和 route；再启 outbox、worker、消息 consumer。
4. Drools 先 `SHADOW` 对比，测试 tenant 才切 `CENTER`；自动 remediation 保持关闭。

生产不得设置 `BENEFIT_SECURITY_DEV_MODE=true`。JWT audience 必须能唯一映射 tenant，header 只允许与 token tenant 一致。

## 常见故障

### UNKNOWN 增长

- 查看 `benefit_operation_unknown` 和渠道错误率。
- 保持该 operation 的稳定 requestNo，确认 route Adapter 的 query 能力。
- worker 在 `DISPATCHING/QUERYING` 中崩溃时，过期 lease 会把 operation 恢复为 `UNKNOWN`；接管者只查询同一 operation，item 不会重新 issue。
- 不得手工改成失败后补发，也不得切 fallback；无查询能力时转人工向渠道核实。
- 可禁用故障 route 阻止新流量，但 worker 仍应收敛已接订单。

### outbox 积压或 DEAD

- 先确认 Kafka、ACL、topic 和序列化错误，禁止绕过 outbox 直发。
- 修复后先重放单个 event，验证 consumer inbox 命中，再小批量 redrive。
- 当前版本不提供“清空”接口；DEAD redrive 必须走受审计的 DBA 变更，将精确 `(tenant_id,event_id)` 从 `DEAD` 前滚到 `FAILED` 并设置 `next_attempt_at=CURRENT_TIMESTAMP`。不得删除 outbox/DLQ 或盲目回退 offset。

### 库存异常

- `benefit_inventory_invalid > 0` 为 P0，立即停止新受理并保留 worker/outbox 现场。
- 核对 inventory ledger、reservation operation 和 award ledger。库存修正只能通过带 requestId 的 admin adjustment 追加流水。
- 禁止直接返还 `CHANNEL_SHADOW`。

### 部分成功

- 按 item 查询失败码和原 operation；已成功 item 不回滚。
- 仅明确 `FAILED_FINAL` 且有审批引用时允许 REISSUE；UNKNOWN 先 query。
- remediation 使用稳定 externalCommandId；重放不同 payload 会返回幂等冲突。

### SKU 审批停在 PENDING_APPROVAL / PENDING_BUSINESS

- 先按 `tenant + skuId + skuVersion` 查模板、start outbox、workflow 实例和 actionId，不要直接把 SKU 改成 ACTIVE。
- start outbox 积压时检查 `BENEFIT_OUTBOX_ENABLED`、三个 workflow topic、Kafka ACL 和 `benefit-center` HMAC key。action 收不到时检查 `WORKFLOW_KAFKA_ENABLED`、consumer group `benefit-wf-sku-golive`，以及是否配置了 `workflow-server` 的验签 key。
- workflow 侧办理返回 202 只表示决定已受理。权益中台 CAS 成功并发出 `workflow.action.applied.v1` 后，workflow 才能收到 `benefitSkuGoLiveApplied` 并结束流程。
- 若业务返回 `SKU_APPROVAL_STATE_CHANGED` 或版本冲突，保留原流程和 inbox 审计；重新提交必须基于当前 DRAFT 新版本生成新的幂等周期，禁止复用旧 actionId 强行覆盖。

### WalletEntry 核销或退款冲突

- 先用 `GET /admin/v1/wallets/{subjectRef}/entries` 核对 entryId、status、version 与发放时 `skuVersion`；订单详情只用于关联，不是资产状态真相源。
- 同一 `Idempotency-Key` 重放应返回首次 202 结果，即使资产后来继续迁移；同键异载荷必须保持 409。不要因为返回旧版本就改写 `bc_command_idempotency.result_payload`。
- `WALLET_VERSION_CONFLICT` 先判断是否为并发命令；`WALLET_ILLEGAL_TRANSITION` / `WALLET_ALREADY_USED` 表示当前状态不允许该动作。当前只允许 `UNUSED→FROZEN→USED`、`UNUSED→USED`、`USED→REVERSED`，没有 unfreeze。
- 现金 redeem/refund 对账以 `bc_wallet_balance_ledger` 的 `REDEEM` / `REFUND` 和履约 outbox 为准；禁止调用 remediation 的 `REVERSAL` 来替代钱包退款，也禁止按当前 SKU 面额重算。
- 钱包 outbox 的 `FULFILLMENT_WALLET` 与发奖履约事实结构不同。现有 recon `BenefitOdsConsumer` 尚不兼容该 payload；启用 relay 前应隔离事件路由或先升级 consumer，并监控 retry/DLT，不能把“已发布”视为“已纳入对账”。

## 灰度与回滚

- 回滚只切新 sourceRequest：Drools `CENTER → LEGACY` 后，benefit 已接受订单仍由 worker 收敛。
- 单渠道故障优先将 route disabled；不要全局停止 outbox。
- 数据只前滚修复，不 drop ledger/inbox/outbox，不重写历史 operation。
- 任一双发、负库存、跨租户或账不守恒立即停止扩档。
- workflow 回切先停 `WORKFLOW_KAFKA_ENABLED`，再停 `BENEFIT_OUTBOX_ENABLED`；这会同时影响其他 outbox，执行前必须确认普通履约事实允许短时积压。在途 PENDING 审批保留并人工收敛，不批量改状态。
