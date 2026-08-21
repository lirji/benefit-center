# 渠道接入规范

## 必备资料

- 正式 API 文档、sandbox、限流与超时口径、状态码表。
- issue/query/reverse 的稳定请求号语义；callback 签名、时间戳和重放规则。
- 权益是否可撤销、已使用/已核销/已发货后的边界。
- 库存权威方、明确 OOS 码、fallback SKU 等价性审批。
- secret/Vault 引用、PII 字段、日志脱敏和数据保留期限。

## 统一结果映射

| 渠道结果 | 中台结果 | 自动动作 |
|---|---|---|
| 明确成功 | `SUCCEEDED` | 记 ISSUE ledger |
| 明确未发/OOS | `NOT_ISSUED` | 仅在配置等价 route 时评估 fallback |
| 参数或收件人永久错误 | `FINAL_FAILURE` | 释放中台预占，等待人工/对账 |
| 可证明未送达的临时错误 | `RETRYABLE_FAILURE` | 同 operation 重试 |
| timeout、断连、无法判定的 5xx | `UNKNOWN` | 查询同 operation；禁止新 issue |

## 上线契约测试

每个真实 Adapter 必须证明：issue replay 不重复、timeout 后 query 可确认、明确 OOS 映射正确、重复/乱序 callback 单调收敛、reverse 定向且幂等、限流/熔断只影响本渠道、日志无 token/兑换码/PII。缺任一证据时 route 必须保持 `enabled=false`。

`ConfigurableHttpChannelAdapter` 只是参考签名协议，不能作为某个真实渠道已经通过验收的证据。
完成验收后仍需同时开启全局 `BENEFIT_REAL_CHANNEL_ENABLED=true` 与具体
`BENEFIT_HTTP_CHANNEL_ENABLED=true`；任一为 false 都不会注册真实 HTTP Adapter。
`BENEFIT_HTTP_CHANNEL_CONNECT_TIMEOUT_MS`/`READ_TIMEOUT_MS` 必须按渠道 SLA 显式复核，且 worker
lease 应覆盖最坏调用与本地收敛时间；timeout 一律映射 `UNKNOWN` 后查询原 operation。
