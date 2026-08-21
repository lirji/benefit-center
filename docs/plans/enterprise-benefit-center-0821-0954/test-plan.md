# 测试方案与验收标准

> 视角：test-designer。测试目标是证明“不会重复发、不会超卖、未知结果不会双发、部分成功可解释、账能闭环”，不是只证明接口返回 200。

## 1. 测试分层

| 层级 | 运行范围 | 核心证据 |
|---|---|---|
| 单元/属性测试 | domain/application，无 Spring | 状态机合法转换、幂等 key、金额/数量守恒、route 决策、错误码映射 |
| 组件测试 | JDBC、Kafka、Adapter、security | 唯一键/CAS/lease/outbox/inbox、序列化兼容、签名和租户隔离 |
| 集成测试 | benefit-center 单体 + MySQL/Kafka + channel simulator | 接单→库存→渠道→ledger→event 全链路 |
| 跨仓回归 | drools + benefit + recon | AwardIntent、ODS 三方、remediation 闭环、legacy 不回归 |
| 故障注入 | kill/timeout/duplicate/乱序/failover | 每个事务边界后崩溃仍收敛到唯一业务结果 |
| 性能/容量 | API、worker、热点库存、ODS/recon | 达到批准吞吐且零超卖、backlog可恢复、DB资源有余量 |
| 灰度演练 | LEGACY/SHADOW/CENTER | 可观测、可停新流量、已接单不断档、不双发 |

## 2. 候选方案的差异化测试成本

### A

- 必须把 `GrantLedgerTest` 扩成组合奖品，同时证明旧 claim/confirm/release JSON、状态码和 recon view 不变。
- activity publish、lifecycle、decision、grant worker 共享库/进程的锁竞争回归是必测项。
- 还必须提前做未来抽离的数据双写/追平演练，测试成本并未真正消失。

### B

- 重点集中在本地事务边界与一个外部 operation 状态机；可用 MySQL+Kafka+channel simulator穷举。
- 跨仓只需验证契约、灰度 authority 和 ODS，不需要在一期验证服务间库存 saga。
- 是四案最容易给出“每个失败点 kill 后唯一收敛”证据的方案。

### C

- 每一个服务本地事务后都要 kill；每条 command/event 要重复、乱序、延迟、版本混跑和重放。
- 必须做 saga 模型检查、projection 重建、六服务 contract compatibility 和多 broker partition 测试。
- 端到端测试矩阵随服务数和状态数乘法增长。

### D

- 除 C/B 的履约测试外，还要双 Cell 同键竞态、route cache stale、tenant move、global inventory 和跨 Cell ODS 水位。
- 真实故障域/区域收益在单机测试环境难以证明，验收成本最高。

## 3. benefit-center 单元测试

### 3.1 状态机

每一合法边建正例，每一非法边建反例：

- Order：ACCEPTED→PROCESSING→三种结果；PARTIAL/FAILED→REMEDIATING；SUCCESS/PARTIAL→REVERSING；REVERSED 不可成功。
- Item：PENDING/RESERVED/DISPATCHING/UNKNOWN/终态；只有 FINAL_FAILED 可 REISSUE，只有 SUCCEEDED 可 REVERSE。
- Operation：UNKNOWN 只能 QUERYING，不能直接创建新 fallback；同 operation 迟到响应不得覆盖更可信回调终态。
- Remediation：同 externalCommandId 同 payload replay；异 payload conflict；REJECTED/EXECUTED 不可重开。

验收：状态机 transition table 分支覆盖 100%；所有枚举值被无 default 的 switch 或参数化测试覆盖。

### 3.2 路由与库存

- 首选 channel success：中心 quota reserved→issued，eager fallback stock reserved→released。
- channel 明确 OOS：fallback route enabled 才切；disabled/非等价/无库存均 final failed。
- timeout/5xx：进入 UNKNOWN，不 fallback。
- channel capability 不支持 reverse/query/reserve：按能力矩阵进入人工或禁 route。
- CAS：available=n，并发 n+k 请求，成功恰 n，available不负、reserved+issued守恒。
- reservation timeout：只释放未进入成功/unknown 的 reservation；unknown 禁自动释放后再重发。

### 3.3 金额、码与隐私

- CASH amountMinor >0、currency匹配 SKU、上下限；溢出/小数在 contract 边界拒绝。
- 非现金不构造 currency/amount；ODS 与对账模型只比较 quantity、SKU、providerRef 和状态。任何把非现金写成 `XXX/0` 的映射都应被 schema/mapper 测试拒绝。
- code hash 唯一；密文可解、明文不出 entity `toString`/日志/JSON；并发分配一码只给一 item。
- physical 只接受 addressRef；明文地址字段反序列化应被 schema 拒绝。

## 4. benefit-center 持久化与并发集成测试

测试数据库必须包含 MySQL 8；H2 只用于快速反馈，不能替代以下证据。

