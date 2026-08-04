#!/usr/bin/env bash
# Strict acceptance gate for model-first contextual understanding and cross-Agent continuation.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"
COMPOSE=(docker compose -f "${PROJECT_ROOT}/dev/local/docker-compose.yml")

psql_local() {
  "${COMPOSE[@]}" exec -T postgres psql -U agent_platform -d agent_platform -Atc "$1"
}

RUN_LIVE=1
for arg in "$@"; do
  case "${arg}" in
    --offline) RUN_LIVE=0 ;;
    *) echo "unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

MODULES=(
  framework/registry/asset-registry
  framework/runtime/context-engine
  framework/runtime/agent-runtime-starter
  framework/intent-engine/intent-fastpath
  framework/observability/agent-observability
  infrastructure/a2a/a2a-client-starter
  infrastructure/a2a/a2a-server
  infrastructure/a2a/a2a-gateway-core
  infrastructure/persistence/persistence-jdbc
  agents/account/backend
  agents/mobile-banking-assistant/backend
)
TESTS=(
  ArbitrationSkillKnowledgeTest
  AssetLintTest
  LogicalModelRoutingTest
  ContextualQueryRewriterTest
  ContextLeaseCompilerTest
  ModelContextualQueryRewriterTest
  ArbitrationFallbackTest
  ContextualRewriteConsumptionTest
  DecisionTraceWiringTest
  MicrometerDecisionTraceTest
  AccountReferenceResolverTest
  ContextDeltaMergerTest
  ContextResolutionPolicyGateTest
  DeterministicContinuationRulesTest
  TaskContinuationPortTest
  ContinuationCoordinatorTest
  ContinuationPolicyGateTest
  ModelContinuationUnderstandingTest
  RuntimeBackedAgentNodeTest
  MultiLevelContextDelegationTest
  A2ACapabilityDelegatorTest
  A2ARemoteDomainReferenceResolverTest
  A2AGatewayTest
  AccountDomainReferenceCapabilityTest
  TaskOrchestratorMiddlewareTest
  Scene5WorkingMemoryTest
  ContinuationModelUnavailableEndToEndTest
  ModuleDependencyTest
  ThinEntryBoundaryTest
)

module_csv=$(IFS=,; printf '%s' "${MODULES[*]}")
test_csv=$(IFS=,; printf '%s' "${TESTS[*]}")

echo "[context-acceptance] run executable evidence"
mvn -B -q -pl "${module_csv}" -am \
  "-Dtest=${test_csv}" \
  -Dsurefire.failIfNoSpecifiedTests=false test

assert_suite() {
  local report="$1" minimum="$2" label="$3"
  if [ ! -f "${report}" ]; then
    echo "[FAIL] ${label} report missing: ${report}" >&2
    exit 1
  fi
  local tests skipped failures errors
  tests=$(xmllint --xpath 'string(/testsuite/@tests)' "${report}")
  skipped=$(xmllint --xpath 'string(/testsuite/@skipped)' "${report}")
  failures=$(xmllint --xpath 'string(/testsuite/@failures)' "${report}")
  errors=$(xmllint --xpath 'string(/testsuite/@errors)' "${report}")
  if [ "${tests}" -lt "${minimum}" ] || [ "${skipped}" != "0" ] || \
     [ "${failures}" != "0" ] || [ "${errors}" != "0" ]; then
    echo "[FAIL] ${label}: tests=${tests} minimum=${minimum} skipped=${skipped} failures=${failures} errors=${errors}" >&2
    exit 1
  fi
  echo "[PASS] ${label} tests=${tests} skipped=0 failures=0 errors=0"
}

