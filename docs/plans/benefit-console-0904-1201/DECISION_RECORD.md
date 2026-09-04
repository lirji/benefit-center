# benefit-console 决策记录

> 日期：2026-09-04
> 状态：待用户批准。获批前不改业务代码。

## 1. 问题

`benefit-center` 是模块化单体 API，门户已把 `http://localhost:8083/` 登记为入口，但仓库没有独立前端。需要前后端分离，并提供可运营的独立 SPA。

## 2. 备选方案

| 方案 | 做法 | 取 | 舍 |
|---|---|---|---|
| **A. recon 同源运营台** | React18 + Vite5 + antd5 + React Query；nginx 反代 API；`BENEFIT_UI_PORT` 改绑 console | 与对账/流程台一致；门户 healthz 契约现成；Casdoor 可后接 | 要拆端口、补最小读 API |
| B. Lookup-only 纯前端 | 只调现有 GET/PUT/POST，列表用 localStorage | 后端几乎不动 | 目录/库存/工作台无法运营，只是 API 表单壳 |
| C. BFF | 独立网关聚合读模型 | OpenAPI 保持 integration-only | 多一个长期服务，一期过重 |

## 3. 推荐

**选 A，一期 UI 范围按运营配置 + 点查/处置，不承担发放。**

理由：

1. 用户要的是独立前端服务，不是落地页升级。B 做出来仍像调试器。
2. 现有 OpenAPI **没有 list**。目录、库存、工作台要可用，必须补租户内只读查询；表已在、索引可复用，不必上 BFF。
3. Drools 才是 AwardIntent 生产者。控制台不提供「手工发奖」。
4. 视觉跟落地页青绿，骨架跟 `recon-console`，避免再造一套设计系统。

## 4. 已裁定（布局由实现方决定，供批准时确认）

| 决策 | 选择 | 依据 |
|---|---|---|
| 技术栈 | 抄 recon-console（antd5 / RR6 / React Query / axios / pnpm） | 同级控制台主线 |
| 视觉 | 浅色运营台 + `#0F6F6A`，不用对账蓝 `#315EFB` | 落地页已有品牌色 |
| 导航 | 工作台 → 发放订单 → 补发处置 → 库存中心 → 权益目录 | 对齐 runbook 频率 |
| 发放 | 不做 AwardIntent 表单 | 架构文档 R7 |
| 鉴权一期 | `VITE_AUTH_MODE=dev` + `GET /admin/v1/console/me`；OIDC 文件可先抄好但不作为上线门槛 | 与 recon 首期、当前 compose `dev-mode=true` 一致 |
| 端口 | `BENEFIT_UI_PORT=8083` → nginx console；API 改 `127.0.0.1:8183:8083` | 门户地址不变，对齐 recon `8180/8088` |
| 落地页 | Java `GET /` 删除，由 SPA 接管；门户 `/healthz` 打 console nginx；API 容器探活继续用 actuator | 对齐 recon |
| 移动端 | 桌面主路径；`<992` Drawer 菜单、`<768` 表格改卡片；手机不做完整配置 | 抄 recon，不另起 H5 |

## 5. 待用户确认的假设

1. 接受新增 **tenant 作用域** 的 console 读 API（见 FINAL_PLAN §API），不返回兑换码密文。
2. 一期不接 Casdoor 登录页；生产 catalog 继续 `coming-soon`。
3. 补发主路径仍是 recon；console 提供点查、从订单预填、应急 execute。
4. 兑换码导入只提交 hash + cipherText，不做浏览器侧加密。

## 6. 否决项

- 不在控制台提交 AwardIntent。
- 不上 Node BFF。
- 不抄 risk-console 深色指挥台风。
- 不把 Prometheus/Grafana 嵌进一期工作台。
