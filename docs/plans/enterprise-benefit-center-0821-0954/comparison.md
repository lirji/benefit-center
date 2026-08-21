# 候选方案对比与统一评审

> 视角：risk-reviewer + plan-judge。评分先固定量表和权重，再看方案，减少因先偏爱某架构而调整标准的确认偏差。

## 1. 统一评分规则

所有维度 1–5 分，**5 始终更有利**：

- 正确性：满足独立中台、组合部分成功、幂等、库存、remediation 的程度。
- 改动风险：5=现有系统影响小、隔离好；1=高耦合/大迁移。
- 复杂度：5=一期复杂度低；1=状态/部署/运维极复杂。
- 可维护性：边界清晰、可定位、团队能长期维护。
- 扩展性：渠道、来源、容量、Cell/分片演进能力。
- 测试难度：5=容易形成确定性自动化证据；1=需大量分布式故障状态。
- 回滚成本：5=开关隔离、无迁移/双写；1=协议/数据/路由难回退。

权重：正确性 25%、改动风险 15%、复杂度 10%、可维护性 15%、扩展性 15%、测试难度 10%、回滚成本 10%。总分为加权 5 分制。

## 2. 评分表

| 方案 | 正确性 25% | 改动风险 15% | 复杂度 10% | 可维护性 15% | 扩展性 15% | 测试难度 10% | 回滚成本 10% | 加权总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 活动内扩展再抽离 | 2 | 2 | 4 | 2 | 2 | 3 | 3 | **2.60** |
| B 独立模块化单体 | 5 | 4 | 4 | 5 | 4 | 4 | 4 | **4.40** |
| C 事件驱动微服务 Saga | 4 | 2 | 1 | 3 | 5 | 1 | 2 | **2.90** |
| D Cell-first | 4 | 1 | 1 | 3 | 5 | 1 | 1 | **2.65** |

评分不是数学真理，敏感性如下：若已存在成熟 Kafka/Saga/多团队平台，C 的复杂度、维护性和测试难度可各升 1，总分约 3.25，仍低于 B；若一期明确要求多地域 Cell，D 的正确性和改动风险会变化，需要重评，而不能沿用本表。

## 3. 逐方案判决

### A：不选

一句话原因：复用现有 grant 能快速演示，但一期不产生独立 benefit-center，并把活动库存、权益库存和敏感渠道数据混在同一故障域，后续还要付一次完整抽离成本。

可吸收点：条件 UPDATE/CAS、红蓝字分录、outbox relay 的实现模式；不复制具体 grant 表和事件 schema。

### B：作为主线

一句话原因：以一个本地事务保证 order/item/中心库存/outbox，外部渠道用 operation saga 收敛，在正确性、一期可测性与未来拆分之间最平衡。

不能忽略的弱点：单逻辑库热点、API/worker 连接争用、outbox backlog、eager fallback reserve 占库存、real channel 能力待验证；“模块化单体”不消除外部最终一致性。

### C：不整案采用

一句话原因：伸缩和团队自治最好，但当前仓库没有成熟事件平台证据，从第一天拆六服务会把接单、库存、ledger 的本地原子性拆成多级 saga，测试状态爆炸。

可吸收点：AsyncAPI、稳定 partition key、consumer inbox、schema compatibility、渠道 worker 可独立 profile/未来拆服务。

### D：不整案采用

一句话原因：需求是“未来 Cell/分库分表”，不是一期多 Cell；提前引入 Router、global control、tenant 搬迁和全局库存所有权会制造比渠道发放更难的问题。

可吸收点：所有主键/唯一键带 tenant、order 固化 `home_cell`、无跨租户事务、按 tenant 路由、搬迁时 freeze/drain 的约束。

## 4. 失败场景横向比较

| 失败场景 | A | B | C | D |
|---|---|---|---|---|
| activity-console 故障 | 决策写面和全公司发放同时受损 | activity 只影响新 intent；中台继续收敛已接单 | intent producer受损，其余服务继续 | 对应 source/Cell受损 |
| DB 事务后进程崩溃 | 同库 outbox 可恢复 | 同库 outbox 可恢复 | 每个服务各自 outbox，跨服务需 saga timeout | 每 Cell outbox，另有路由恢复 |
| 渠道成功响应丢失 | 需扩展旧 grant，易误 fallback | operation UNKNOWN+query，边界清晰 | 多服务事件未知状态 | 同 B，另加 Cell route |
| 热点 SKU | 与 activity 表争锁 | 单库热点，能做分桶账户/限流 | inventory service独立扩容 | 可按 Cell分摊，但全局库存难 |
| 同请求双路由 | legacy/new 共享服务，最难发现 | authority flag + source unique 可防 | 多入口 topic需全局 idempotency | stale homeCell可能跨库双单 |
| recon 自动补发并发 | 旧模型无 item/operation | remediation/item CAS | command saga 多级幂等 | 同 C + 回原 Cell |
| 回滚新版本 | schema与旧代码耦合 | 新流量切 LEGACY，已接单继续 | 协议版本/事件回放复杂 | tenant map/数据位置限制回滚 |

## 5. 最终合并方案

最终方案以 B 为骨架，吸收而不提前实现：

1. C 的版本化 OpenAPI/AsyncAPI、Kafka adapter、稳定 operation partition key、inbox/outbox 和 schema compatibility gate。
2. D 的 `tenantId + homeCell + routingKey` 数据约束、聚合不跨分片、ID 全局唯一、旧订单回 homeCell。
3. A 中已经由仓库验证的 CAS、append-only ledger、transactional outbox 和短事务置态模式。
4. recon 继续复用现有两段 spine、守恒、人工痕迹三表分离，不重写对账核心。

一期明确不吸收 C 的服务拆分和 D 的 Router/多 Cell运行时。未来触发条件必须量化：

- 单库在目标峰值×2 压测中持续成为瓶颈，且优化热点/索引/连接池后仍不达标；
- 渠道 worker 与 API 的发布/容量确有独立需求；
- 出现必须隔离的区域合规或单租户故障域；
- 团队具备 Kafka schema、重放和跨 Cell on-call 能力。

## 6. 方案 B 的已知弱点与缓解

| 弱点 | 一期缓解 | 不能过度承诺的边界 |
|---|---|---|
| 单库热点 | 短事务、库存 CAS、按 SKU/account 分桶、API/worker pool 隔离、压测 | 未实测前不能承诺无限横扩 |
| API+worker 同 artifact | profile 分角色、同代码不同 deployment | 仍共享 schema；未来拆进程需容量证据 |
| 渠道最终一致 | stable operation、query、callback inbox、UNKNOWN 禁 fallback | 无 query/幂等能力的渠道只能降级人工 |
| eager fallback reserve 占库 | reservation TTL/age metric，渠道成功立即 release | 若业务要求极低占用可改 LAZY，但兜底成功率会下降 |
| 双对账来源 | legacy/new 场景隔离、cutover 时间和 source namespace | 一期客服查询仍可能跨两系统 |
| Kafka 基建待验证 | transport port；HTTP入口必做；Kafka profile可开关 | 若组织不用 Kafka，实施前必须替换适配器和部署计划 |
| real Adapter未知 | capability matrix、模拟器、contract test、route默认禁用 | 没有正式渠道文档/账号不能验收真实发放 |

## 7. 结论

选择方案 B 的理由不是它最“简单”，而是它把最关键的 order/item/inventory/outbox 保持在可证明的本地事务，同时把不可避免的渠道异步复杂度收敛到 operation 状态机；现有 activity 与 recon 只做边界清晰的增量改造，灰度和回滚成本最低。最终执行细节见 `FINAL_PLAN.md`。
