#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
FIXTURE="${SCENARIO_FIXTURE:-${ROOT}/agents/mobile-banking-assistant/e2e/conversation-scenarios.json}"
USER_ID="${SCENARIO_USER_ID:-conversation-e2e}"
SPACE_ID="${SCENARIO_SPACE_ID:-conversation-e2e}"
CHANNEL="${SCENARIO_CHANNEL:-MOBILE_BANK}"
RUN_ID="$(date +%s)-${RANDOM}"
PASS=0
FAIL=0
SCENARIO_NUMBER=0

pass() { PASS=$((PASS + 1)); echo "[PASS] $*"; }
fail() { FAIL=$((FAIL + 1)); echo "[FAIL] $*" >&2; }

require() {
  command -v "$1" >/dev/null || { echo "缺少命令：$1" >&2; exit 2; }
}

post_chat() {
  local payload="$1"
  curl -sS -H 'Content-Type: application/json' \
    -H "X-User-ID: ${USER_ID}" -H "X-Space-ID: ${SPACE_ID}" -H "X-Channel-ID: ${CHANNEL}" \
    -X POST "${BASE_URL}/api/v1/chat" --data "${payload}" -w $'\n%{http_code}'
}

response_diagnostic() {
  local response="$1"
  if printf '%s' "${response}" | jq -e 'type == "object"' >/dev/null 2>&1; then
    printf '%s' "${response}" | jq -c \
      '{traceId,decision:.decision.decision,reasonCode:.decision.reasonCode,
        phase:.plan.responsePhase,candidates:.decision.candidateIds,
        template:.plan.templateKey,cards:.plan.cardComponents,
        actions:[.actions[]?.event],text,error,message}'
    return
  fi
  printf 'non-json=%s' "$(printf '%s' "${response}" | tr '\n' ' ' | cut -c1-1200)"
}

assert_scalar() {
  local response="$1" path="$2" expected="$3" label="$4" actual
  [ -z "${expected}" ] && return 0
  actual="$(printf '%s' "${response}" | jq -r "${path} // empty")"
  if [ "${actual}" != "${expected}" ]; then
    fail "${label}: expected=${expected} actual=${actual:-<empty>}"
    return 1
  fi
}

assert_array_contains() {
  local response="$1" path="$2" expected_json="$3" label="$4" item ok=0
  while IFS= read -r item; do
    if ! printf '%s' "${response}" | jq -e --arg item "${item}" "${path} | index(\$item) != null" >/dev/null; then
      fail "${label}: 缺少 ${item}"
      ok=1
    fi
  done < <(printf '%s' "${expected_json}" | jq -r '.[]?')
  return "${ok}"
}

