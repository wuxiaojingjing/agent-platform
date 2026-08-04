#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "${ROOT}/.." && pwd)"

echo "[git-preflight] migration groups"
for group in framework infrastructure agents samples docs scripts dev; do
  count="$(find "${ROOT}/${group}" -type f 2>/dev/null | wc -l | tr -d ' ')"
  printf '  %-16s %s files\n' "${group}" "${count}"
done

staged="$(git -C "${REPO}" diff --cached --name-only | wc -l | tr -d ' ')"
echo "[git-preflight] staged files: ${staged} (本脚本不会暂存或提交)"

secret_files="$(rg -l --hidden --glob '!**/target/**' --glob '!**/node_modules/**' \
  --glob '!scripts/env.local.sh' --glob '!**/application-local.yml' \
  '(api[_-]?key|secret|token)[[:space:]]*[:=][[:space:]]*[^$[:space:]][^[:space:]]+' \
  "${ROOT}" 2>/dev/null | wc -l | tr -d ' ')"

if [ "${secret_files}" -gt 0 ]; then
  echo "[git-preflight] BLOCKED: ${secret_files} tracked-candidate files contain secret-like assignments"
  exit 2
fi

echo "[git-preflight] secret scan: no tracked-candidate finding"
