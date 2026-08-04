package com.huawei.finance.product.mobilebanking.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, redacted projection of persisted Agent collaboration state. */
@Component
public class CollaborationTraceView {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CollaborationTraceView(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    public Map<String, Object> byTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) return Map.of();
        try {
            List<Map<String, Object>> delegations = jdbc.queryForList("""
                    select delegation_id as "delegationId",
                           source_agent_id as "sourceAgentId",
                           target_agent_id as "targetAgentId",
                           root_task_id as "rootTaskId",
                           parent_task_id as "parentTaskId",
                           source_task_id as "sourceTaskId",
                           mode, capability_id as "capabilityId", depth,
                           outcome, reason_code as "reasonCode", created_at as "createdAt"
                    from agent_delegation where trace_id = ? order by created_at
                    """, traceId);
            List<Map<String, Object>> tasks = jdbc.queryForList("""
                    select task_id as "taskId", agent_id as "agentId",
                           capability_id as "capabilityId", state,
                           source as "intentPath", invocation_origin as "invocationOrigin",
                           guardrail_status as "guardrailStatus",
                           failure_class as "failureClass", created_at as "createdAt"
                    from agent_task where trace_id = ? order by created_at
                    """, traceId);
            return Map.of("delegations", delegations, "tasks", tasks);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    public Map<String, Object> latestPlan(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Map.of();
        try {
            List<Map<String, Object>> plans = jdbc.queryForList("""
                    select plan_id as "planId", cursor, jsonb_array_length(items) as "stepCount",
                           state, trace_id as "traceId", created_at as "createdAt",
                           updated_at as "updatedAt", original, source,
                           items::text as "itemsJson"
                    from agent_intent_plan
                    where agent_id = ? and session_id = ?
                    order by created_at desc limit 1
                    """, agentId, sessionId);
            if (plans.isEmpty()) return Map.of();
            Map<String, Object> plan = new LinkedHashMap<>(plans.getFirst());
            plan.put("items", readItems(plan.remove("itemsJson")));
            plan.put("steps", jdbc.queryForList("""
                    select step_index as "stepIndex", capability_id as "capabilityId",
                           task_id as "taskId", status, failure_class as "failureClass",
                           reason_code as "reasonCode",
                           (select coalesce(jsonb_agg(k), '[]'::jsonb)::text
                              from jsonb_object_keys(result_facts) k) as "factKeys",
                           completed_at as "completedAt"
                    from agent_intent_plan_step where plan_id = ? order by step_index
                    """, plan.get("planId")));
            return Map.copyOf(plan);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readItems(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            return mapper.readValue(String.valueOf(raw), new TypeReference<>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
