#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
COMPOSE=(docker compose --env-file "${ROOT}/scripts/env.local.sh" -f "${ROOT}/dev/local/docker-compose.yml")
MOBILE_OVERRIDDEN=0
PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "[PASS] $*"; }
fail() { FAIL=$((FAIL + 1)); echo "[FAIL] $*" >&2; }

psql() {
  "${COMPOSE[@]}" exec -T postgres psql -U agent_platform -d agent_platform -Atc "$1"
}

wait_sql() {
  local sql="$1" expected="$2"
  for _ in $(seq 1 60); do
    if [ "$(psql "${sql}" 2>/dev/null || true)" = "${expected}" ]; then return 0; fi
    sleep 2
  done
  return 1
}

wait_http() {
  for _ in $(seq 1 90); do
    if curl -sf "${BASE_URL}/actuator/health" >/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

wait_multihop_trace() {
  local trace="$1"
  for _ in $(seq 1 60); do
    if curl -sf "${JAEGER_URL}/api/traces/${trace}" | jq -e '
      .data[0] as $trace |
      ([ $trace.processes[].serviceName ] | unique) as $services |
      ($services | index("mobile-banking-assistant")) != null and
      ($services | index("a2a-gateway")) != null and
      ($services | index("agent.finance_assistant")) != null and
      ($services | index("agent.fund_service")) != null and
      ([ $trace.spans[] | select(.operationName == "agent.a2a.gateway.route") ] | length) >= 2
    ' >/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

wait_multihop_logs() {
  local trace="$1" query response
  query='{compose_service=~"mobile-banking-assistant|a2a-gateway|finance-assistant|fund-service"} | json'
  for _ in $(seq 1 60); do
    response="$(curl -sgf "${LOKI_URL}/loki/api/v1/query_range" \
      --data-urlencode "query=${query}" --data-urlencode 'limit=1000' || true)"
    if printf '%s' "${response}" | jq -e --arg trace "${trace}" '
      [.data.result[].values[][1] | fromjson? |
        select(.traceId == $trace) | .service] | unique | length >= 4
    ' >/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

wait_direct_observation() {
  local trace="$1" query jaeger loki
  query='{compose_service="account"} | json'
  for _ in $(seq 1 60); do
    jaeger="$(curl -sf "${JAEGER_URL}/api/traces/${trace}" || true)"
    loki="$(curl -sgf "${LOKI_URL}/loki/api/v1/query_range" \
      --data-urlencode "query=${query}" --data-urlencode 'limit=200' || true)"
    if printf '%s' "${jaeger}" | jq -e '.data | length > 0' >/dev/null && \
       printf '%s' "${loki}" | jq -e --arg trace "${trace}" '
         any(.data.result[].values[][1] | fromjson?; .traceId == $trace)
       ' >/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

restore_mobile() {
  if [ "${MOBILE_OVERRIDDEN}" -eq 1 ]; then
    "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 180 \
      mobile-banking-assistant >/dev/null 2>&1 || true
  fi
}
trap restore_mobile EXIT INT TERM

chat() {
  local session="$1" query="$2" page="$3"
  jq -n --arg session "${session}" --arg query "${query}" --arg page "${page}" \
    '{sessionId:$session,userId:"p1-user",query:$query,channel:"MOBILE_BANK",page:$page,userState:"LOGGED_IN"}' |
    curl -sf -H 'Content-Type: application/json' -H 'X-User-ID: p1-user' \
      -H 'X-Space-ID: p1' -H 'X-Channel-ID: MOBILE_BANK' \
      -X POST "${BASE_URL}/api/v1/chat" --data-binary @-
}

direct_account() {
  local payload="$1" trace="$2"
  "${COMPOSE[@]}" exec -T -e "P1_PAYLOAD=${payload}" -e "P1_TRACE=${trace}" a2a-gateway sh -c \
    'wget -qO- --header="Content-Type: application/json" --header="traceparent: 00-$P1_TRACE-1111111111111111-01" --post-data="$P1_PAYLOAD" http://account:8080/a2a/v2/inbound'
}

curl -sf "${BASE_URL}/actuator/health" >/dev/null

session="p1-hop-$(date +%s)-${RANDOM}"
response="$(chat "${session}" '请金融助手帮我查询基金产品C' 'finance-center')"
trace="$(printf '%s' "${response}" | jq -r '.traceId')"
if printf '%s' "${response}" | jq -e '
  .usedTemplate == "tpl.product.result" and .fellBack == false and
  (.text | contains("基金产品C"))
' >/dev/null; then
  pass "多层协同按基金叶子能力渲染成功事实"
else
  fail "多层协同成功但面客回复仍是兜底：$(printf '%s' "${response}" | jq -c '{usedTemplate,fellBack,text}')"
fi
if wait_sql "select count(*) from agent_delegation where trace_id='${trace}'" 2; then
  pass "多层协同产生两次 A2A 委托"
else
  fail "多层协同未形成两次委托 trace=${trace}"
fi

hops="$(psql "select source_agent_id||'>'||target_agent_id||':'||mode from agent_delegation where trace_id='${trace}' order by created_at")"
if [ "${hops}" = $'agent.mobile-banking-assistant>agent.finance_assistant:GOAL\nagent.finance_assistant>agent.fund_service:TASK' ]; then
  pass "手机银行 GOAL → 金融助手，金融助手 TASK → 基金助手"
else
  fail "多层路由不符合预期：${hops}"
fi

roots="$(psql "select count(distinct root_task_id) from agent_delegation where trace_id='${trace}'")"
tasks="$(psql "select count(*) from agent_task where trace_id='${trace}'")"
if [ "${roots}" = 1 ] && [ "${tasks}" -ge 3 ]; then
  pass "两跳保持同一 rootTaskId，并建立三层独立任务"
else
  fail "任务血缘不完整 roots=${roots} tasks=${tasks}"
fi

if wait_multihop_trace "${trace}"; then
  pass "Jaeger 同一 Trace 包含三个 Agent 和两次 Gateway 路由"
else
  fail "Jaeger 多跳 Trace 不完整 trace=${trace}"
fi
if ruby "${ROOT}/scripts/validate-jaeger-trace.rb" --trace-id "${trace}" \
  --jaeger-url "${JAEGER_URL}" --profile deterministic-a2a --wait-seconds 60; then
  pass "多跳 Trace 必需 Span、父链和隐私字段完整"
else
  fail "多跳 Trace Span/父链/隐私验收失败 trace=${trace}"
fi
if wait_multihop_logs "${trace}"; then
  pass "Loki 可按同一 traceId 关联四个进程"
else
  fail "Loki 未关联完整多跳日志 trace=${trace}"
fi

marker="principal-p1-${RANDOM}-private"
delegation="p1-replay-$(date +%s)-${RANDOM}"
direct_trace="$(openssl rand -hex 16)"
payload="$(jq -n --arg delegation "${delegation}" --arg trace "${direct_trace}" --arg marker "${marker}" \
  '{version:"a2a/2",tenantId:"p1",sourceAgentId:"agent.mobile-banking-assistant",
    targetAgentId:"agent.account",rootTaskId:("root-"+$delegation),parentTaskId:("parent-"+$delegation),
    sourceTaskId:("source-"+$delegation),delegationId:$delegation,traceId:$trace,
    principal:{principalRef:$marker,authLevel:"STRONG",channel:"TEST",sourceSessionRef:("session-"+$marker)},
    mode:"TASK",intentPath:"FAST_PATH",goal:"查询余额",capabilityId:"cap.account.balance.query",
    parameters:{},confirmedFacts:[],deadline:"2030-01-01T00:00:00Z",
    delegationPath:["agent.mobile-banking-assistant"]}')"
one="$(mktemp)"
two="$(mktemp)"
direct_account "${payload}" "${direct_trace}" >"${one}" & pid1=$!
direct_account "${payload}" "${direct_trace}" >"${two}" & pid2=$!
wait "${pid1}" || true
wait "${pid2}" || true
task_count="$(psql "select count(*) from agent_task where agent_id='agent.account' and invocation_origin='A2A' and source_invocation_id='${delegation}'")"
task_ids="$(jq -r '.facts.targetTaskId // empty' "${one}" "${two}" | sort -u | wc -l | tr -d ' ')"
if [ "${task_count}" = 1 ] && [ "${task_ids}" = 1 ]; then
  pass "并发重复信封只建立一个目标任务并返回相同 taskId"
else
  fail "目标 Runtime 重放失败 taskCount=${task_count} uniqueTaskIds=${task_ids}"
fi
if ! wait_direct_observation "${direct_trace}"; then
  fail "隐私断言前未在 Jaeger/Loki 观察到目标 Trace ${direct_trace}"
else
  privacy_query="{compose_service=~\"account|a2a-gateway\"} |= \"${marker}\""
  privacy_loki="$(curl -sgf "${LOKI_URL}/loki/api/v1/query_range" \
    --data-urlencode "query=${privacy_query}" --data-urlencode 'limit=20' || printf '%s' '{}')"
  if ! grep -q "${marker}" "${one}" "${two}" && \
     ! "${COMPOSE[@]}" logs --since=5m account a2a-gateway | grep -q "${marker}" && \
     ! curl -sf "${JAEGER_URL}/api/traces/${direct_trace}" | grep -q "${marker}" && \
     printf '%s' "${privacy_loki}" | jq -e '([.data.result[]?] | length) == 0' >/dev/null && \
     ! "${COMPOSE[@]}" exec -T account wget -qO- http://localhost:8080/actuator/prometheus | grep -q "${marker}"; then
    pass "主体引用未进入回执、日志、Trace、指标或 Loki"
  else
    fail "主体引用发生泄露"
  fi
fi
rm -f "${one}" "${two}"

env SLOWPATH_MAX_AUTO_STEPS=1 \
  "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 180 \
  mobile-banking-assistant >/dev/null
MOBILE_OVERRIDDEN=1
wait_http || fail "单步 Slow Path 容器启动超时"

slow_session="p1-slow-$(date +%s)-${RANDOM}"
first_slow="$(chat "${slow_session}" '查一下余额，然后查询基金产品C' 'home')"
plan_id="$(psql "select plan_id from agent_intent_plan where agent_id='agent.mobile-banking-assistant' and session_id='${slow_session}' order by created_at desc limit 1")"
plan_capabilities="$(psql "select string_agg(item->>'capabilityId', '>' order by ordinality) from agent_intent_plan, jsonb_array_elements(items) with ordinality as step(item, ordinality) where plan_id='${plan_id}'")"
plan_navigation="$(psql "select count(*) from agent_intent_plan, jsonb_array_elements(items) as step(item) where plan_id='${plan_id}' and item->>'capabilityId' like 'cap.nav.%'")"
if [ "${plan_capabilities}" = 'cap.account.balance.query>cap.fund.product.query' ] && \
   [ "${plan_navigation}" = 0 ]; then
  pass "Planner 开启时规则锚定保持余额 → 基金，且导航能力未混入"
else
  fail "Slow Path 候选治理失效 capabilities=${plan_capabilities} nav=${plan_navigation}"
fi
if [ -n "${plan_id}" ] && wait_sql "select cursor from agent_intent_plan where plan_id='${plan_id}'" 1 && \
   [ "$(psql "select count(*) from agent_intent_plan_step where plan_id='${plan_id}'")" = 1 ] && \
   printf '%s' "${first_slow}" | jq -e '
     .usedTemplate == "tpl.plan.progress" and .fellBack == false and
     (.text | contains("12,845.60")) and (.text | contains("基金产品查询尚未完成"))
   ' >/dev/null; then
  pass "Slow Path 第一步事实已持久化并按进度模板回复"
else
  fail "Slow Path 第一步持久化或面客回复不正确：$(printf '%s' "${first_slow}" | jq -c '{usedTemplate,fellBack,text}')"
fi

"${COMPOSE[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 180 \
  mobile-banking-assistant >/dev/null
MOBILE_OVERRIDDEN=0
wait_http || fail "Slow Path 恢复容器启动超时"
continued="$(chat "${slow_session}" '继续' 'home')"
step_count="$(psql "select count(*) from agent_intent_plan_step where plan_id='${plan_id}'")"
plan_state="$(psql "select state from agent_intent_plan where plan_id='${plan_id}'")"
first_replays="$(psql "select count(*) from agent_task where agent_id='agent.mobile-banking-assistant' and source_invocation_id='${plan_id}:0:0'")"
if [ "${step_count}" = 2 ] && [ "${plan_state}" = COMPLETED ] && [ "${first_replays}" = 1 ] && \
   printf '%s' "${continued}" | jq -e '
     .usedTemplate == "tpl.plan.result" and .fellBack == false and
     (.text | contains("12,845.60")) and (.text | contains("基金产品C")) and
     (.text | contains("R3")) and (.text | contains("3.2%"))
   ' >/dev/null; then
  pass "容器重启后续办第二步，历史步骤未重放且聚合回复完整"
else
  fail "Slow Path 重启续办或聚合回复失败 steps=${step_count} state=${plan_state} firstTasks=${first_replays} response=$(printf '%s' "${continued}" | jq -c '{usedTemplate,fellBack,text}')"
fi

echo "P1 Smoke: PASS=${PASS} FAIL=${FAIL}"
if [ "${FAIL}" -ne 0 ]; then exit 1; fi
