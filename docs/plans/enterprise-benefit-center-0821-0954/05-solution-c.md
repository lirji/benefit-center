# 候选方案 C：事件驱动微服务 Saga

> 架构意图：从第一天拆成 intent、inventory、orchestrator、channel workers、ledger、query 六类服务；Kafka 是唯一主干，每项发放由 Saga 编排，读模型通过事件构建。

## 1. 架构与模块职责

- `award-intent-service`：幂等接单和 AwardIntent 校验。
- `benefit-inventory-service`：中心库存/配额、码池；独立数据库。
- `fulfillment-orchestrator`：组合 saga、路由、fallback、补偿。
- `channel-worker-*`：现金、券、服务券、实物各独立消费组和部署。
- `benefit-ledger-service`：不可变 ISSUE/REVERSAL 台账。
- `benefit-query-service`：CQRS read model。
- `recon-platform`：直接消费全量事实事件进 ODS并发 remediation command。

服务间禁止同步数据库访问；全部通过 Kafka command/event，必要查询走 API。

## 2. 核心流程

1. intent service 落单并发布 `AwardAccepted`。
2. orchestrator 发 `ReserveInventory`；inventory 回 `Reserved/Rejected`。
3. orchestrator 发渠道 command，worker 调外部并回 success/failure/unknown。
4. orchestrator 决定 fallback/重试/部分结果；ledger service消费成功事实建账。
5. query service异步汇聚最终视图；recon 消费三类事件。
6. remediation 走反向 command saga。

## 3. 改动范围

除 Drools connector 与 recon ODS 外，需要至少六个新部署单元、各自数据库、topic、schema registry、服务发现、统一鉴权、trace 和 CI/CD。测试和本地编排也必须覆盖全套 broker/DB。

## 4. 优势

- 库存、渠道和查询可按各自负载独立扩容；单个慢渠道不会占用其它 worker 资源。
- 事件事实天然适合 ODS、回放、审计和多下游。
- 服务边界接近未来组织分工，长期超大规模下可减小单库热点。

## 5. 风险评审

### 正确性与事务

- order 与 inventory 不在同一事务，接单成功但 reserve command 丢失、reserve 成功但 saga 未收到等情况都依赖多级 outbox/inbox、超时扫描和补偿。
- 组合部分成功、fallback、补发、冲正会形成大量交叉 saga；状态空间远大于方案 B。
- ledger 消费事件建账存在“履约成功但台账积压”的窗口；recon 会产生暂时假差异，需要水位和等待窗。

### 并发与幂等

- 每个 service、topic 和 projection 都要独立幂等；operation id 设计稍有漂移就会在重放时重复库存或重复发放。
- Kafka 分区键必须让同 item/order 有序，同时不能把热点 tenant/SKU 全压一分区；两者取舍复杂。

### 性能与运维

- 单步骤吞吐高，但端到端延迟包含多次 broker 往返；小规模下未必优于模块化单体。
- 需要成熟 Kafka、schema registry、DLQ、重放审批、跨服务 trace、容量和 on-call；仓库现状没有这些公共基建证据。

### 安全与数据

- 敏感 payload 在多个 topic 和服务复制，码/PII 脱敏与 ACL 面扩大。
- 数据修复需考虑事件事实、各服务当前表、读模型和 ODS 四份状态，回滚不能简单回库。

### 灰度与回滚

- 单服务可独立回滚，但协议兼容和事件回放风险高；发布顺序必须 producer-last/consumer-first。
- Saga 升级中混跑两版状态机，需 upcaster 和版本路由，一期成本大。

## 6. 扩展性与实施成本

- 初期成本：极高。
- 水平扩展：高。
- Cell/分片：中高，但必须解决跨 Cell topic/库存所有权。
- 维护性：只有在团队按服务长期负责且已有事件平台时才高；当前仓库团队规模和基础设施待验证。

## 7. 测试设计

- 每一 saga transition 做模型检查/property test。
- Kafka 故障注入：重复/乱序/跨版本/分区迁移/DLQ 重放。
- 跨六服务端到端：每个本地事务之后 kill -9，验证最终唯一结果。
- Contract compatibility：所有 event schema backward/forward。
- 大规模事件回放与 projection 重建；敏感信息 topic ACL 检查。

## 8. 适用结论

适用于已有成熟事件平台、超高规模和多团队独立所有权。它的“扩展性最好”不等于一期正确性最好；在当前仓库没有 Kafka 基座的证据下，测试难度和隐藏状态组合会压倒收益。
