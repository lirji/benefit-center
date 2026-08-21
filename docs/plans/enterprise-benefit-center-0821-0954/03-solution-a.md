# 候选方案 A：活动平台内扩展发放内核，再抽离

> 架构意图：以现有 `GrantService` 为核心，把多权益、渠道和组合状态先做进 `activity-console`，待稳定后再整体抽成 `benefit-center`。这是“低初始投入的绞杀迁移”方案，不是最终推荐方案。

## 1. 架构与模块职责

- `activity-common`：扩展 `ActivityGrantEntity`/entry/outbox，新增 award item、渠道、码池、库存模型。
- `activity-console`：把 `GrantService` 扩成组合发放编排器，新增渠道 Adapter、worker、补发/冲正 API。
- `activity-decision`：仍保持只读，只返回当前 decision 结果。
- `recon-platform`：继续读 activity DB 视图，扩充三方源。
- 第二阶段再把上述包复制/迁移到独立 `benefit-center`。

## 2. 核心流程

1. 活动写面收到“提交奖品”请求，调用现有 `ActivityQueryService` 重算。
2. 在 activity DB 中写扩展后的 grant/order/item、扣活动库存、写 outbox。
3. activity worker 调渠道并更新 item，组合聚合部分成功。
4. recon 通过 activity DB 视图读取预期/内部/渠道表。
5. 后续再抽库、抽服务、切调用方。

## 3. 改动范围

改动集中在 `drools-demo/activity-common` 与 `activity-console`：现有 `GrantService#claimInventory/confirmGrant/releaseGrant`、三张 grant 表、`ActivityMarketingController`、安全矩阵和配置都会承担新语义。`recon-platform` 仍需 ODS/remediation 改造，但短期可以用同库视图。

## 4. 优势

- 直接复用已验证的 CAS、追加分录、outbox relay、租户和鉴权实现模式。
- 不先引入跨服务、MQ、独立运维，开发环境最容易跑通。
- 对当前单活动现金发放改动最少，短期演示成本最低。

## 5. 风险评审

### 正确性与兼容性

- **违反硬目标**：一期产物不是独立 benefit-center，无法按要求独立扩缩容和开放给非 Drools 系统。
- `activity_grant` 的唯一键 `(tenant,order,activity)` 与组合 item/补发 operation 语义冲突。原地扩表会让 legacy JSON、客服查询和 recon 视图一起变化。
- `activity_manage.inventory` 是活动配额，不是权益 SKU 库存；混用会把营销预算与真实券码/渠道库存合并成一笔错误账。

### 事务、并发与幂等

- 同库事务看似简单，但把活动配置、库存、渠道任务和权益账绑到一个数据库；热点渠道库存会反向拖慢活动写面。
- 后续抽离时本地事务会被拆成跨服务 saga，必须重写一遍幂等、补偿和迁移。
- 原 `grantNo:eventType` 只能一 ISSUE/一 REVERSAL，不能表达补发多 operation；强改会破坏旧消费者。

### 性能与可用性

- `activity-console` 同时承担配置发布、生命周期调度、grant outbox、渠道慢调用和履约 worker；现有调度池曾需要专门避免下游 webhook 阻塞活动上下线，继续叠加会放大耦合。
- 活动平台故障会同时丧失决策配置和全公司权益发放能力。

### 安全与数据迁移

- activity DB 将存兑换码密文、物流引用、渠道响应和资金账，权限面远超原营销控制台。
- 真正抽离时需要在线搬迁活动 grant、码池、operation 和 outbox，双写/追平/切换风险最高。

### 灰度与回滚

- 可用旧/new endpoint 做方法级开关，但因为共享表和类，回滚 jar 可能无法读取扩展状态或新枚举。
- schema 只能前滚，业务回滚却仍在同一个服务，隔离效果差。

## 6. 扩展性与实施成本

- 初期成本：中。
- 总拥有成本：高；必然经历第二次抽离改造。
- Cell/分库分表：差。activity 的版本化表、decision 快照和权益账的分片维度不同。
- 多来源开放 API：差。非活动调用方被迫依赖 activity 领域。

## 7. 测试设计

- 必须扩展现有 `GrantLedgerTest` 覆盖组合 item 与 legacy API 兼容。
- 必须证明 `DecisionGoldenSetTest`、`DecisionOutputContractTest` 完全不变。
- 并发测试同时运行 activity publish/lifecycle 与热点发放，验证无锁干扰。
- 抽离演练需要双写、追平和断点恢复测试；这部分在一期就无法省掉，只是被推迟。

## 8. 适用结论

只适用于“先做内部原型且明确允许一期不独立”的场景。本任务把独立 benefit-center 写成硬要求，因此该方案即使短期快，也不能作为交付方案。
