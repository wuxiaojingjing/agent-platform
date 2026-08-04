#!/usr/bin/env bash
# 等三个中间件真正可用。docker compose 的 "started" 不等于 "ready"，
# 直接跑集成测试会得到误导性的连接失败。

set -euo pipefail

TIMEOUT="${1:-120}"
DEADLINE=$(( $(date +%s) + TIMEOUT ))

wait_for() {
  local name="$1" probe="$2"
  printf '[infra] 等待 %s ' "${name}"
  while true; do
    if eval "${probe}" >/dev/null 2>&1; then
      printf ' 就绪\n'
      return 0
    fi
    if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
      printf ' 超时\n' >&2
      return 1
    fi
    printf '.'
    sleep 2
  done
}

wait_for "OpenSearch" "curl -sf http://localhost:9200/_cluster/health | grep -qE '\"status\":\"(green|yellow)\"'"
wait_for "Redis"      "docker exec agent-platform-redis redis-cli ping | grep -q PONG"
wait_for "Postgres"   "docker exec agent-platform-postgres pg_isready -U agent_platform -d agent_platform"

echo "[infra] 三个中间件全部就绪"
curl -s http://localhost:9200 | python3 -c "import sys,json;d=json.load(sys.stdin);print('[infra] OpenSearch',d['version']['distribution'],d['version']['number'])" 2>/dev/null || true