assert_suite "framework/runtime/context-engine/target/surefire-reports/TEST-com.huawei.finance.context.ContextualQueryRewriterTest.xml" 9 "context rewrite policy"
assert_suite "framework/runtime/context-engine/target/surefire-reports/TEST-com.huawei.finance.context.ContextLeaseCompilerTest.xml" 10 "context lease authority provenance"
assert_suite "framework/runtime/agent-runtime-starter/target/surefire-reports/TEST-com.huawei.finance.runtime.bootstrap.ModelContextualQueryRewriterTest.xml" 5 "model contextual rewrite"
assert_suite "framework/intent-engine/intent-fastpath/target/surefire-reports/TEST-com.huawei.finance.fastpath.ArbitrationFallbackTest.xml" 9 "model arbitration fail-safe fallback and knowledge admission"
assert_suite "framework/intent-engine/intent-fastpath/target/surefire-reports/TEST-com.huawei.finance.fastpath.ContextualRewriteConsumptionTest.xml" 4 "context slot provenance and primary-goal preservation"
assert_suite "framework/intent-engine/intent-fastpath/target/surefire-reports/TEST-com.huawei.finance.fastpath.DecisionTraceWiringTest.xml" 9 "decision trace wiring"
assert_suite "framework/observability/agent-observability/target/surefire-reports/TEST-com.huawei.finance.obs.trace.MicrometerDecisionTraceTest.xml" 24 "async trace propagation"
assert_suite "agents/account/backend/target/surefire-reports/TEST-com.huawei.finance.domain.account.AccountReferenceResolverTest.xml" 3 "authoritative account reference mapping"
assert_suite "framework/registry/asset-registry/target/surefire-reports/TEST-com.huawei.finance.registry.ArbitrationSkillKnowledgeTest.xml" 5 "evaluated-gap knowledge admission"
assert_suite "framework/registry/asset-registry/target/surefire-reports/TEST-com.huawei.finance.registry.AssetLintTest.xml" 17 "knowledge asset release lint"
assert_suite "infrastructure/model/model-openai-compatible/target/surefire-reports/TEST-com.huawei.finance.gateway.LogicalModelRoutingTest.xml" 3 "logical model endpoint routing"
assert_suite "framework/runtime/context-engine/target/surefire-reports/TEST-com.huawei.finance.context.ContextDeltaMergerTest.xml" 4 "context delta CAS"
assert_suite "framework/runtime/agent-runtime-core/target/surefire-reports/TEST-com.huawei.finance.runtime.ContextResolutionPolicyGateTest.xml" 3 "authoritative reference resolution policy"
assert_suite "framework/runtime/task-orchestrator/target/surefire-reports/TEST-com.huawei.finance.orchestrator.continuation.DeterministicContinuationRulesTest.xml" 7 "deterministic continuation boundary"
assert_suite "framework/runtime/task-orchestrator/target/surefire-reports/TEST-com.huawei.finance.orchestrator.continuation.TaskContinuationPortTest.xml" 3 "runtime-declared continuation slots"
assert_suite "framework/runtime/task-orchestrator/target/surefire-reports/TEST-com.huawei.finance.orchestrator.continuation.ContinuationCoordinatorTest.xml" 4 "model-first continuation coordinator"
assert_suite "framework/runtime/task-orchestrator/target/surefire-reports/TEST-com.huawei.finance.orchestrator.continuation.ContinuationPolicyGateTest.xml" 8 "continuation policy gate"
assert_suite "framework/runtime/agent-runtime-starter/target/surefire-reports/TEST-com.huawei.finance.runtime.bootstrap.ModelContinuationUnderstandingTest.xml" 6 "model continuation"
assert_suite "infrastructure/a2a/a2a-client-starter/target/surefire-reports/TEST-com.huawei.finance.a2a.client.A2ACapabilityDelegatorTest.xml" 8 "A2A client"
assert_suite "infrastructure/a2a/a2a-client-starter/target/surefire-reports/TEST-com.huawei.finance.a2a.client.A2ARemoteDomainReferenceResolverTest.xml" 4 "A2A authoritative reference resolution"
assert_suite "infrastructure/a2a/a2a-server/target/surefire-reports/TEST-com.huawei.finance.a2a.node.RuntimeBackedAgentNodeTest.xml" 8 "A2A runtime resume"
assert_suite "infrastructure/a2a/a2a-gateway-core/target/surefire-reports/TEST-com.huawei.finance.a2a.MultiLevelContextDelegationTest.xml" 1 "three-level delegation"
assert_suite "infrastructure/a2a/a2a-gateway-core/target/surefire-reports/TEST-com.huawei.finance.a2a.A2AGatewayTest.xml" 9 "A2A gateway receipt"
assert_suite "infrastructure/persistence/persistence-jdbc/target/surefire-reports/TEST-com.huawei.finance.orchestrator.TaskOrchestratorMiddlewareTest.xml" 24 "task NEED_USER persistence"
assert_suite "agents/account/backend/target/surefire-reports/TEST-com.huawei.finance.domain.account.AccountDomainReferenceCapabilityTest.xml" 2 "account authoritative reference capability"
assert_suite "agents/mobile-banking-assistant/backend/target/surefire-reports/TEST-com.huawei.finance.arch.ModuleDependencyTest.xml" 20 "runtime extension dependency boundary"
assert_suite "agents/mobile-banking-assistant/backend/target/surefire-reports/TEST-com.huawei.finance.arch.ThinEntryBoundaryTest.xml" 2 "thin entry semantic boundary"
assert_suite "agents/mobile-banking-assistant/backend/target/surefire-reports/TEST-com.huawei.finance.product.mobilebanking.ContinuationModelUnavailableEndToEndTest.xml" 2 "model-unavailable continuation boundary"

