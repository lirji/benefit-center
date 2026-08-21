# 候选方案 D：Cell-first 多活与分片优先

> 架构意图：从第一天按 tenant 分配 home Cell。每个 Cell 内含完整 API、库存、worker、ledger 和 outbox，Cell Router 负责路由；全局控制面只管理租户/渠道元数据，recon 按 Cell 汇聚 ODS。

## 1. 架构与模块职责

- `benefit-global-control`：tenant→homeCell、SKU模板、渠道目录、全局开关；不写发放事务。
- `benefit-cell-router`：从 JWT tenant 和 order homeCell 路由，支持搬迁双读禁双写。
- `benefit-cell-runtime`：方案 B 的完整领域闭环，每 Cell 独立数据库、Kafka namespace 和密钥。
- `benefit-global-query`：跨 Cell 客服查询聚合，只读。
- `recon-platform`：每 Cell ODS 对账，再汇总全局报表；remediation 回原 homeCell。

## 2. 核心流程

1. Router 查 tenant homeCell，并把路由结果写入 intent envelope。
2. Cell 在本地事务处理 order/inventory/outbox；所有重试按 order 上的 homeCell 回原 Cell。
3. 渠道 Adapter 可在 Cell 内或区域共享；共享渠道仍由全局限流服务协调。
4. ODS 以 `(cellId,tenantId,eventId)` 汇聚，remediation 命令携带 homeCell。
5. 租户搬迁使用 freeze→drain→snapshot/copy→verify→switch，不允许 active-active 双写同 tenant。

## 3. 改动范围

需要新建全局控制面、Router、Cell runtime、全局查询、租户搬迁工具、每 Cell 部署模板和跨 Cell ODS。Drools、recon、JWT client 和所有消息都要理解 cell/homeCell。

## 4. 优势

- 故障域明确，单 Cell/数据库故障不影响其它租户；天然适合区域合规和大规模横扩。
- 热 tenant 可以独占 Cell；库存和订单不做跨 Cell 事务。
- 一期就验证最终分片模型，避免将来修改主键/路由键。

## 5. 风险评审

### 正确性与兼容性

- tenant 搬迁和 homeCell 缓存不一致可能在两个 Cell 各创建一单；需要全局租约/epoch，而这本身成为强一致控制点。
- 活动平台和 recon 当前都没有 cell 概念；全链路协议一次扩大，legacy 回滚更难。

### 事务与库存

- 中心库存若是公司全局共享，拆 Cell 后必须选择库存归属、预分配或全局库存服务；否则“Cell 内本地事务”无法防全局超发。
- 渠道配额通常跨 tenant/Cell 共享，必须增加全局限流/配额，削弱 Cell 独立性。

### 可用性与性能

- Router/control plane 成为新关键依赖；缓存可提高可用性，却增加 stale route 双写风险。
- recon 跨 Cell 水位不同会产生暂时假差异和全局报表延迟。

### 安全与运维

- 每 Cell 密钥、topic、数据库、监控和 runbook 数量成倍增加；tenant 搬迁涉及密文/KMS 区域策略。
- 小规模一期无法充分验证真实 Cell 故障/容量收益，易形成空壳抽象。

### 灰度与回滚

- 回滚 Router 或 tenant map 必须与所有 active orders 的 homeCell 一致，不能简单恢复旧配置。
- 数据搬迁一旦开始，只能 forward-fix；回滚会面临增量回灌和重复 order。

## 6. 扩展性与实施成本

- 初期成本：极高，为四案最高。
- 长期隔离/扩展：最高。
- 测试难度：最高；至少需要双 Cell、路由缓存、迁移和 ODS 水位故障注入。
- 当前适配性：低；需求只要求“未来 Cell/分库分表”，没有要求一期上线 Cell。

## 7. 测试设计

- 双 Cell 同幂等键竞态，证明全局 epoch 只允许一处接单。
- Router 缓存 stale、control plane outage、Cell failover、tenant 搬迁中断/回滚。
- 全局共享 SKU 并发超卖测试；验证库存预分配守恒。
- 跨 Cell ODS 水位和 remediation 回原 Cell。
- 区域密钥、日志、备份和灾难恢复演练。

## 8. 适用结论

只有明确的一期多地域/超大租户隔离指标才值得采用。当前目标是“为未来 Cell 预留”，方案 B 保留 tenant/homeCell/routing 接缝即可；现在实施 D 会把待验证的未来约束变成大量当前故障面。
