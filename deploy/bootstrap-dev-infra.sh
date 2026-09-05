#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEV_INFRA_DIR="${DEV_INFRA_DIR:-${PROJECT_DIR}/../dev-infra}"
DEV_INFRA_ENV_FILE="${DEV_INFRA_ENV_FILE:-${DEV_INFRA_DIR}/.env}"
PROJECT_ENV_FILE="${BENEFIT_ENV_FILE:-${SCRIPT_DIR}/.env}"
DEV_INFRA_COMPOSE_FILE="${DEV_INFRA_DIR}/compose.yaml"

for required_file in "${DEV_INFRA_ENV_FILE}" "${PROJECT_ENV_FILE}" "${DEV_INFRA_COMPOSE_FILE}"; do
  if [[ ! -r "${required_file}" ]]; then
    echo "Required file is missing or unreadable: ${required_file}" >&2
    exit 1
  fi
done

set -a
# shellcheck disable=SC1090
source "${DEV_INFRA_ENV_FILE}"
# shellcheck disable=SC1090
source "${PROJECT_ENV_FILE}"
set +a

BENEFIT_DB_NAME="${BENEFIT_DB_NAME:-benefit_center}"
if [[ "${BENEFIT_REDIS_PASSWORD:-}" != "${REDIS7_PASSWORD:-}" ]]; then
  echo "BENEFIT_REDIS_PASSWORD must match REDIS7_PASSWORD from dev-infra/.env." >&2
  exit 1
fi
for value in "${BENEFIT_DB_NAME}" "${BENEFIT_DB_USER}" "${BENEFIT_DB_PASSWORD}"; do
  if [[ ! "${value}" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "Database name, user and password may only contain letters, digits, _ and -." >&2
    exit 1
  fi
done

export DEV_INFRA_ENV_FILE
"${DEV_INFRA_DIR}/bin/dev-infra" up mysql84 redis7 kafka38

compose=(docker compose --env-file "${DEV_INFRA_ENV_FILE}" -f "${DEV_INFRA_COMPOSE_FILE}")
"${compose[@]}" up -d --wait --wait-timeout 120 mysql84 redis7 kafka38

"${compose[@]}" exec -T -e MYSQL_PWD="${MYSQL84_ROOT_PASSWORD}" mysql84 \
  mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${BENEFIT_DB_NAME}\`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS '${BENEFIT_DB_USER}'@'%' IDENTIFIED BY '${BENEFIT_DB_PASSWORD}';
ALTER USER '${BENEFIT_DB_USER}'@'%' IDENTIFIED BY '${BENEFIT_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${BENEFIT_DB_NAME}\`.* TO '${BENEFIT_DB_USER}'@'%';
FLUSH PRIVILEGES;
SQL

topics=(
  benefit.award-intent.v1
  benefit.fulfillment-event.v1
  benefit.remediation.command.v1
  benefit.remediation.result.v1
  benefit.sku-template.v1
)
for topic in "${topics[@]}"; do
  "${compose[@]}" exec -T kafka38 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server infra-kafka38:9092 \
    --create --if-not-exists --topic "${topic}" --partitions 3 --replication-factor 1
done

"${compose[@]}" exec -T -e MYSQL_PWD="${BENEFIT_DB_PASSWORD}" mysql84 \
  mysql --protocol=tcp -h 127.0.0.1 -u"${BENEFIT_DB_USER}" -NBe \
  "SELECT CONCAT('database=', DATABASE(), ', collation=', DEFAULT_COLLATION_NAME) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='${BENEFIT_DB_NAME}'" \
  "${BENEFIT_DB_NAME}"
"${compose[@]}" exec -T kafka38 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server infra-kafka38:9092 --list | grep '^benefit\.' | sort

echo "Benefit Center dev-infra resources are ready."