1. **入口幂等**：100 并发同 key 同 payload，只一 order；其余返回同 orderNo。异 payload 全部 409。
2. **Item 唯一**：同 order/clientItemId unique；重复 MQ delivery 不增 item。
3. **Operation lease**：两 worker 抢同 operation，仅一方得到 lease；lease 到期可接管；旧 owner 提交被 expected lease/version 拒绝。
4. **Outbox 原子性**：在 order commit 前抛异常，无 order/outbox；commit 后 kill relay，重启可发布。
5. **Inbox 原子性**：消费失败保留可重试状态；处理成功后重复消息不重复业务动作。
6. **Callback race**：callback先到、sync后到；sync先到、callback后到；两个结果相同收敛，冲突进入告警/人工，不回退终态。
7. **Deadlock/retry**：多 item 以稳定 SKU/account 顺序锁定；故意反序制造死锁，事务级有限重试不重复外部调用。
8. **Ledger唯一**：同 operation+entryType 只能一行；issue+reversal 净额/净数量为0。
9. **Cell键**：同 tenant 固定 homeCell；已有 order 查询/重试忽略当前 tenant map 改动，回原 homeCell。

## 5. 渠道 Adapter 契约测试

每个真实 Adapter 上线前必须通过同一 `ChannelAdapterContractTest`：

| 用例 | 期望 |
|---|---|
| issue success + replay | 两次调用同 operation key，渠道只一份权益，同 reference |
| business OOS | 映射 `CONFIRMED_OUT_OF_STOCK`，允许 route engine评估 fallback |
| validation/recipient invalid | FINAL_FAILED，不自动重试 |
| 429/5xx | RETRYABLE 或 UNKNOWN 按渠道文档，不猜 OOS |
| connection/read timeout | UNKNOWN；若支持 query 则查询同 request no |
| callback duplicate | inbox去重，状态不变 |
| callback out-of-order | 单调状态；较弱状态不覆盖较强终态 |
| reverse supported | 原 issue reference定向撤销，replay不重复 |
| reverse unsupported/already consumed | MANUAL_REQUIRED，不伪造成功 |
| rate limit/circuit/bulkhead | 仅该 channel受限，其它 channel worker 正常 |
| secret/redaction | 请求日志不含 token、码、PII；metric label无 user/order |

没有正式 API 文档和 sandbox 的 Adapter 只能标 `reference/mock`，不能通过生产验收。

## 6. AwardIntent API/MQ 契约测试

- OpenAPI request/response 使用 golden JSON；新增 optional 字段兼容，删除/改义必须破坏性版本升级。
- HTTP 与 Kafka 输入经同一 validator/handler，产生相同 order/items/hash。
- envelope 的 eventId/schemaVersion/tenantId/traceId/partitionKey 必填；partitionKey固定 tenant+awardOrder/sourceRequest。
- payload hash canonicalization 对 JSON key order 不敏感，对业务值变化敏感。
- schema registry compatibility（若启用）必须 backward；未知 major version进 DLQ。
- 大小/项数/字符串长度上限测试，防止超大 intent 占库/占消息。

## 7. Drools 接入回归

### 新测试

- `ActivityAwardBindingTest`：create/edit版本各自绑定，旧版本不被新绑定污染，跨租户不可见。
- `ActivityAwardIntentAssemblerTest`：
  - discount 只取 `applied=true` item；动态 amount 来自 server decision；
  - gift 由 activity/version/batchId 映射；
  - 无 binding/禁用 SKU fail-closed；
  - 不读取客户端 benefitSku/amount/channel。
- `ActivityAwardIntentOutboxTest`：sourceRequest 幂等、outbox原子、重试/DEAD、HTTP/Kafka publisher。
- `ActivityBenefitCutoverTest`：LEGACY只走旧 grant；SHADOW不真实发；CENTER只发 intent，禁止双 authority。
- `ActivityAwardIntentSecurityTest`：新端点需 write authority，tenant信封冲突403。

### 必跑既有回归

- `DecisionGoldenSetTest`、`DecisionOutputContractTest`、`DecisionScopeGoldenTest`。
- `GrantLedgerTest`、`GrantOutboxGatingTest`、`GrantOutboxTest`。
- `TenantIsolationTest`、`DecisionAuthIntegrationTest`、`DecisionDdlGuardTest`。

验收：既有 decision JSON、金额算法、查询次数和 legacy grant 状态码不变；新增逻辑只在 connector write path。

## 8. recon ODS 与 remediation 测试

### ODS

- eventId/inbox 幂等；同事件异 payload拒绝并告警。
- 三张 ODS 按 tenant+eventId唯一；时间窗口边界 `[from,to]` 和 keyset分页不漏不重。
- 两 tenant 使用相同 issue/order/channel serial 不互相匹配；跨租户 API不可查询。
- 非现金 observation 的 expected/internal/fulfillment 在 issueId、SKU、quantity、status 一致时干净匹配；缺一侧为 MISSING/BRIDGE_BROKEN，数量或 SKU 不同产生专属差异；重复 event 与重复业务凭证分别区分。

