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

## 灰度与回滚

- 回滚只切新 sourceRequest：Drools `CENTER → LEGACY` 后，benefit 已接受订单仍由 worker 收敛。
- 单渠道故障优先将 route disabled；不要全局停止 outbox。
- 数据只前滚修复，不 drop ledger/inbox/outbox，不重写历史 operation。
- 任一双发、负库存、跨租户或账不守恒立即停止扩档。