SCENE_REPORT="agents/mobile-banking-assistant/backend/target/surefire-reports/TEST-com.huawei.finance.product.mobilebanking.Scene5WorkingMemoryTest.xml"
assert_suite "${SCENE_REPORT}" 5 "Scene5"
scene_tests=$(xmllint --xpath 'string(/testsuite/@tests)' "${SCENE_REPORT}")
if [ "${scene_tests}" != "5" ]; then
  echo "[FAIL] Scene5 must contain exactly 5 tests, actual=${scene_tests}" >&2
  exit 1
fi

CONTINUATION_RULES="framework/runtime/task-orchestrator/src/main/java/com/huawei/finance/orchestrator/continuation/DeterministicContinuationRules.java"
if rg -n -P '\p{Han}|Pattern\.compile|\.matches\(|toLowerCase\(' "${CONTINUATION_RULES}"; then
  echo "[FAIL] deterministic continuation rules contain natural-language interpretation" >&2
  exit 1
fi
echo "[PASS] deterministic continuation rules contain no natural-language phrase logic"

RUNTIME_ENTRY="framework/runtime/agent-runtime-core/src/main/java/com/huawei/finance/runtime/DefaultAgentRuntime.java"
if rg -n 'isContinueInput|\.matches\(' "${RUNTIME_ENTRY}"; then
  echo "[FAIL] runtime entry contains natural-language continuation interpretation" >&2
  exit 1
fi
echo "[PASS] runtime entry delegates natural-language continuation to the model"

STATIC_PLAN_ENTRY="framework/runtime/agent-runtime-core/src/main/java/com/huawei/finance/runtime/multi/MultiIntentCoordinator.java"
if rg -n 'looksLikeAbandon|private static Optional<SubIntent> pick|text\.contains|query\.contains' \
  "${STATIC_PLAN_ENTRY}"; then
  echo "[FAIL] Static Plan coordinator contains natural-language continuation interpretation" >&2
  exit 1
fi
echo "[PASS] Static Plan continuation is driven by model events, not phrase matching"