assert_step() {
  local response="$1" expected="$2" captures="$3" ok=0 value key expected_value expected_task task_id event capture
  value="$(printf '%s' "${expected}" | jq -r '.decision // empty')"
  assert_scalar "${response}" '.decision.decision' "${value}" decision || ok=1
  value="$(printf '%s' "${expected}" | jq -r '.phase // empty')"
  assert_scalar "${response}" '.plan.responsePhase' "${value}" phase || ok=1
  value="$(printf '%s' "${expected}" | jq -r '.reasonCode // empty')"
  assert_scalar "${response}" '.decision.reasonCode' "${value}" reasonCode || ok=1
  value="$(printf '%s' "${expected}" | jq -r '.taskShape // empty')"
  assert_scalar "${response}" '.decision.taskShape' "${value}" taskShape || ok=1
  value="$(printf '%s' "${expected}" | jq -r '.candidate // empty')"
  if [ -n "${value}" ] && ! printf '%s' "${response}" | jq -e --arg value "${value}" \
      '.decision.candidateIds | index($value) != null' >/dev/null; then
    fail "candidate: 缺少 ${value}"
    ok=1
  fi
  value="$(printf '%s' "${expected}" | jq -r '.template // empty')"
  assert_scalar "${response}" '.plan.templateKey' "${value}" template || ok=1
  assert_array_contains "${response}" '.plan.actionCodes' \
    "$(printf '%s' "${expected}" | jq -c '.actionCodes // []')" actionCodes || ok=1
  assert_array_contains "${response}" '.plan.cardComponents' \
    "$(printf '%s' "${expected}" | jq -c '.cardComponents // []')" cardComponents || ok=1
  assert_array_contains "${response}" '[.actions[].event]' \
    "$(printf '%s' "${expected}" | jq -c '.responseActions // []')" responseActions || ok=1
  assert_array_contains "${response}" '.decision.evidenceRefs' \
    "$(printf '%s' "${expected}" | jq -c '.evidenceRefs // []')" evidenceRefs || ok=1
  value="$(printf '%s' "${expected}" | jq -c '.intentPlanCapabilities // empty')"
  if [ -n "${value}" ] && ! printf '%s' "${response}" | jq -e --argjson expected "${value}" \
      '[.decision.intentPlan.items[].capabilityId] == $expected' >/dev/null; then
    fail "intentPlan.capabilities: expected=${value} actual=$(printf '%s' "${response}" | jq -c '[.decision.intentPlan.items[].capabilityId]')"
    ok=1
  fi
  value="$(printf '%s' "${expected}" | jq -c '.intentPlanRelations // empty')"
  if [ -n "${value}" ] && ! printf '%s' "${response}" | jq -e --argjson expected "${value}" \
      '[.decision.intentPlan.items[].relation] == $expected' >/dev/null; then
    fail "intentPlan.relations: expected=${value} actual=$(printf '%s' "${response}" | jq -c '[.decision.intentPlan.items[].relation]')"
    ok=1
  fi
  while IFS= read -r value; do
    if ! printf '%s' "${response}" | jq -e --arg value "${value}" '.text | contains($value)' >/dev/null; then
      fail "text: 缺少 ${value}"
      ok=1
    fi
  done < <(printf '%s' "${expected}" | jq -r '.textContains[]?')
  while IFS= read -r value; do
    if printf '%s' "${response}" | jq -e --arg value "${value}" '.text | contains($value)' >/dev/null; then
      fail "text: 不应包含 ${value}"
      ok=1
    fi
  done < <(printf '%s' "${expected}" | jq -r '.textNotContains[]?')
  while IFS= read -r value; do
    if printf '%s' "${response}" | jq -e --arg value "${value}" '.plan.slots | has($value)' >/dev/null; then
      fail "slots: 不应包含 ${value}"
      ok=1
    fi
  done < <(printf '%s' "${expected}" | jq -r '.slotsAbsent[]?')
  while IFS= read -r value; do
    key="$(printf '%s' "${value}" | jq -r '.key')"
    expected_value="$(printf '%s' "${value}" | jq -c '.value')"
    if ! printf '%s' "${response}" | jq -e --arg key "${key}" --argjson expected "${expected_value}" \
        '.plan.slots[$key] == $expected' >/dev/null; then
      fail "slots.${key}: expected=${expected_value} actual=$(printf '%s' "${response}" | jq -c --arg key "${key}" '.plan.slots[$key]')"
      ok=1
    fi
  done < <(printf '%s' "${expected}" | jq -c '.slotValues // {} | to_entries[]')
  if printf '%s' "${expected}" | jq -e 'has("taskIdPresent")' >/dev/null; then
    value="$(printf '%s' "${expected}" | jq -r '.taskIdPresent')"
    if [ "${value}" = true ] && ! printf '%s' "${response}" | jq -e '.taskId != null' >/dev/null; then
      fail "taskId: 预期创建任务，实际为空"
      ok=1
    elif [ "${value}" = false ] && ! printf '%s' "${response}" | jq -e '.taskId == null' >/dev/null; then
      fail "taskId: 预期不创建任务，实际=$(printf '%s' "${response}" | jq -r '.taskId')"
      ok=1
    fi
  fi
  task_id="$(printf '%s' "${response}" | jq -r '.taskId // empty')"
  value="$(printf '%s' "${expected}" | jq -r '.taskIdEqualsCapture // empty')"
  if [ -n "${value}" ]; then
    expected_task="$(printf '%s' "${captures}" | jq -r --arg key "${value}" '.[$key] // empty')"
    if [ -z "${expected_task}" ] || [ "${task_id}" != "${expected_task}" ]; then
      fail "taskId: expected capture=${value}:${expected_task:-<missing>} actual=${task_id:-<empty>}"
      ok=1
    fi
  fi
  value="$(printf '%s' "${expected}" | jq -r '.taskIdDiffersCapture // empty')"
  if [ -n "${value}" ]; then
    expected_task="$(printf '%s' "${captures}" | jq -r --arg key "${value}" '.[$key] // empty')"
    if [ -z "${task_id}" ] || [ -z "${expected_task}" ] || [ "${task_id}" = "${expected_task}" ]; then
      fail "taskId: expected different from capture=${value}:${expected_task:-<missing>} actual=${task_id:-<empty>}"
      ok=1
    fi
  fi
  while IFS= read -r value; do
    if ! printf '%s' "${response}" | jq -e --arg event "${value}" --arg task "${task_id}" \
        '.actions | any(.event == $event and .ref == $task)' >/dev/null; then
      fail "action ${value}: ref 未指向当前 taskId=${task_id:-<empty>}"
      ok=1
    fi
  done < <(printf '%s' "${expected}" | jq -r '.actionRefsTaskId[]?')
  while IFS= read -r value; do
    event="$(printf '%s' "${value}" | jq -r '.event')"
    capture="$(printf '%s' "${value}" | jq -r '.capture')"
    expected_task="$(printf '%s' "${captures}" | jq -r --arg key "${capture}" '.[$key] // empty')"
    if [ -z "${expected_task}" ] || ! printf '%s' "${response}" | jq -e \
        --arg event "${event}" --arg ref "${expected_task}" \
        '.actions | any(.event == $event and .ref == $ref)' >/dev/null; then
      fail "action ${event}: ref 未指向 capture=${capture}:${expected_task:-<missing>}"
      ok=1
    fi
  done < <(printf '%s' "${expected}" | jq -c '.actionRefsCaptures[]?')
  return "${ok}"
}