### `BENEFIT_CASH_3WAY` 与 `ENTITLEMENT_FULFILLMENT`

现金场景参数化复现 clean、missing、duplicate、amount/currency/status/timing/bridge broken，并断言金额守恒。非金额场景复现 clean、missing、extra、duplicate、quantity/SKU/status/timing/bridge broken，并断言数量守恒；两类测试不得共享伪金额 fixture。

### Remediation

- 安全矩阵：UNKNOWN、跨币、无 targetRef、不可逆权益只能 MANUAL_REVIEW。
- auto allowlist 默认空；开启单一测试 tenant+error code后仅该类 auto approve。
- 同 fingerprint/command id重跑不重复 suggestion/outbox/benefit operation。
- 并发审批/执行使用 expected-state CAS；只有一条 command发布。
- benefit返回 SUCCEEDED/FAILED/MANUAL_REQUIRED 后状态单调更新；下一账期验证已修复差异消失，未修复仍存在且不重复执行。
- 保留既有 `MarketingThreeWay*`、`DispositionConvergenceA1Test`、`ReconJobRerunIdempotencyTest`、`ReversalApprovalWorkflowIT` 回归。

## 9. 跨系统端到端场景

1. Drools discount+gift 组合→AwardIntent 3 item→两个渠道成功、一个OOS且fallback失败→PARTIAL_SUCCESS→ODS产生一条MISSING→审批补发→SUCCESS→下次对账clean。
2. 渠道 success 响应丢失→中台UNKNOWN→callback success→只一 ISSUE；期间 recon等待水位，不提前补发。
3. cash发1000分，渠道只发900→AMOUNT_MISMATCH→REISSUE adjustment或REVERSE选择按安全矩阵；结果三方一致。
4. duplicate callback/MQ/API并发→order/item/operation/ledger/channel reference均唯一。
5. 已成功券被核销后 recon请求冲正→Adapter返回 consumed→MANUAL_REQUIRED，无负向ledger。
6. tenant A/B同 sourceRequest/order/SKU→各自一单一库存账；A token查B返回404/403。

## 10. 故障注入矩阵

| 注入点 | 恢复后不变量 |
|---|---|
| 接单事务写一半 | 全回滚，无库存悬挂 |
| 接单commit后、HTTP响应前 | replay返回首次order，不建第二单 |
| worker取得lease后崩溃 | lease到期接管，稳定operation避免双发 |
| 渠道success后、中心落库前崩溃 | query/callback确认，仍只一 ISSUE |
| 中心落库后、outbox publish前崩溃 | relay恢复，事件至少一次，消费者幂等 |
| publish成功、mark sent前崩溃 | 重发同eventId，消费者丢重复 |
| recon消费ODS后、offset提交前崩溃 | inbox命中，不重复ODS |
| remediation发布后、mark sent前崩溃 | benefit inbox命中，不重复补发/冲正 |
| Kafka不可用30分钟 | API可按容量继续接单或主动backpressure；恢复后outbox清空 |
| DB failover | 未决事务重试不跨外部I/O；无超卖/双发 |

## 11. 性能与容量测试

- API：1、5、20 item组合；同 key replay；不同 tenant。记录p50/p95/p99、DB连接、锁等待、outbox写入。
- 库存：单热点 SKU 与均匀1000 SKU；零超卖是硬断言。
- Worker：快/慢/限流/断路渠道混合，证明bulkhead隔离；backlog恢复时间。
- Kafka：目标吞吐×2、rebalance、outbox批量、consumer lag。
- ODS/recon：按预计日量×2造数，keyset常量内存，partition无死锁，守恒闭合。
- 资源门槛使用业务批准值；当前建议的 API p95≤100ms/p99≤200ms只作为初始基线。

## 12. 发布验收清单

- [ ] 单元、组件、MySQL/Kafka集成、跨仓E2E全部绿，测试报告附版本和命令。
- [ ] real Adapter 的 sandbox contract test 证据齐全；未齐全 route保持disabled。
- [ ] 100并发同intent、热点库存、未知结果、callback race和并发remediation通过。
- [ ] 既有 Drools golden/legacy grant/recon MARKETING_3WAY 回归通过。
- [ ] ODS tenant/window 不重不漏，`BENEFIT_CASH_3WAY` 金额守恒与 `ENTITLEMENT_FULFILLMENT` 数量守恒均通过。
- [ ] 故障注入、backlog恢复、灰度切换、回滚新流量演练通过。
- [ ] 监控/告警能发现 API错误、库存不足、UNKNOWN、outbox/inbox lag、DEAD、渠道错误、部分成功、recon差异和remediation失败。
- [ ] 安全测试证明跨租户、越权、回调伪造、敏感日志均被阻断。
