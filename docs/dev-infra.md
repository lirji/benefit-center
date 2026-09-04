# dev-infra 接入

Benefit Center 的本地数据库和消息中间件由同级 `dev-infra` 项目统一管理。项目 Compose 只运行应用，并仅加入已有的 `dev-infra` 外部网络，不再创建项目网络、MySQL 或 Redpanda 容器。

## 资源清单

| 资源 | 宿主机连接 | Compose 内连接 |
|---|---|---|
| MySQL 8.4 | `127.0.0.1:43306/benefit_center` | `infra-mysql84:3306/benefit_center` |
| Kafka 3.8 | `127.0.0.1:49092` | `infra-kafka38:9092` |

数据库使用项目独立账号 `benefit` 和 `utf8mb4_bin` 排序规则。密码只保存在已忽略的 `deploy/.env`；共享 MySQL root 密码只保存在 `dev-infra/.env`。

Kafka 自动创建 Topic 已关闭，初始化脚本会幂等创建三个分区、单副本的本地开发 Topic：

- `benefit.award-intent.v1`
- `benefit.fulfillment-event.v1`
- `benefit.remediation.command.v1`
- `benefit.remediation.result.v1`

## 初始化与启动

```bash
cp deploy/.env.example deploy/.env
# 修改 BENEFIT_DB_PASSWORD；首次创建数据库后不要随意更换。
bash deploy/bootstrap-dev-infra.sh
bash deploy/compose.sh up --build --wait
```

`bootstrap-dev-infra.sh` 会启动共享 MySQL/Kafka、创建或校准项目数据库账号，并创建 Topic。应用启动时由 Flyway 创建或升级业务表。

宿主机直接启动 Java 服务时，至少设置：

```bash
export BENEFIT_DB_USER=benefit
export BENEFIT_DB_PASSWORD='<deploy/.env 中的值>'
mvn -pl benefit-server -am spring-boot:run
```

数据库和 Kafka 地址已经在 `application.yml` 中默认指向共享服务的宿主端口。

## 验证

```bash
curl --fail http://127.0.0.1:8083/healthz
curl --fail http://127.0.0.1:8183/actuator/health/readiness
bash deploy/compose.sh ps
cd ../dev-infra && ./bin/dev-infra status
```

数据库应存在 16 张带中文表注释的 `bc_*` 业务表以及 `flyway_schema_history`，Schema 版本为 V10。共享 Kafka 应存在上述四个 Topic。浏览器入口是 `console`（`${BENEFIT_UI_PORT:-8083}`），Java API 只发布到 `127.0.0.1:8183`。

## 数据迁移与回滚

本次按空库初始化：原项目 Compose 未运行，预期的 `deploy_benefit_mysql` 卷不存在；原 Redpanda 也未配置持久化卷，因此没有历史数据库记录或 Kafka 消息可导入。

回滚时可以恢复旧版 Compose 配置并重新创建项目私有服务。现有 Docker 卷不会由初始化或启动脚本删除；不要使用 `docker compose down -v` 或删除 `dev-infra` 数据卷。
