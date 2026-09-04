# benefit-console 实施计划

> 目录：`docs/plans/benefit-console-0904-1201/`
> 配套决策：`DECISION_RECORD.md`
> 状态：待批准

## goals

- 在 `benefit-center/benefit-console` 落地独立 SPA，前后端分离。
- 运营可配置租户/SKU/路由/库存，可按单号点查发放与补发。
- 门户 `launchUrl` 指向 console，`healthUrl` 仍为同源 `/healthz`。
- 桌面完整可用；窄屏可查单、看状态、轻量处置。

## non-goals

- 手工发放 AwardIntent、活动绑定、Drools 灰度。
- Casdoor 正式登录回环（文件可预留，生产 catalog 保持 `coming-soon`）。
- 跨租户切换、客服批量查询、报表/BI、真实渠道配置向导。
- 浏览器内加密兑换码、展示 `cipher_text`。
- 独立 H5 / App；手机不做完整目录/码池导入。

## 视觉方向与设计参考（待用户确认的假设）

| 来源 | 借鉴 | 落到本项目 |
|---|---|---|
| Mobbin / SaaS Interface 运营台 | 顶栏 + 左导航 + 指标卡 CTA | `AppLayout` + `MetricCard` + `PageHeader` |
| Ant Design Pro List+Detail | filter + 右侧 Drawer | 订单/补发详情 |
| `recon-console` | 992 Drawer 菜单、768 卡片表、44px 主按钮 | 直接复刻布局与 CSS 节奏 |
| benefit 落地页 | accent `#0F6F6A`、ink `#142033`、bg `#F5F8FB` | `src/theme/colors.ts` |

Tokens：`borderRadius 8/12`，`controlHeight 36/40`，侧栏 224，顶栏 56，content max 1440，gutter 16。字体 `Inter, PingFang SC, Microsoft YaHei`。状态不只靠颜色：Tag 必带中文。

## 路由与页面流

```
/                     → /dashboard
/dashboard            工作台（指标 + 需关注订单）
/orders               发放订单（双路径点查 + 关注列表）
/remediations         补发处置（按号查询 + 从订单预填）
/inventory            库存账户 + 调整 + 码资产元数据
/catalog              Tab：租户 / SKU / 路由
/login                预留；dev 模式不强制
/auth/callback        预留
```

主路径：工作台告警 → 订单 Drawer → 子项 `FAILED_FINAL` → 补发表单。

配置路径：目录建 SKU/路由 → 库存加配额 → 工作台不再空。

## 组件树

**复用（从 recon-console 改色/改文案）**

- `AppLayout`、`UserMenu`、`PageHeader`、`AsyncState`、`MetricCard`、`StatusTag`

**新建**

- `AwardOrderStatusTag` / `AwardItemStatusTag` / `RemediationStatusTag`
- `OrderLookupForm`、`OrderDetailDrawer`、`ItemTimeline`
- `RemediationForm`（UNKNOWN 禁用 + Tooltip）
- `SkuEditorDrawer`、`RouteEditorDrawer`、`InventoryAdjustModal`
- `CodeAssetImportModal`（无明文栏）

## 状态与边界（逐页）

| 页面 | loading | empty | error | success |
|---|---|---|---|---|
| 工作台 | PageSkeleton | 「尚未配置 SKU」CTA → /catalog | ErrorState 重试 | 4 指标 + 关注列表，30s 刷新 |
| 订单 | 结果区 Skeleton | 未搜：引导输入；404：友好空 | Alert，非整页炸 | Drawer 订单+子项 |
| 补发 | Table/卡片 Skeleton | 「从订单发起或输入 remediationNo」 | 400/409 顶部 Alert | 刷新 Drawer |
| 库存 | 账户卡 Skeleton | 「未建账户」→ 调整即建 | P0 负库存横幅 | 202 后 invalidate |
| 目录 | Tab Skeleton | 「尚未创建」 | 409 版本冲突 | 204 + message |

表单：CASH SKU 必填面额币种；非 CASH 禁止金额；path id = body id；库存 `requestId` 必填；补发 REISSUE/REVERSE 要 `originalOperationNo`，建议填 `approvalRef`。

## API 契约

### 已有（前端只消费）

