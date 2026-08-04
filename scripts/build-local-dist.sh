#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${ROOT}/dev/local/.dist"

"${ROOT}/scripts/generate-observability-targets.sh"

cd "${ROOT}"
npm --prefix agents/mobile-banking-assistant/frontend run build
mvn -DskipTests package

mkdir -p "${DIST}/extensions"

atomic_copy_jar() {
  local source="$1" target="$2" temporary
  temporary="${target}.tmp.$$"
  jar tf "${source}" >/dev/null
  cp "${source}" "${temporary}"
  mv -f "${temporary}" "${target}"
}

# .dist 目录被运行中的容器只读 bind mount。直接 cp 会先截断目标文件，
# 旧 JVM 随后按需读取嵌套依赖时会随机 NoClassDefFoundError；同目录 mv 保留旧 inode，
# 让运行进程继续读旧归档，下一次容器启动再读取新归档。
atomic_copy_jar framework/host/agent-host-app/target/agent-host-app-0.1.0-SNAPSHOT.jar \
  "${DIST}/agent-host-app.jar"
atomic_copy_jar infrastructure/a2a/a2a-gateway-app/target/a2a-gateway-app-0.1.0-SNAPSHOT.jar \
  "${DIST}/a2a-gateway-app.jar"
atomic_copy_jar agents/mobile-banking-assistant/backend/target/mobile-banking-assistant-0.1.0-SNAPSHOT.jar \
  "${DIST}/mobile-banking-assistant.jar"
atomic_copy_jar samples/agents/banking-systems-simulator/target/banking-systems-simulator-0.1.0-SNAPSHOT.jar \
  "${DIST}/banking-systems-simulator.jar"

for agent in account transfer creditcard wealth-aggregate fund-service insurance-service finance-assistant \
  deposit-service loan-service payroll-service wealth-product; do
  atomic_copy_jar "agents/${agent}/backend/target/${agent}-0.1.0-SNAPSHOT.jar" \
    "${DIST}/extensions/${agent}.jar"
done

echo "[build-local-dist] 已生成 ${DIST}"
