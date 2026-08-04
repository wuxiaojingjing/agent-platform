#!/usr/bin/env bash
# 冒烟：拉起应用，用入口路由基准场景打 REST 入口，逐条核对完整 Decision。
#
# 与单测的分工：单测在进程内验判定，冒烟验的是「打包出来的 jar 能不能跑」——
# 装配缺 Bean、资产路径写错、端口没起来，这些只有真正启动一次才会暴露。
#
# 用法：
#   scripts/smoke.sh              # 自行打包并启动，跑完自动关掉
#   scripts/smoke.sh --keep       # 跑完保留进程，方便手工继续试
#   BASE_URL=http://host:8080 scripts/smoke.sh --no-start   # 打到已在跑的实例

set -euo pipefail

if [ "${1:-}" = "--platform" ]; then
  exec "$(cd "$(dirname "$0")" && pwd)/smoke-p0.sh"
fi

if [ -n "${BASH_SOURCE[0]:-}" ]; then
  SELF="${BASH_SOURCE[0]}"
else
  SELF="$0"
fi
SCRIPT_DIR="$(cd "$(dirname "${SELF}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8080}"
START_APP=1
KEEP_APP=0
APP_PID=""
LOG_FILE="${PROJECT_ROOT}/target/smoke-app.log"

for arg in "$@"; do
  case "${arg}" in
    --no-start) START_APP=0 ;;
    --keep) KEEP_APP=1 ;;
    *) echo "未知参数：${arg}" >&2; exit 2 ;;
  esac
done

PASS=0
FAIL=0

cleanup() {
  if [ -n "${APP_PID}" ] && [ "${KEEP_APP}" -eq 0 ]; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
    echo "[smoke] 已停止应用（日志：${LOG_FILE}）"
  elif [ -n "${APP_PID}" ]; then
    echo "[smoke] 应用继续运行 pid=${APP_PID} 日志=${LOG_FILE}"
  fi
}
trap cleanup EXIT

# 渠道网关会在鉴权之后注入的身份与租户头（FP-65）。不带这些头的请求在入口就被 400 拦掉，
# 所以这里必须模拟出网关的行为，否则冒烟看到的会是一片「请求失败」而非真实出口
TENANT_HEADERS=(
  -H "X-User-ID: u-smoke"
  -H "X-Space-ID: space-smoke"
  -H "X-Channel-ID: MOBILE_BANK"
)

# 用 jq 取字段而不是 grep：出口结构是嵌套 JSON，
# 用文本匹配会在「文案里恰好出现 Decision 名」时给出假阳性
field() {
  printf '%s' "$1" | jq -r "$2"
}

# $1 场景名 $2 会话 $3 用户输入 $4 页面 $5 期望出口 $6 回复里应出现的关键词
scenario() {
  local name="$1" session="$2" query="$3" page="$4" want="$5" keyword="$6"

  local payload response decision reason short_circuit text
  payload=$(jq -n --arg s "${session}" --arg q "${query}" --arg p "${page}" \
    '{sessionId:$s,userId:"u-smoke",query:$q,channel:"MOBILE_BANK",page:$p,userState:""}')

  response=$(curl -sf -X POST "${BASE_URL}/api/v1/chat" \
    -H 'Content-Type: application/json' "${TENANT_HEADERS[@]}" -d "${payload}") || {
    echo "[FAIL] ${name}：请求失败"
    FAIL=$((FAIL + 1))
    return
  }

  decision=$(field "${response}" '.decision.decision')
  reason=$(field "${response}" '.decision.reasonCode')
  short_circuit=$(field "${response}" '.decision.shortCircuit')
  text=$(field "${response}" '.text')

  if [ "${decision}" != "${want}" ]; then
    echo "[FAIL] ${name}：期望 ${want}，实际 ${decision}（${reason}）"
    FAIL=$((FAIL + 1))
    return
  fi
  if [ -n "${keyword}" ] && ! printf '%s' "${text}" | grep -q "${keyword}"; then
    echo "[FAIL] ${name}：回复里没有「${keyword}」，实际为「${text}」"
    FAIL=$((FAIL + 1))
    return
  fi

  PASS=$((PASS + 1))
  printf '[PASS] %-10s %-18s %-22s 短路=%-18s %s\n' \
    "${name}" "${decision}" "${reason}" "${short_circuit}" "${text}"
}