- `GET /openapi/v1/award-orders/{orderNo}`
- `GET /openapi/v1/award-orders?sourceSystem&sourceRequestId`
- `POST/GET /internal/v1/remediations`、`POST .../execute`
- `PUT /admin/v1/tenants|skus|routes`
- `POST /admin/v1/inventory/adjustments`、`POST /admin/v1/code-assets`

### 新增（一期必须）

全部 **当前 JWT tenant**（`WHERE tenant_id=?`），禁止跨租户；`limit` 默认 20、最大 50。

**不要**复用 `BenefitOperationalMetrics` 的全库 COUNT（那是跨租户 worker 指标）。

Scope 裁定：console 读接口一律挂 `/admin/v1/**`，沿用现有 `benefit.admin`。订单点查继续走 `/openapi/v1` 的 `benefit.award.read`。补发写继续走 `/internal/v1` 的 `benefit.remediate`。dev 模式 `permitAll`；secure 下缺 scope 返回 403，前端进 `ForbiddenPage`。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/admin/v1/console/me` | `{ tenantId, subject, displayName, scopes[] }`；dev 返回全权限沙箱身份 |
| GET | `/admin/v1/console/overview` | **本租户**计数：unknownOps、partialOrders、pendingRemediations、skuCount、enabledRoutes。不做 `invalidInventory` 假指标（表有 `CHECK >= 0`，正常库恒为 0） |
| GET | `/admin/v1/tenants/current` | 当前 JWT 租户配置（homeCell/enabled/version），供目录「租户」Tab |
| GET | `/admin/v1/skus` | SKU 列表 |
| GET | `/admin/v1/routes` | 可选 `skuId` |
| GET | `/admin/v1/inventory/accounts` | 余额三元组；**不含** ledger 全量 |
| GET | `/admin/v1/console/attention-orders` | 订单 `PARTIAL_SUCCEEDED`/`REMEDIATING`，**或**存在 item/operation 状态 `UNKNOWN`/`QUERYING` 的订单；按 `updated_at` 倒序 |
| GET | `/admin/v1/remediations` | 本租户补发列表，可选 `status`；单条详情仍用已有 `GET /internal/v1/remediations/{no}` |
| GET | `/admin/v1/code-assets` | 可选 `skuId`；只回 `codeAssetId/skuId/status/expiresAt/codeHash` 前 8 位；**禁止 cipherText** |

伴随 Flyway：`idx_bc_order_attention (tenant_id, status, updated_at)`、`idx_bc_remediation_status (tenant_id, status, updated_at)`。item/operation 已有 `idx_bc_item_due` / `idx_bc_operation_due`。

写入仍走现有 admin API。OpenAPI 同步进 `benefit-center-v1.yaml`。

## 响应式与移动端

| 语义 | 断点 | 策略 |
|---|---|---|
| shell-mobile | `<992` (`!lg`) | 侧栏改 Drawer；Header 汉堡 |
| content-stack | `<768` (`!md`) | Table → 卡片；Drawer 100%；页头堆叠 |
| desktop | `≥992` | 侧栏 224；工作台 4 指标 |

逐页：

- 工作台：`xs=24 sm=12 xl=6`；一期**不做饼图**（指标卡 + `mobile-data-card` 关注列表）。
- 订单：筛选 vertical；详情全宽 Drawer；`<768` 用 `mobile-data-card`。
- 补发：卡片 + 底部 44px 按钮；UNKNOWN 用 Alert 说明。
- 目录/码导入：`<768` 顶部提示「建议桌面完成」，表单仍单列可用。
- 401/403：整页 `ForbiddenPage`；列表超 50 条只显示「已截断」；工作台 30s 刷新失败保留旧数 + 非阻塞 Alert。

触控目标 ≥44px；`prefers-reduced-motion` 关进场动画。

## 文件级改动清单

**新建**

- `benefit-console/` 全套（package.json、Vite、Dockerfile、nginx.conf.template、src/**、e2e/**）
- `benefit-server/.../ConsoleQueryController.java` + application 查询用例
- adapters JDBC list 方法（tenant 条件 + LIMIT）
- `benefit-server/.../ConsoleAuthController.java`（`/admin/v1/console/me`）
- 对应 JUnit / MockMvc

**修改**

- `deploy/docker-compose.yml`：`benefit-center` 改为 `127.0.0.1:8183:8083`（actuator healthcheck 不变）；新增 `console`（`${BENEFIT_UI_PORT:-8083}:8083`，`depends_on` API healthy，`BENEFIT_API_URL=http://benefit-center:8083`，healthcheck `wget /healthz`）
- `benefit-console/nginx.conf.template`：`/healthz` CORS `*`；反代 `/openapi/` `/admin/` `/internal/` → `$BENEFIT_API_URL`（变量 upstream + Docker DNS）
- `PortalLandingController`：删除 `GET /`；API 容器可保留 `/healthz` 给诊断，**门户探活改打 console nginx**
- `BenefitSecurityConfig`：去掉 `GET /` permitAll；console 读走 JWT/`benefit.admin`
- `PortalLandingEndToEndTest` / `PortalLandingSecureModeTest`：不再断言 HTML `/`；改测 `/healthz` 与 API 401
- OpenAPI yaml + Flyway 关注/补发索引
- `README.md`、`docs/dev-infra.md`、`auth-platform/docs/统一门户端口注册表.md`（`up -d console`）
- `auth-platform/deploy/platform-ports.sh`：`verify_compose benefit console 8083`
- `.github/workflows/ci.yml`：`benefit-console` 的 `pnpm test && pnpm build`（及 mock e2e）

