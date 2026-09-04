# benefit-console

权益发放中台的独立运营台。桌面完整可用；窄屏可查单、看状态、轻量处置。不提供手工发奖表单。

```bash
corepack enable
corepack pnpm install
corepack pnpm dev      # http://localhost:5173 ，反代到 :8183
corepack pnpm test
corepack pnpm build
corepack pnpm e2e
```

开发模式默认 `VITE_AUTH_MODE=dev`，请求带 `X-Tenant-Id`（登录页可改，默认 `dev-tenant`）。生产走同源 nginx：`/healthz` 给门户探活，`/openapi` `/admin` `/internal` 反代 Java API。
