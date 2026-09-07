# dev-infra 接入

Benefit Center 的本地数据库、缓存和消息中间件由同级 `dev-infra` 项目统一管理。项目 Compose 只运行应用，并仅加入已有的 `dev-infra` 外部网络，不再创建项目网络、MySQL、Redis 或 Redpanda 容器。

## 资源清单

| 资源 | 宿主机连接 | Compose 内连接 |
|---|---|---|
| MySQL 8.4 | `127.0.0.1:43306/benefit_center` | `infra-mysql84:3306/benefit_center` |
| Redis 7 | `127.0.0.1:46379` | `infra-redis7:6379` |
| Kafka 3.8 | `127.0.0.1:49092` | `infra-kafka38:9092` |

数据库使用项目独立账号 `benefit` 和 `utf8mb4_bin` 排序规则。密码只保存在已忽略的 `deploy/.env`；共享 MySQL root 密码只保存在 `dev-infra/.env`。

Kafka 自动创建 Topic 已关闭，初始化脚本会幂等创建三个分区、单副本的本地开发 Topic：

- `benefit.award-intent.v1`
- `benefit.fulfillment-event.v1`
- `benefit.remediation.command.v1`
- `benefit.remediation.result.v1`
- `benefit.sku-template.v1`

## 初始化与启动

```bash
cp deploy/.env.example deploy/.env
# 修改 BENEFIT_DB_PASSWORD，并令 BENEFIT_REDIS_PASSWORD 与 dev-infra/.env 的 REDIS7_PASSWORD 一致。
bash deploy/bootstrap-dev-infra.sh
bash deploy/compose.sh up --build --wait
```

`bootstrap-dev-infra.sh` 会启动共享 MySQL/Redis/Kafka、创建或校准项目数据库账号，并创建 Topic。应用启动时由 Flyway 创建或升级业务表。

宿主机直接启动 Java 服务时，至少设置：

```bash
export BENEFIT_DB_USER=benefit
export BENEFIT_DB_PASSWORD='<deploy/.env 中的值>'
export BENEFIT_REDIS_PASSWORD='<dev-infra/.env 的 REDIS7_PASSWORD>'
mvn -pl benefit-server -am spring-boot:run
```

数据库、Redis 和 Kafka 地址已经在 `application.yml` 中默认指向共享服务的宿主端口。

## 验证

```bash
curl --fail http://127.0.0.1:8083/healthz
curl --fail http://127.0.0.1:8183/actuator/health/readiness
bash deploy/compose.sh ps
cd ../dev-infra && ./bin/dev-infra status
```

数据库应存在 22 张带中文表注释的 `bc_*` 业务表以及 `flyway_schema_history`，Schema 版本为 V12。共享 Kafka 应存在上述五个 Topic。浏览器入口是 `console`（`${BENEFIT_UI_PORT:-8083}`），Java API 只发布到 `127.0.0.1:8183`。

## 数据迁移与回滚

本次按空库初始化：原项目 Compose 未运行，预期的 `deploy_benefit_mysql` 卷不存在；原 Redpanda 也未配置持久化卷，因此没有历史数据库记录或 Kafka 消息可导入。

回滚时可以恢复旧版 Compose 配置并重新创建项目私有服务。现有 Docker 卷不会由初始化或启动脚本删除；不要使用 `docker compose down -v` 或删除 `dev-infra` 数据卷。

## 统一链路追踪

```bash
cd ../dev-infra && make marketing-obs
cd ../benefit-center
bash deploy/compose.sh -f docker-compose.yml -f compose.observability.yml up -d --build
```

观测 overlay 为 `server` 注入共享 OpenTelemetry Java Agent，服务名固定为 `benefit-center`，OTLP HTTP 发送到共享 Collector。Grafana 地址为 `http://127.0.0.1:3001`；在 Tempo 中按服务名、HTTP route、错误状态或 traceId 查询。默认本地全采样，可在 `deploy/.env` 改为 `OTEL_TRACES_SAMPLER=traceidratio`、`OTEL_TRACES_SAMPLER_ARG=0.1`，完全停用则设置 `OTEL_SDK_DISABLED=true`。AwardIntent/outbox 等延迟投递会开始新 trace，业务 `traceId/sourceRequestId` 应同时用于日志和审计检索。完整规则见同级 `dev-infra/docs/observability.md`。