## 实施步骤

1. 后端：tenant 过滤的读 API + `/me` + Flyway 索引 + MockMvc（密文、跨租户、limit）。
2. 脚手架：`benefit-console`、theme（改尽 recon 蓝 `#315EFB`）、layout、Vite 与 nginx 都反代 `/openapi` `/admin` `/internal`。
3. 页面：工作台 → 订单 → 目录 → 库存 → 补发。
4. Compose 双服务 + `platform-ports.sh` 改 verify `console` + 门户文档；同 PR 落地否则 check 会断。
5. 删 Java `GET /` **并改** `PortalLanding*Test` / SecurityConfig。
6. vitest + Playwright desktop + Pixel 5。
7. CI frontend job（working-directory `benefit-console`）。

## 测试策略

- 后端：overview/sku/route/inventory/attention 的 tenant 隔离、limit、code-assets 无 cipher。
- 前端 vitest：状态 Tag、UNKNOWN 禁用补发、权限隐藏写按钮、订单 404 空态、库存表单校验。
- e2e（mock API）：桌面进工作台；`<992` 开菜单；订单点查打开 Drawer；Pixel 5 卡片列表。
- 不在 PR CI 拉 MySQL/Kafka 全栈。

## 验收标准

- [ ] `pnpm test && pnpm build` 与 `mvn -pl benefit-server -am test` 相关类通过。
- [ ] 本地 `compose.sh up` 后门户点「权益发放中台」打开 SPA，不是 Java HTML。
- [ ] `GET http://localhost:8083/healthz` 返回 2xx，CORS `*`，门户探活可用。
- [ ] 工作台能看到真实计数（空库为 0，不是假数据）。
- [ ] 目录可列出并 upsert SKU/路由；库存可列出并调整。
- [ ] 用已有 e2e 夹具单号可查到订单与子项状态。
- [ ] UNKNOWN 子项不能提交补发。
- [ ] 码资产列表/导入 UI 无明文、无 cipher 展示。
- [ ] **Pixel 5（393×851）**：菜单 Drawer 可用，订单结果为卡片，主按钮 ≥44px。
- [ ] 不提供发奖表单。

## 风险与回滚

| 风险 | 缓解 |
|---|---|
| 读 API 全表扫 | 强制 `tenant_id=?` + LIMIT；补 `(tenant_id,status,updated_at)` 索引 |
| 端口拆分破坏门户 check | 同步改 `platform-ports.sh` 服务名 |
| dev-mode permitAll | README 标明仅本机；生产 catalog 不开放 |
| OIDC 未接 | 生产保持 coming-soon |
| 回滚 | catalog `launchUrl` 指回 API 或把项目设 maintenance；console 镜像可单独撤 |

## 假设与待澄清（不阻断推荐方案）

- 假设：用户接受补读 API。若否，只能做 lookup-only，工作台/目录列表降级。
- 假设：补发对人开放 `benefit.remediate`（dev 全开）。
- 不阻断：Casdoor org/client 命名留到二期 provision 脚本。
