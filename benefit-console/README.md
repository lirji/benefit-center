# benefit-console

权益发放中台的独立运营台。覆盖总览、SKU 模板、发放订单、用户钱包与补发/冲正处置；桌面完整可用，窄屏可查单、看状态、轻量处置。不提供手工发奖表单。

```bash
corepack enable
corepack pnpm install
corepack pnpm dev      # http://localhost:5173 ，反代到 :8183
corepack pnpm test
corepack pnpm build
corepack pnpm e2e
```

开发模式默认 `VITE_AUTH_MODE=dev`，请求带 `X-Tenant-Id`（登录页可改，默认 `dev-tenant`）。生产走同源 nginx：`/healthz` 给门户探活，`/openapi` `/admin` `/internal` 反代 Java API。

主要入口：

- `/catalog`：维护 DRAFT SKU、提交首次上线审批；PENDING_APPROVAL 只读，审批在 workflow-platform 待办中心完成。
- `/orders`：查询订单及 item 级履约状态，可从成功项跳转关联钱包资产。
- `/wallets`：按 subjectRef 查询资产与余额；对合法状态显示 freeze/redeem/refund，所有命令都生成新的 `Idempotency-Key` 并以 202 结果为准。
- `/remediations`：查看和执行受控 REISSUE/REVERSE，不与钱包 refund 混用。

生产 OIDC 权限以服务端为准：目录、钱包查询与钱包命令当前均要求 `benefit.admin`；订单读写和 remediation 分别使用 `benefit.award.*`、`benefit.remediate`。前端隐藏按钮只用于减少误操作，不构成授权边界。
