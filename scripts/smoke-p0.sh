#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8086}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
COMPOSE=(docker compose --env-file "${ROOT}/scripts/env.local.sh" -f "${ROOT}/dev/local/docker-compose.yml")
RUN_FAULTS=1
RUN_OBSERVABILITY=1

for arg in "$@"; do
  case "${arg}" in
    --no-faults) RUN_FAULTS=0 ;;
    --no-observability) RUN_OBSERVABILITY=0 ;;
    *) echo "未知参数：${arg}" >&2; exit 2 ;;
  esac
done

PASS=0
FAIL=0
GATEWAY_STOPPED=0
ACCOUNT_STOPPED=0
FAKE_ACCOUNT_REGISTERED=0

pass() { PASS=$((PASS + 1)); echo "[PASS] $*"; }
fail() { FAIL=$((FAIL + 1)); echo "[FAIL] $*" >&2; }

cleanup() {
  if [ "${FAKE_ACCOUNT_REGISTERED}" -eq 1 ]; then
    curl -sf -X DELETE 'http://localhost:8848/nacos/v1/ns/instance?serviceName=agent.account&groupName=HUAWEI_FINANCE_AGENT&ip=192.0.2.1&port=9&clusterName=DEFAULT&ephemeral=true' \
      >/dev/null 2>&1 || true
  fi
  if [ "${ACCOUNT_STOPPED}" -eq 1 ]; then
    "${COMPOSE[@]}" start account >/dev/null 2>&1 || true
  fi
  if [ "${GATEWAY_STOPPED}" -eq 1 ]; then
    "${COMPOSE[@]}" start a2a-gateway >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

wait_http() {
  local url="$1" attempts="${2:-60}"
  for _ in $(seq 1 "${attempts}"); do
    if curl -sf "${url}" >/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

wait_agent_status() {
  local agent_id="$1" expected="$2"
  for _ in $(seq 1 30); do
    if curl -sf "${BASE_URL}/internal/agents" | jq -e \
      --arg id "${agent_id}" --arg status "${expected}" \
      '.agents[] | select(.agentId == $id) | .runtimeStatus == $status' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_container_healthy() {
  local service="$1"
  for _ in $(seq 1 45); do
    if "${COMPOSE[@]}" ps --format json "${service}" | jq -e \
      'select(.State == "running" and (.Health == "" or .Health == "healthy"))' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_prometheus_targets() {
  for _ in $(seq 1 45); do
    if curl -sf "${PROMETHEUS_URL}/api/v1/targets" | jq -e '
      [.data.activeTargets[] | select(.labels.job == "agent-platform")] as $apps |
      ($apps | length) == 28 and all($apps[]; .health == "up")
    ' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_grafana_ready() {
  local sources source_health dashboards
  for _ in $(seq 1 45); do
    sources="$(curl -sf "${GRAFANA_URL}/api/datasources" || true)"
    source_health="$(
      for uid in prometheus loki jaeger; do
        curl -sf "${GRAFANA_URL}/api/datasources/uid/${uid}/health" || printf '%s\n' '{"status":"ERROR"}'
      done | jq -s '.' 2>/dev/null || printf '%s' '[]'
    )"
    dashboards="$(curl -sgf --get "${GRAFANA_URL}/api/search" --data-urlencode 'type=dash-db' || true)"
    if curl -sf "${GRAFANA_URL}/api/health" | jq -e '.database == "ok"' >/dev/null && \
      printf '%s' "${sources}" | jq -e '
        [.[].uid] | sort == (["jaeger", "loki", "prometheus"] | sort)' >/dev/null && \
      printf '%s' "${source_health}" | jq -e \
        'length == 3 and all(.[]; .status == "OK")' >/dev/null && \
      printf '%s' "${dashboards}" | jq -e '
        [.[].uid] as $uids |
        ["agent-platform-overview", "agent-platform-a2a", "agent-platform-intent-context", "agent-platform-runtime"] |
        all(.[]; . as $uid | $uids | index($uid) != null)' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_trace_terminal() {
  local trace="$1"
  for _ in $(seq 1 105); do
    if curl -sf "${BASE_URL}/internal/console/recent" | jq -e --arg trace "${trace}" '
      .[] | select(.traceId == $trace) |
      any(.moduleSteps[]?; .module == "a2a-client" and
        (.outcome == "SUCCEEDED" or .outcome == "ERROR" or .outcome == "FAILED"))
    ' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

a2a() {
  local target="$1" capability="$2" parameters="$3" suffix="$4"
  local trace delegation payload
  trace="$(openssl rand -hex 16)"
  delegation="smoke-${suffix}-$(date +%s)-${RANDOM}"
  payload="$(jq -n \
    --arg target "${target}" --arg capability "${capability}" \
    --arg trace "${trace}" --arg delegation "${delegation}" \
    --argjson parameters "${parameters}" \
    '{version:"a2a/2",tenantId:"smoke",sourceAgentId:"agent.mobile-banking-assistant",
      targetAgentId:$target,rootTaskId:("root-"+$delegation),parentTaskId:("parent-"+$delegation),
      sourceTaskId:("source-"+$delegation),delegationId:$delegation,traceId:$trace,
      principal:{principalRef:"principal:smoke",authLevel:"AUTHENTICATED",channel:"TEST",
        sourceSessionRef:("session-"+$delegation)},mode:"TASK",intentPath:"FAST_PATH",
      goal:$capability,capabilityId:$capability,parameters:$parameters,
      confirmedFacts:[{confirmedAt:"smoke",confirmedBy:"smoke-suite"}],
      deadline:"2030-01-01T00:00:00Z",delegationPath:["agent.mobile-banking-assistant"]}')"
  curl -sf --connect-timeout 3 --max-time 15 -H 'Content-Type: application/json' \
    -H "traceparent: 00-${trace}-1111111111111111-01" \
    -X POST "${GATEWAY_URL}/a2a/v2/delegations" --data "${payload}"
}

assert_real_agent() {
  local name="$1" target="$2" capability="$3" parameters="$4"
  local expected_outcome="${5:-SUCCEEDED}" expected_slot="${6:-}" response
  if ! response="$(a2a "${target}" "${capability}" "${parameters}" "${name}")"; then
    fail "真实领域 ${name} 请求失败"
    return
  fi
  if [ "${expected_outcome}" = "NEED_USER" ] && printf '%s' "${response}" | jq -e \
    --arg slot "${expected_slot}" '
      .outcome == "NEED_USER" and .reasonCode == "MISSING_SLOT" and
      (.facts | length == 0) and any(.missingSlots[]?; .slot == $slot)
    ' >/dev/null; then
    pass "真实领域 ${name} 返回 NEED_USER 且结构化缺槽为 ${expected_slot}"
  elif [ "${expected_outcome}" = "SUCCEEDED" ] && printf '%s' "${response}" | jq -e \
    '.outcome == "SUCCEEDED" and (.facts | length > 0)' >/dev/null; then
    pass "真实领域 ${name} 经 Gateway 成功且事实非空"
  else
    fail "真实领域 ${name} 回执不符合契约：$(printf '%s' "${response}" | jq -c \
      '{outcome,reasonCode,facts,missingSlots}')"
  fi
}

assert_scaffold() {
  local target="$1" response
  if ! response="$(a2a "${target}" "cap.smoke.unopened" '{}' "${target#agent.}")"; then
    fail "Scaffold ${target} 请求失败"
    return
  fi
  if printf '%s' "${response}" | jq -e \
    '.outcome == "DOMAIN_NOT_OPEN" and .reasonCode == "DOMAIN_NOT_OPEN" and (.facts | length == 0)' >/dev/null; then
    pass "Scaffold ${target} 显式 DOMAIN_NOT_OPEN"
  else
    fail "Scaffold ${target} 返回了错误语义或假数据"
  fi
}

curl -sf "${BASE_URL}/actuator/health" | jq -e '.status == "UP"' >/dev/null
curl -sf "${GATEWAY_URL}/actuator/health" | jq -e '.status == "UP"' >/dev/null

agents="$(curl -sf "${BASE_URL}/internal/agents")"
if printf '%s' "${agents}" | jq -e '
  .summary.configured == 27 and .summary.online == 27 and
  .summary.unhealthy == 0 and .summary.offline == 0 and
  .summary.implemented == 12 and .summary.scaffold == 15 and
  ([.agents[] | select(.roles | index("domain"))] | length) == 26
' >/dev/null; then
  pass "Agent 清单 27/27 在线，12 个已实现、15 个 Scaffold"
else
  fail "Agent 清单数量或状态不符合 P0 口径"
fi

overview="$(curl -sf "${BASE_URL}/internal/console/overview")"
if printf '%s' "${overview}" | jq -e \
  '.index.documentCount == 42 and .index.vectorsIndexed == true and .index.semanticAvailable == true' >/dev/null; then
  pass "语义索引 42 条且向量通道可用"
else
  fail "语义索引未达到 vectorsIndexed=true / semanticAvailable=true / documentCount=42"
fi

assert_real_agent account agent.account cap.account.balance.query '{}'
assert_real_agent transfer agent.transfer cap.transfer '{"payee":"张三","amount":"100"}'
assert_real_agent creditcard agent.creditcard cap.creditcard.bill.query '{}' NEED_USER cardRef
assert_real_agent wealth agent.wealth_aggregate cap.wealth.holding.query '{}'
assert_real_agent fund agent.fund_service cap.fund.product.query '{}'
assert_real_agent insurance agent.insurance_service cap.insurance.product.query '{}'
assert_real_agent finance agent.finance_assistant cap.nav.account_查询账户余额 '{}'
assert_real_agent deposit agent.deposit_service cap.deposit.product.query '{}'
assert_real_agent loan agent.loan_service cap.loan.product.query '{}'
assert_real_agent payroll agent.payroll_service cap.payroll.status.query '{"principalRef":"principal:smoke"}'
assert_real_agent wealth-product agent.wealth_product cap.wealth-product.product.query '{}'

for target in \
  agent.advisory_service agent.benefits_ops agent.bond_service agent.branch_service \
  agent.channel_settings agent.e_cny agent.enterprise_service agent.fx_service \
  agent.life_service agent.livelihood_service agent.payment agent.personal_info \
  agent.precious_metal agent.security_service agent.vip_service; do
  assert_scaffold "${target}"
done

session="smoke-semantic-$(date +%s)-${RANDOM}"
privacy_marker="context-private-${RANDOM}"
semantic_query="我卡里还剩多少钱，${privacy_marker}"
chat_payload="$(jq -n --arg session "${session}" \
  --arg query "${semantic_query}" \
  '{sessionId:$session,userId:"u-smoke",query:$query,channel:"MOBILE_BANK",page:"home",userState:"LOGGED_IN"}')"
chat="$(curl -sf -H 'Content-Type: application/json' -H 'X-User-ID: u-smoke' \
  -H 'X-Space-ID: smoke' -H 'X-Channel-ID: MOBILE_BANK' \
  -X POST "${BASE_URL}/api/v1/chat" --data "${chat_payload}")"
trace="$(printf '%s' "${chat}" | jq -r '.traceId')"
wait_trace_terminal "${trace}" || fail "语义请求的 A2A 委托未在 deadline 内进入终态"
recent="$(curl -sf "${BASE_URL}/internal/console/recent")"
if printf '%s' "${recent}" | jq -e --arg trace "${trace}" '
  .[] | select(.traceId == $trace) |
  .capabilityId == "cap.account.balance.query" and
  ([.path.topCandidates[] | select(.candidateId == "cap.account.balance.query") | .semantic] | max) > 0 and
  (.gatewayCalls | index("a2a:agent.account")) != null and
  any(.moduleSteps[]?; .module == "a2a-client" and .outcome == "SUCCEEDED") and
  any(.moduleSteps[]?; .module == "task-orchestrator" and
    .outcome == "SUCCESS" and .output.state == "SUCCEEDED")
' >/dev/null && printf '%s' "${chat}" | jq -e '.text | length > 0' >/dev/null; then
  pass "语义表达命中余额能力并由 A2A 返回成功回复 trace=${trace}"
else
  fail "语义表达未同时满足语义分数、余额能力和 A2A 来源"
fi

if [ "${RUN_FAULTS}" -eq 1 ]; then
  "${COMPOSE[@]}" stop a2a-gateway >/dev/null
  GATEWAY_STOPPED=1
  fault_session="smoke-gateway-down-$(date +%s)-${RANDOM}"
  fault_payload="$(jq -n --arg session "${fault_session}" \
    '{sessionId:$session,userId:"u-smoke",query:"查询尾号8821卡的可用余额",channel:"MOBILE_BANK",page:"account",userState:"LOGGED_IN"}')"
  curl -s -H 'Content-Type: application/json' -H 'X-User-ID: u-smoke' \
    -H 'X-Space-ID: smoke' -H 'X-Channel-ID: MOBILE_BANK' \
    -X POST "${BASE_URL}/api/v1/chat" --data "${fault_payload}" >/dev/null || true
  if "${COMPOSE[@]}" logs --since=30s mobile-banking-assistant | grep -q 'A2A_GATEWAY_UNAVAILABLE'; then
    pass "Gateway 停止后返回 A2A_GATEWAY_UNAVAILABLE，未本地回落"
  else
    fail "Gateway 停止故障未记录 A2A_GATEWAY_UNAVAILABLE"
  fi
  "${COMPOSE[@]}" start a2a-gateway >/dev/null
  GATEWAY_STOPPED=0
  wait_http "${GATEWAY_URL}/actuator/health" 60 || fail "Gateway 故障恢复超时"

  "${COMPOSE[@]}" stop account >/dev/null
  ACCOUNT_STOPPED=1
  if wait_agent_status agent.account OFFLINE; then
    pass "account 停止后控制台显示 OFFLINE"
  else
    fail "account 停止后未在发现缓存周期内显示 OFFLINE"
  fi
  curl -sf -X POST \
    'http://localhost:8848/nacos/v1/ns/instance?serviceName=agent.account&groupName=HUAWEI_FINANCE_AGENT&ip=192.0.2.1&port=9&clusterName=DEFAULT&enabled=true&healthy=false&weight=1&ephemeral=true&metadata=%7B%22huawei.finance.agent.id%22%3A%22agent.account%22%2C%22huawei.finance.agent.implementation-mode%22%3A%22extension%22%2C%22huawei.finance.agent.protocol-version%22%3A%22a2a%2F2%22%2C%22huawei.finance.agent.capabilities%22%3A%22agent.account%2Ccap.account.balance.query%22%7D' \
    >/dev/null
  FAKE_ACCOUNT_REGISTERED=1
  if wait_agent_status agent.account UNHEALTHY; then
    pass "Nacos 存在不健康 account 实例时控制台显示 UNHEALTHY"
  else
    fail "Nacos 不健康实例未映射为 UNHEALTHY"
  fi
  unhealthy_receipt="$(a2a agent.account cap.account.balance.query '{}' unhealthy-account || true)"
  if printf '%s' "${unhealthy_receipt}" | jq -e \
    '.outcome == "FATAL" and .reasonCode == "AGENT_ENDPOINT_MISSING"' >/dev/null; then
    pass "不健康 account 不参与 Gateway 派单"
  else
    fail "不健康 account 仍被派单或错误码不明确"
  fi
  curl -sf -X DELETE \
    'http://localhost:8848/nacos/v1/ns/instance?serviceName=agent.account&groupName=HUAWEI_FINANCE_AGENT&ip=192.0.2.1&port=9&clusterName=DEFAULT&ephemeral=true' \
    >/dev/null
  FAKE_ACCOUNT_REGISTERED=0
  "${COMPOSE[@]}" start account >/dev/null
  ACCOUNT_STOPPED=0
  wait_agent_status agent.account ONLINE || fail "account Nacos 恢复超时"
  wait_container_healthy account || fail "account 容器健康恢复超时"
fi

if [ "${RUN_OBSERVABILITY}" -eq 1 ]; then
  if ruby "${ROOT}/scripts/validate-jaeger-trace.rb" --trace-id "${trace}" \
    --jaeger-url "${JAEGER_URL}" --profile full --wait-seconds 60 \
    --privacy-marker "${privacy_marker}" --privacy-marker "${semantic_query}"; then
    pass "Jaeger 必需 Span、父链和隐私字段完整"
  else
    fail "Jaeger Span/父链/隐私验收失败 trace=${trace}"
  fi

  if wait_prometheus_targets; then
    pass "Prometheus 28 个应用 Target 全部 UP"
  else
    fail "Prometheus 应用 Target 未达到 28/28 UP"
  fi

  if curl -sf "${JAEGER_URL}/api/traces/${trace}" | jq -e '
    [.data[0].processes[].serviceName] | unique |
    index("mobile-banking-assistant") != null and
    index("a2a-gateway") != null and index("agent.account") != null
  ' >/dev/null; then
    pass "Jaeger 同一 Trace 包含入口、Gateway 和 account"
  else
    fail "Jaeger 未找到三服务完整 Trace ${trace}"
  fi

  loki_query='{compose_service=~"mobile-banking-assistant|a2a-gateway|account"} | json'
  if curl -sgf "${LOKI_URL}/loki/api/v1/query_range" \
    --data-urlencode "query=${loki_query}" --data-urlencode 'limit=500' | \
    jq -e --arg trace "${trace}" \
      '[.data.result[].values[][1] | fromjson? | select(.traceId == $trace) | .service] | unique | length >= 3' >/dev/null; then
    pass "Loki 可按同一 traceId 关联三服务日志"
  else
    fail "Loki 未关联到三服务 traceId=${trace}"
  fi

  if wait_grafana_ready; then
    pass "Grafana 健康，三数据源连通且四个看板已预置"
  else
    fail "Grafana、数据源连通性或预置看板不健康"
  fi
fi

unhealthy="$("${COMPOSE[@]}" ps --format json | jq -s \
  '[.[] | select(.State != "running" or (.Health != "" and .Health != "healthy"))] | length')"
if [ "${unhealthy}" -eq 0 ]; then
  pass "Compose 容器全部运行且健康"
else
  fail "Compose 仍有 ${unhealthy} 个停止或不健康容器"
fi

echo "[smoke-p0] 通过 ${PASS} 项，失败 ${FAIL} 项"
[ "${FAIL}" -eq 0 ]