KNOWLEDGE_CONSUMERS=(
  framework/intent-engine/intent-fastpath/src/main/java/com/huawei/finance/fastpath/arbitration/ModelArbitrator.java
  framework/runtime/agent-runtime-starter/src/main/java/com/huawei/finance/runtime/bootstrap/ModelContextualQueryRewriter.java
  framework/runtime/agent-runtime-starter/src/main/java/com/huawei/finance/runtime/bootstrap/ModelContinuationUnderstanding.java
)
for consumer in "${KNOWLEDGE_CONSUMERS[@]}"; do
  if ! rg -q 'getEligibleKnowledgeExamples\(\)' "${consumer}"; then
    echo "[FAIL] model consumer bypasses evaluated-gap knowledge admission: ${consumer}" >&2
    exit 1
  fi
done
echo "[PASS] arbitration, context and continuation models inject only evaluated-gap knowledge"

echo "[context-acceptance] validate Jaeger validator positive/negative fixtures"
ruby scripts/test/validate_jaeger_trace_test.rb

if [ "${RUN_LIVE}" -eq 0 ]; then
  echo "[context-acceptance] offline mode: live conversation and Jaeger trace checks deferred"
else
  BASE_URL="${BASE_URL:-http://localhost:8080}"
  JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
  TRACE_IDS="${CONTEXT_TRACE_IDS:-}"
  TRACE_SPECS=()
  PRIVACY_MARKERS=()
  if [ -z "${TRACE_IDS}" ]; then
  if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null; then
    echo "[FAIL] live context acceptance requires ${BASE_URL}; set CONTEXT_TRACE_IDS to reuse generated traces" >&2
    exit 1
  fi
  session="context-acceptance-$(date +%s)-${RANDOM}"
  principal_marker="principal-context-${RANDOM}-private"
  first_query="查一下余额"
  second_query="第二张呢"
  chat() {
    local query="$1"
    jq -n --arg session "${session}" --arg user "${principal_marker}" --arg query "${query}" \
      '{sessionId:$session,userId:$user,query:$query,channel:"MOBILE_BANK",page:"account",userState:"LOGGED_IN"}' |
      curl -sf -H 'Content-Type: application/json' -H "X-User-ID: ${principal_marker}" \
        -H 'X-Space-ID: context-acceptance' -H 'X-Channel-ID: MOBILE_BANK' \
        -X POST "${BASE_URL}/api/v1/chat" --data-binary @-
  }
  first_response="$(chat "${first_query}")"
  printf '%s' "${first_response}" | jq -e '.traceId | test("^[0-9a-f]{32}$")' >/dev/null
  first_visible_text="$(printf '%s' "${first_response}" | jq -r '.text')"
  second_response="$(chat "${second_query}")"
  trace_id="$(printf '%s' "${second_response}" | jq -r '.traceId')"
  recent_context="$(curl -sf "${BASE_URL}/internal/console/recent")"
  if ! printf '%s' "${second_response}" | jq -e '
      .decision.decision == "EXECUTE_CAPABILITY" and
      .plan.slots.accountAlias == "尾号 3344 借记卡" and
      .plan.slots.availableBalance == "8,000.00"' >/dev/null || \
     ! printf '%s' "${trace_id}" | rg -q '^[0-9a-f]{32}$' || \
     ! printf '%s' "${recent_context}" | jq -e --arg trace "${trace_id}" '
       any(.[];
         .traceId == $trace and
         any(.moduleSteps[]?;
           .module == "context-engine" and .operation == "contextual-rewrite" and
           .outcome == "APPLIED" and
           .output.modelVersion != null and .output.modelVersion != "none" and
           (.output.promptVersion | length) > 0 and
           .output.slotUpdates.accountOrdinal == 2))' >/dev/null; then
    echo "[FAIL] contextual follow-up did not resolve the second authoritative account" >&2
    exit 1
  fi
  TRACE_SPECS+=("${trace_id}|full")

  paired_call_result="$(psql_local "
    select count(*)
      from agent_conversation_turn turn_record
     where tenant_id = 'context-acceptance'
       and agent_id = 'agent.mobile-banking-assistant'
       and session_id = '${session}'
       and exists (
         select 1
           from jsonb_array_elements(turn_record.messages) call_item
           join jsonb_array_elements(turn_record.messages) result_item
             on call_item->>'callId' = result_item->>'callId'
          where call_item->>'role' = 'ASSISTANT'
            and call_item->>'type' = 'TOOL_CALL'
            and result_item->>'role' = 'AGENT'
            and result_item->>'type' = 'AGENT_RESULT'
       )")"
  recent_context="$(curl -sf "${BASE_URL}/internal/console/recent")"
  if [ "${paired_call_result}" -lt 1 ] || ! printf '%s' "${recent_context}" | jq -e \
      --arg trace "${trace_id}" --arg visibleText "${first_visible_text}" '
      any(.[];
        .traceId == $trace and
        any(.moduleSteps[]?;
          .module == "context-engine" and .operation == "contextual-rewrite" and
          any(.input.conversationHistory[]?;
            .role == "assistant" and .type == "TEXT" and
            .userVisible == true and .modelVisible == true and
            .text == $visibleText and
            (.data.displaySlots.cards | length) == 3) and
          any(.input.conversationHistory[]?;
            .role == "agent" and .type == "AGENT_RESULT" and
            (.callId | length) > 0)))' >/dev/null; then
    echo "[FAIL] tool/Agent result and exact visible response must enter second-turn model context" >&2
    exit 1
  fi
  echo "[PASS] persisted TOOL_CALL/AGENT_RESULT pairing and exact visible projection"

  no_context_session="context-acceptance-no-context-$(date +%s)-${RANDOM}"
  session="${no_context_session}"
  no_context_response="$(chat "${second_query}")"
  if ! printf '%s' "${no_context_response}" | jq -e '
      .decision.decision == "CLARIFY" and
      (.plan.slots.accountAlias == null) and
      (.plan.slots.availableBalance == null)' >/dev/null; then
    echo "[FAIL] ordinal reference without authoritative context must clarify" >&2
    exit 1
  fi
  no_context_trace="$(printf '%s' "${no_context_response}" | jq -r '.traceId')"
  TRACE_SPECS+=("${no_context_trace}|entry")

  out_of_range_session="context-acceptance-out-of-range-$(date +%s)-${RANDOM}"
  session="${out_of_range_session}"
  chat "${first_query}" >/dev/null
  out_of_range_response="$(chat "第四张呢")"
  if ! printf '%s' "${out_of_range_response}" | jq -e '
      .decision.decision == "CLARIFY" and
      (.plan.slots.accountAlias == null) and
      (.plan.slots.availableBalance == null)' >/dev/null; then
    echo "[FAIL] out-of-range ordinal must not execute a fallback account" >&2
    exit 1
  fi
  out_of_range_trace="$(printf '%s' "${out_of_range_response}" | jq -r '.traceId')"
  TRACE_SPECS+=("${out_of_range_trace}|context-short-circuit")

  session="context-acceptance-correction-$(date +%s)-${RANDOM}"
  correction_first="$(chat "给张三转1000")"
  correction_second="$(chat "不是张三，是李四")"
  correction_third="$(chat "确认执行转账")"
  if ! printf '%s' "${correction_first}" | jq -e '
      .decision.decision == "EXECUTE_CAPABILITY" and
      .plan.responsePhase == "CONFIRM" and .plan.slots.payee == "张三" and
      .plan.slots.amount == "1000"' >/dev/null ||
     ! printf '%s' "${correction_second}" | jq -e '
      .decision.decision == "RESUME_TASK" and
      .plan.responsePhase == "CONFIRM" and .plan.slots.payee == "李四" and
      .plan.slots.amount == "1000"' >/dev/null ||
     ! printf '%s' "${correction_third}" | jq -e '
      .decision.decision == "RESUME_TASK" and
      .plan.responsePhase == "FINAL" and .plan.slots.payee == "李四"' >/dev/null; then
    echo "[FAIL] model-first correction/explicit confirmation did not preserve the task contract" >&2
    exit 1
  fi
  correction_trace="$(printf '%s' "${correction_third}" | jq -r '.traceId')"
  TRACE_SPECS+=("${correction_trace}|continuation-a2a")

  session="context-acceptance-card-slot-$(date +%s)-${RANDOM}"
  card_first="$(chat "查信用卡账单")"
  card_second="$(chat "尾号8821那张")"
  if ! printf '%s' "${card_first}" | jq -e '
      .decision.decision == "CLARIFY" and
      (.decision.missingSlots | index("cardRef")) != null' >/dev/null ||
     ! printf '%s' "${card_second}" | jq -e '
      .decision.decision == "RESUME_TASK" and
      .plan.responsePhase == "FINAL" and .plan.slots.cardRef == "尾号8821那张"' >/dev/null; then
    echo "[FAIL] model-resolved open slot did not resume the original task" >&2
    exit 1
  fi
  card_trace="$(printf '%s' "${card_second}" | jq -r '.traceId')"
  TRACE_SPECS+=("${card_trace}|continuation-a2a")

  session="context-acceptance-half-transfer-$(date +%s)-${RANDOM}"
  chat "${first_query}" >/dev/null
  half_review="$(chat "用第二张卡转一半给张三")"
  half_final="$(chat "确认执行转账")"
  if ! printf '%s' "${half_review}" | jq -e '
      .decision.decision == "EXECUTE_CAPABILITY" and
      .plan.responsePhase == "CONFIRM" and .plan.slots.payee == "张三" and
      .plan.slots.amount == null and .plan.slots.amountBasis == "REQUERY_THEN_HALF" and
      .plan.slots.fromAccount == "尾号 3344 借记卡"' >/dev/null ||
     ! printf '%s' "${half_final}" | jq -e '
      .decision.decision == "RESUME_TASK" and .plan.responsePhase == "FINAL" and
      .plan.slots.fromAccount == "尾号 3344 借记卡"' >/dev/null; then
    echo "[FAIL] contextual half transfer did not requery, review and explicitly execute" >&2
    exit 1
  fi
  half_review_trace="$(printf '%s' "${half_review}" | jq -r '.traceId')"
  half_final_trace="$(printf '%s' "${half_final}" | jq -r '.traceId')"
  TRACE_SPECS+=("${half_review_trace}|continuation-a2a")
  TRACE_SPECS+=("${half_final_trace}|continuation-a2a")

  PRIVACY_MARKERS+=(
    --privacy-marker "${principal_marker}"
    --privacy-marker "${second_query}"
    --privacy-marker "不是张三，是李四"
    --privacy-marker "尾号8821那张"
    --privacy-marker "用第二张卡转一半给张三"
    --privacy-marker "确认执行转账"
  )
  echo "[PASS] live ordinal references: valid=resolved no-context=clarify out-of-range=clarify trace=${trace_id}"
  echo "[PASS] live model continuation: correction=confirmed open-slot=resumed half-transfer=requeried"
  fi

  if [ "${#TRACE_SPECS[@]}" -eq 0 ]; then
    IFS=',' read -r -a trace_ids <<< "${TRACE_IDS}"
    for trace_id in "${trace_ids[@]}"; do
      trace_id="$(printf '%s' "${trace_id}" | tr -d '[:space:]')"
      [ -n "${trace_id}" ] || continue
      TRACE_SPECS+=("${trace_id}|full")
    done
  fi

  for trace_spec in "${TRACE_SPECS[@]}"; do
    IFS='|' read -r trace_id trace_profile <<< "${trace_spec}"
    trace_id="$(printf '%s' "${trace_id}" | tr -d '[:space:]')"
    [ -n "${trace_id}" ] || continue
    if [ "${trace_profile}" = "continuation-a2a" ]; then
      ruby scripts/validate-jaeger-trace.rb --trace-id "${trace_id}" --jaeger-url "${JAEGER_URL}" \
        --required-spans "agent.context.load,agent.context.compile,agent.context.rewrite,agent.task.orchestrate,agent.a2a.client,agent.a2a.delegate,agent.a2a.gateway.route,agent.a2a.server.execute,agent.a2a.target.runtime" \
        --wait-seconds 60 "${PRIVACY_MARKERS[@]}"
    else
      ruby scripts/validate-jaeger-trace.rb --trace-id "${trace_id}" --jaeger-url "${JAEGER_URL}" \
        --profile "${trace_profile}" --wait-seconds 60 "${PRIVACY_MARKERS[@]}"
    fi
  done