if [ "${START_APP}" -eq 1 ]; then
  echo "[smoke] 检查中间件"
  "${SCRIPT_DIR}/wait-for-infra.sh" 60

  # 清掉上一次冒烟留下的出口缓存。不清的话本次每一条都从一级短路直出，
  # 看着全绿，实际上一次判定都没跑
  docker exec agent-platform-redis sh -c \
    "for pattern in 'huawei-finance-agent:route-decision:v3:*' 'huawei-finance-agent:decision-meta:*' 'huawei-finance-agent:decision:*'; do redis-cli --scan --pattern \"\$pattern\" | xargs -r redis-cli del; done" >/dev/null 2>&1 || true

  # 端口被别的进程占着时必须直接停。否则下面的就绪探测会打到那个进程上、报「就绪」，
  # 而我们启的这个早已因端口冲突退出——结果是一整轮红色断言，指向的却不是被测代码。
  if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[smoke] 端口 8080 已被占用，先停掉它再跑：" >&2
    lsof -nP -iTCP:8080 -sTCP:LISTEN >&2
    exit 1
  fi

  echo "[smoke] 打包"
  (cd "${PROJECT_ROOT}" && mvn -B -q -DskipTests package)

  # 入口 backend artifactId=mobile-banking-assistant；Agent 根在上一级。
  APP_DIR="${PROJECT_ROOT}/agents/mobile-banking-assistant"
  JAR=$(ls "${APP_DIR}"/backend/target/mobile-banking-assistant-*.jar 2>/dev/null | head -1)
  if [ -z "${JAR}" ] || [ ! -f "${JAR}" ]; then
    echo "[smoke] 找不到入口 jar：${APP_DIR}/backend/target/mobile-banking-assistant-*.jar" >&2
    exit 1
  fi
  mkdir -p "${PROJECT_ROOT}/target"
  echo "[smoke] 启动 ${JAR}"
  # AGENT_HOME 指向 Agent 根（含 agent.yaml / assets / frontend）；cwd 同此目录以便
  # optional:file:./application-local.yml 生效。
  (cd "${APP_DIR}" && AGENT_HOME="${APP_DIR}" java -jar "${JAR}" > "${LOG_FILE}" 2>&1) &
  APP_PID=$!

  printf '[smoke] 等待应用就绪 '
  for _ in $(seq 1 60); do
    if curl -sf "${BASE_URL}/actuator/health" | grep -q '"status":"UP"'; then
      printf ' 就绪\n'
      break
    fi
    if ! kill -0 "${APP_PID}" 2>/dev/null; then
      printf ' 进程已退出\n' >&2
      tail -30 "${LOG_FILE}" >&2
      exit 1
    fi
    printf '.'
    sleep 2
  done
fi

curl -sf "${BASE_URL}/actuator/health" >/dev/null || {
  echo "[smoke] ${BASE_URL} 不可达" >&2
  exit 1
}

RUN="smoke-$(date +%s)"
echo
echo "[smoke] ===== 完整入口路由 ====="
scenario "余额查询" "${RUN}-1" "查一下余额"                    "home"     "EXECUTE_CAPABILITY" "可用余额"
scenario "换卡澄清" "${RUN}-2" "换卡"                          "home"     "CLARIFY"           "借记卡"
scenario "条件计划" "${RUN}-3" "查余额，再给老徐转 1000；不足就别转" "home" "STATIC_PLAN"       ""
scenario "额度调整" "${RUN}-4" "帮我把信用卡额度改成 10 万"     "home"     "HANDOFF"           "额度管理"

echo
echo "[smoke] ===== 转账两段式：确认前不得有可执行凭据 ====="
scenario "转账待确认" "${RUN}-5" "给张三转 1000"   "transfer" "EXECUTE_CAPABILITY" "确认"
scenario "转账已确认" "${RUN}-5" "确认执行转账"    "transfer" "RESUME_TASK"        "张三"

echo
echo "[smoke] ===== 一级出口缓存 ====="
scenario "首次持仓" "${RUN}-6" "看看我的理财持仓" "home" "EXECUTE_CAPABILITY" ""
CACHED=$(curl -sf -X POST "${BASE_URL}/api/v1/chat" -H 'Content-Type: application/json' \
  "${TENANT_HEADERS[@]}" \
  -d "$(jq -n --arg s "${RUN}-7" '{sessionId:$s,userId:"u-smoke",query:"看看我的理财持仓",channel:"MOBILE_BANK",page:"home",userState:""}')" \
  | jq -r '.decision.shortCircuit')
if [ "${CACHED}" = "L1_CACHE" ]; then
  PASS=$((PASS + 1))
  echo "[PASS] 二次命中   短路=L1_CACHE"
else
  FAIL=$((FAIL + 1))
  echo "[FAIL] 二次命中：期望 L1_CACHE，实际 ${CACHED}"
fi

echo
echo "[smoke] ===== 观测打点 ====="
METRICS=$(curl -sf "${BASE_URL}/actuator/prometheus" || true)
for metric in agent_arbitration_decision_total agent_fastpath_short_circuit_total agent_fastpath_latency_seconds \
              agent_fastpath_phase_latency_seconds; do
  if printf '%s' "${METRICS}" | grep -q "^${metric}"; then
    PASS=$((PASS + 1))
    echo "[PASS] 指标 ${metric}"
  else
    FAIL=$((FAIL + 1))
    echo "[FAIL] 指标缺失 ${metric}"
  fi
done

# 分段耗时的价值全在标签上：只有指标名在，说明三段合成了一条曲线，
# 「慢在哪一段」还是答不上来，而那正是 FP-62 要解决的问题
for phase in rewrite recall arbitration; do
  if printf '%s' "${METRICS}" | grep -q "agent_fastpath_phase_latency_seconds.*phase=\"${phase}\""; then
    PASS=$((PASS + 1))
    echo "[PASS] 分段 phase=${phase}"
  else
    FAIL=$((FAIL + 1))
    echo "[FAIL] 分段缺失 phase=${phase}"
  fi
done

echo
echo "[smoke] ===== 智能体清单 ====="
AGENTS=$(curl -sf "${BASE_URL}/internal/agents" || true)
if printf '%s' "${AGENTS}" | jq -e '
  .summary.configured == 27 and
  ([.agents[] | select(.roles | index("domain"))] | length) == 26
' >/dev/null 2>&1; then
  PASS=$((PASS + 1))
  echo "[PASS] 清单 configured=$(printf '%s' "${AGENTS}" | jq -r '.summary.configured')" \
       "online=$(printf '%s' "${AGENTS}" | jq -r '.summary.online')"
else
  FAIL=$((FAIL + 1))
  echo "[FAIL] 清单 /internal/agents 未返回 27 个配置身份和 26 个领域 Agent"
fi

echo
echo "[smoke] 通过 ${PASS} 项，失败 ${FAIL} 项"
[ "${FAIL}" -eq 0 ]
