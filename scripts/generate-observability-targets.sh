#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/dev/local/.dist/observability/prometheus-targets.json"
TMP="${OUT}.tmp"
mkdir -p "$(dirname "${OUT}")"

{
  printf 'mobile-banking-assistant\tmobile-banking-assistant:8080\n'
  printf 'a2a-gateway\ta2a-gateway:8086\n'
  for agent_dir in "${ROOT}"/agents/*; do
    service="$(basename "${agent_dir}")"
    if [ "${service}" != "mobile-banking-assistant" ]; then
      printf '%s\t%s:8080\n' "${service}" "${service}"
    fi
  done
} | jq -Rn '[inputs | split("\t") | {targets: [.[1]], labels: {service: .[0]}}]' > "${TMP}"
mv "${TMP}" "${OUT}"
echo "[observability] Prometheus targets: $(jq length "${OUT}")"