fi

ruby -ryaml -e '
  path = "agents/mobile-banking-assistant/eval/model-knowledge-admission-cases.yaml"
  data = YAML.safe_load(File.read(path), permitted_classes: [], aliases: false)
  failures = []
  Array(data["cases"]).each do |entry|
    id = entry["id"] || "<missing-id>"
    failures << "#{id}: status=#{entry["status"].inspect}, expected locked" unless entry["status"] == "locked"
    failures << "#{id}: expect must be ADMIT, REJECT or INERT" unless %w[ADMIT REJECT INERT].include?(entry["expect"])
    evidence = Array(entry["evidence"]).reject { |value| value.to_s.strip.empty? }
    failures << "#{id}: executable evidence missing" if evidence.empty?
  end
  unless failures.empty?
    warn "[FAIL] knowledge admission manifest:\n  #{failures.join("\n  ")}"
    exit 1
  end
  puts "[PASS] model knowledge admission cases are locked and carry executable evidence"
'

RUN_LIVE="${RUN_LIVE}" ruby -ryaml -e '
  path = "agents/mobile-banking-assistant/eval/context-continuation-cases.yaml"
  data = YAML.safe_load(File.read(path), permitted_classes: [], aliases: false)
  failures = []
  allowed_statuses = ENV["RUN_LIVE"] == "1" ? ["locked"] : ["locked", "known-gap"]
  Array(data["cases"]).each do |entry|
    id = entry["id"] || "<missing-id>"
    unless allowed_statuses.include?(entry["status"])
      failures << "#{id}: status=#{entry["status"].inspect}, expected #{allowed_statuses.join(" or ")}"
    end
    evidence = Array(entry["evidence"]).reject { |value| value.to_s.strip.empty? }
    failures << "#{id}: executable evidence missing" if evidence.empty?
  end
  unless failures.empty?
    warn "[FAIL] context acceptance manifest:\n  #{failures.join("\n  ")}"
    exit 1
  end
  puts ENV["RUN_LIVE"] == "1" \
    ? "[PASS] all context cases are locked and carry executable evidence" \
    : "[PASS] context cases carry executable evidence; live-only gaps remain explicit"