require curl
require jq
jq -e 'type == "array" and length > 0' "${FIXTURE}" >/dev/null
curl -sf "${BASE_URL}/actuator/health" >/dev/null

while IFS= read -r scenario; do
  SCENARIO_NUMBER=$((SCENARIO_NUMBER + 1))
  id="$(printf '%s' "${scenario}" | jq -r '.id')"
  category="$(printf '%s' "${scenario}" | jq -r '.category')"
  page="$(printf '%s' "${scenario}" | jq -r '.page // "home"')"
  # agent_task.session_id 是 varchar(64)。完整场景 id 仅用于报告，运行时会话使用短序号；
  # 否则长场景只有在真正建任务时才以 HTTP 500 暴露，知识直答阶段看起来仍正常。
  session="scenario-${RUN_ID}-${SCENARIO_NUMBER}"
  previous='{}'
  captures='{}'
  step_number=0
  scenario_failed=0

  while IFS= read -r step; do
    step_number=$((step_number + 1))
    query="$(printf '%s' "${step}" | jq -r '.query // ""')"
    action_event="$(printf '%s' "${step}" | jq -r '.actionFromPrevious // empty')"
    quick_reply="$(printf '%s' "${step}" | jq -r '.quickReplyFromPrevious // empty')"
    step_page="$(printf '%s' "${step}" | jq -r '.page // empty')"
    [ -n "${step_page}" ] || step_page="${page}"
    action='null'
    if [ -n "${quick_reply}" ]; then
      if ! printf '%s' "${previous}" | jq -e --arg value "${quick_reply}" \
          '.plan.slots.options | index($value) != null' >/dev/null; then
        fail "${id} step=${step_number}: 上一步没有选项 ${quick_reply}"
        scenario_failed=1
        break
      fi
      query="${quick_reply}"
    fi
    if [ -n "${action_event}" ]; then
      action_ref_capture="$(printf '%s' "${step}" | jq -r '.actionRefCaptureFromPrevious // empty')"
      action_ref=''
      if [ -n "${action_ref_capture}" ]; then
        action_ref="$(printf '%s' "${captures}" | jq -r --arg key "${action_ref_capture}" '.[$key] // empty')"
      fi
      action="$(printf '%s' "${previous}" | jq -c --arg event "${action_event}" --arg ref "${action_ref}" \
        '[.actions[]? | select(.event == $event and ($ref == "" or .ref == $ref))][0] // null')"
      if [ "${action}" = null ]; then
        fail "${id} step=${step_number}: 上一步没有动作 ${action_event}"
        scenario_failed=1
        break
      fi
      query="$(printf '%s' "${previous}" | jq -r --arg event "${action_event}" \
        '[.actions[]? | select(.event == $event)][0].label // ""')"
    fi
    payload="$(jq -n --arg session "${session}" --arg user "${USER_ID}" --arg query "${query}" \
      --arg channel "${CHANNEL}" --arg page "${step_page}" --argjson action "${action}" \
      '{sessionId:$session,userId:$user,query:$query,channel:$channel,page:$page,userState:"LOGGED_IN",action:$action}')"
    if ! raw_response="$(post_chat "${payload}")"; then
      fail "${id} step=${step_number}: 对话接口传输失败"
      scenario_failed=1
      break
    fi
    http_status="${raw_response##*$'\n'}"
    response="${raw_response%$'\n'*}"
    if [[ ! "${http_status}" =~ ^2[0-9][0-9]$ ]]; then
      fail "${id} step=${step_number}: HTTP ${http_status} $(response_diagnostic "${response}")"
      scenario_failed=1
      break
    fi
    if ! printf '%s' "${response}" | jq -e 'type == "object"' >/dev/null 2>&1; then
      fail "${id} step=${step_number}: HTTP ${http_status} 返回非法 JSON $(response_diagnostic "${response}")"
      scenario_failed=1
      break
    fi
    expected="$(printf '%s' "${step}" | jq -c '.expect // {}')"
    if ! assert_step "${response}" "${expected}" "${captures}"; then
      fail "${id} step=${step_number}: $(response_diagnostic "${response}")"
      scenario_failed=1
      break
    fi
    capture_name="$(printf '%s' "${expected}" | jq -r '.captureTaskIdAs // empty')"
    if [ -n "${capture_name}" ]; then
      captured_task_id="$(printf '%s' "${response}" | jq -r '.taskId // empty')"
      if [ -z "${captured_task_id}" ]; then
        fail "${id} step=${step_number}: 无法捕获空 taskId 为 ${capture_name}"
        scenario_failed=1
        break
      fi
      captures="$(printf '%s' "${captures}" | jq -c --arg key "${capture_name}" --arg value "${captured_task_id}" \
        '. + {($key): $value}')"
    fi
    previous="${response}"
  done < <(printf '%s' "${scenario}" | jq -c '.steps[]')

  if [ "${scenario_failed}" -eq 0 ]; then
    pass "${category} / ${id}"
  fi
done < <(jq -c '.[]' "${FIXTURE}")

echo "Conversation scenarios: PASS=${PASS} FAIL=${FAIL}"
[ "${FAIL}" -eq 0 ]
