#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 统一加载 JDK、Maven 镜像与本机模型密钥；密钥文件已被 Git 忽略。
# shellcheck disable=SC1091
source "${ROOT}/scripts/env.sh"
COMPOSE=(docker compose -f "${ROOT}/dev/local/docker-compose.yml")
ACTION="${1:-full}"

CORE_SERVICES=(postgres redis opensearch nacos jaeger a2a-gateway)
for agent_dir in "${ROOT}"/agents/*; do
  CORE_SERVICES+=("$(basename "${agent_dir}")")
done

case "${ACTION}" in
  full)
    "${ROOT}/scripts/build-local-dist.sh"
    "${COMPOSE[@]}" up -d --build
    printf '[run-local] 等待手机银行助手 '
    for _ in $(seq 1 90); do
      if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
        printf ' 就绪\n'
        echo "[run-local] 控制台 http://localhost:8080/console/"
        echo "[run-local] A2A Gateway http://localhost:8086/actuator/health"
        echo "[run-local] Grafana http://localhost:3000/"
        echo "[run-local] Jaeger http://localhost:16686/"
        echo "[run-local] Prometheus http://localhost:9090/"
        exit 0
      fi
      printf '.'
      sleep 2
    done
    printf ' 超时\n' >&2
    "${COMPOSE[@]}" ps >&2
    exit 1
    ;;
  core)
    "${ROOT}/scripts/build-local-dist.sh"
    "${COMPOSE[@]}" up -d --build "${CORE_SERVICES[@]}"
    echo "[run-local] Core 环境已启动（含 Jaeger，不含 Prometheus/Grafana/Loki/Alloy）"
    ;;
  infra)
    "${COMPOSE[@]}" up -d postgres redis opensearch nacos jaeger
    ;;
  down)
    "${COMPOSE[@]}" down
    ;;
  status)
    "${COMPOSE[@]}" ps
    ;;
  *)
    echo "用法：$0 [full|core|infra|down|status]" >&2
    exit 2
    ;;
esac