'

ruby -e '
  docs = %w[
    docs/多轮上下文改写与跨Agent传递验收用例_v0.1.md
    docs/入口意图路由实现与运营干预设计_v0.1.md
    docs/AgentLoop框架与模块输入输出设计_v0.1.md
    docs/Agent平台总体架构草案_v0.7.md
    docs/ADR-010-入口路由Decision与任务形态判定.md
  ]
  failures = []
  docs.each do |path|
    text = File.read(path)
    fences = text.lines.count { |line| line.match?(/^\s*```/) }
    failures << "#{path}: unclosed Markdown fence" if fences.odd?
    text.scan(/\[[^\]]+\]\(([^)]+)\)/).flatten.each do |raw|
      target = raw.strip.sub(/^</, "").sub(/>$/, "")
      next if target.empty? || target.start_with?("#", "http://", "https://", "mailto:")
      target = target.split("#", 2).first
      resolved = File.expand_path(target, File.dirname(path))
      failures << "#{path}: missing link target #{target}" unless File.exist?(resolved)
    end
  end
  unless failures.empty?
    warn "[FAIL] Markdown validation:\n  #{failures.join("\n  ")}"
    exit 1
  end
  puts "[PASS] Markdown fences and local links"
'

echo "[PASS] context acceptance complete"
