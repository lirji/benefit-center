#!/usr/bin/env bash
# Benefit Compose 统一入口：加载项目私有配置，并兼容 auth-platform 中央门户端口。
# 加 --secure 叠加 compose.secure.yml（Casdoor JWT + 运营台 OIDC）。

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_PORTS_LOADER="${PLATFORM_PORTS_LOADER:-${SCRIPT_DIR}/../../auth-platform/deploy/load-platform-ports.sh}"
ENV_ARGS=()
if [[ -r "${SCRIPT_DIR}/.env" ]]; then
  ENV_ARGS+=(--env-file "${SCRIPT_DIR}/.env")
fi
if [[ -r "${PLATFORM_PORTS_LOADER}" ]]; then
  # shellcheck source=/dev/null
  . "${PLATFORM_PORTS_LOADER}"
  ENV_ARGS+=(--env-file "${PLATFORM_PORTS_FILE}")
fi
export BENEFIT_UI_PORT="${BENEFIT_UI_PORT:-8083}"
cd "${SCRIPT_DIR}"

SECURE=0
COMPOSE_ARGS=()
for arg in "$@"; do
  if [[ "${arg}" == "--secure" ]]; then
    SECURE=1
  else
    COMPOSE_ARGS+=("${arg}")
  fi
done
if [[ "${SECURE}" -eq 1 ]]; then
  exec docker compose "${ENV_ARGS[@]}" -f docker-compose.yml -f compose.secure.yml "${COMPOSE_ARGS[@]}"
fi
exec docker compose "${ENV_ARGS[@]}" "${COMPOSE_ARGS[@]}"
