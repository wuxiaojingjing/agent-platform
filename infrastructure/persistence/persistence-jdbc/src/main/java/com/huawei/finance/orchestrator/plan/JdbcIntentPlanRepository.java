package com.huawei.finance.orchestrator.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.validation.ContractJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 多意图计划的读写。
 *
 * <p>推进游标一律走带 {@code where cursor = ?} 的条件更新，与任务态迁移同一理由：
 * 读出来判断再写回，在两个请求同时到达时会双双通过判断——同一件事因此被下发两次。
 */
public class JdbcIntentPlanRepository implements IntentPlanRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcIntentPlanRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcIntentPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 开一份新计划。
     *
     * <p>会话里已有在办计划时先作废旧的：用户重新提了一串诉求，说明上一串不作数了。
     * 不这么做的话唯一索引会直接拒绝插入，用户看到的是一次莫名其妙的失败。
     */
    public PlanRecord open(String agentId, String sessionId, String traceId, IntentPlan plan) {
        abandonActive(agentId, sessionId, "superseded");

        String planId = "plan-" + UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into agent_intent_plan
                        (plan_id, agent_id, session_id, trace_id, original, items, cursor, source, state)
                    values (?, ?, ?, ?, ?, cast(? as jsonb), 0, ?, ?)
                    """,
                    planId, agentId, sessionId, traceId, plan.original(), json(plan.items()),
                    plan.source().name(), PlanState.IN_PROGRESS.name());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException(
                    "会话 " + sessionId + " 已有在办计划却没能作废，两份在办计划会让"
                            + "「用户刚才选的是哪一件」失去唯一解", e);
        }
        return new PlanRecord(planId, agentId, sessionId, traceId, plan, 0, PlanState.IN_PROGRESS, 0);
    }

    /** 某 Agent 会话里在办的那份计划。唯一索引保证最多一条。 */
    public Optional<PlanRecord> findActiveBySession(String agentId, String sessionId) {
        return jdbc.query("""
                select * from agent_intent_plan
                where agent_id = ? and session_id = ?
                  and state in ('IN_PROGRESS','WAITING_USER','WAITING_REVIEW','WAITING_CONFIRMATION')
                order by updated_at desc limit 1
                """, MAPPER, agentId, sessionId).stream().findFirst();
    }

    @Override
    public Optional<PlanRecord> findById(String agentId, String planId) {
        return jdbc.query("select * from agent_intent_plan where agent_id = ? and plan_id = ?",
                MAPPER, agentId, planId).stream().findFirst();
    }

    /**
     * 推进到下一件。
     *
     * @return 是否推进成功；false 表示游标已被另一路请求动过，调用方不得按原下标继续
     */
    public boolean advance(String planId, int from) {
        int updated = jdbc.update("""
                update agent_intent_plan
                set cursor = cursor + 1, state_version = state_version + 1, updated_at = now()
                where plan_id = ? and cursor = ? and state = 'IN_PROGRESS'
                """, planId, from);
        if (updated == 0) {
            return false;
        }
        jdbc.update("""
                update agent_intent_plan
                set state = ?, updated_at = now()
                where plan_id = ? and cursor >= jsonb_array_length(items)
                """, PlanState.COMPLETED.name(), planId);
        return true;
    }

    @Override
    public boolean transition(String planId, PlanState from, PlanState to, long expectedVersion) {
        int updated = jdbc.update("""
                update agent_intent_plan
                set state = ?, state_version = state_version + 1,
                    pending_task_id = null, pending_slot = null, expected_answers = '[]'::jsonb,
                    updated_at = now()
                where plan_id = ? and state = ? and state_version = ?
                """, to.name(), planId, from.name(), expectedVersion);
        return updated == 1;
    }

    @Override
    public boolean waitFor(String planId, long expectedVersion, PlanState waitingState,
                           String taskId, String pendingSlot, List<String> expectedAnswers) {
        if (waitingState != PlanState.WAITING_USER
                && waitingState != PlanState.WAITING_REVIEW
                && waitingState != PlanState.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("非法等待态: " + waitingState);
        }
        int updated = jdbc.update("""
                update agent_intent_plan
                set state = ?, state_version = state_version + 1,
                    pending_task_id = ?, pending_slot = ?, expected_answers = cast(? as jsonb),
                    updated_at = now()
                where plan_id = ? and state = 'IN_PROGRESS' and state_version = ?
                """, waitingState.name(), taskId, pendingSlot, json(expectedAnswers),
                planId, expectedVersion);
        return updated == 1;
    }

    /**
     * 作废会话里在办的计划。没有在办计划时什么也不做。
     */
    public void abandonActive(String agentId, String sessionId, String reason) {
        int updated = jdbc.update("""
                update agent_intent_plan
                set state = ?, state_version = state_version + 1, updated_at = now()
                where agent_id = ? and session_id = ?
                  and state in ('IN_PROGRESS','WAITING_USER','WAITING_REVIEW','WAITING_CONFIRMATION')
                """, PlanState.ABANDONED.name(), agentId, sessionId);
        if (updated > 0) {
            log.info("作废在办计划 agent={} session={} 原因={}", agentId, sessionId, reason);
        }
    }

    @Override
    public List<PlanStepRecord> steps(String planId) {
        return jdbc.query("""
                select * from agent_intent_plan_step
                where plan_id = ? order by step_index
                """, (rs, rowNum) -> new PlanStepRecord(
                rs.getString("plan_id"), rs.getInt("step_index"),
                rs.getString("capability_id"), rs.getString("task_id"),
                Enums.TaskStatus.valueOf(rs.getString("status")),
                Enums.FailureClass.valueOf(rs.getString("failure_class")),
                readMap(rs.getString("result_facts")), rs.getString("reason_code"),
                rs.getTimestamp("completed_at").toInstant()), planId);
    }

    @Override
    public Map<String, Object> parameters(String agentId, String planId) {
        return jdbc.query("""
                select parameters from agent_intent_plan
                where agent_id = ? and plan_id = ?
                """, (rs, rowNum) -> readMap(rs.getString("parameters")), agentId, planId)
                .stream().findFirst().orElse(Map.of());
    }

    @Override
    public void saveParameters(String agentId, String planId, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return;
        int updated = jdbc.update("""
                update agent_intent_plan
                set parameters = parameters || cast(? as jsonb), updated_at = now()
                where agent_id = ? and plan_id = ?
                  and state in ('IN_PROGRESS','WAITING_USER','WAITING_REVIEW','WAITING_CONFIRMATION')
                """, json(parameters), agentId, planId);
        if (updated != 1) {
            throw new IllegalStateException("STATIC_PLAN_NOT_ACTIVE:" + planId);
        }
    }

    @Override
    public void saveStep(PlanStepRecord step) {
        jdbc.update("""
                insert into agent_intent_plan_step
                    (plan_id, step_index, capability_id, task_id, status, failure_class,
                     result_facts, reason_code, completed_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                on conflict (plan_id, step_index) do update set
                    task_id = excluded.task_id,
                    status = excluded.status,
                    failure_class = excluded.failure_class,
                    result_facts = excluded.result_facts,
                    reason_code = excluded.reason_code,
                    completed_at = excluded.completed_at
                """, step.planId(), step.stepIndex(), step.capabilityId(), step.taskId(),
                step.status().name(), step.failureClass().name(), json(step.facts()),
                step.reasonCode(), java.sql.Timestamp.from(step.completedAt()));
    }

    @Override
    public boolean saveStepAndAdvance(PlanStepRecord step, int from) {
        int updated = jdbc.update("""
                with current_plan as (
                    select plan_id from agent_intent_plan
                    where plan_id = ? and cursor = ? and state = 'IN_PROGRESS'
                    for update
                ), saved as (
                    insert into agent_intent_plan_step
                        (plan_id, step_index, capability_id, task_id, status, failure_class,
                         result_facts, reason_code, completed_at)
                    select ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ? from current_plan
                    on conflict (plan_id, step_index) do update set
                        task_id = excluded.task_id,
                        status = excluded.status,
                        failure_class = excluded.failure_class,
                        result_facts = excluded.result_facts,
                        reason_code = excluded.reason_code,
                        completed_at = excluded.completed_at
                    returning plan_id
                )
                update agent_intent_plan
                set cursor = cursor + 1,
                    state = case when cursor + 1 >= jsonb_array_length(items)
                                 then 'COMPLETED' else state end,
                    state_version = state_version + 1,
                    updated_at = now()
                where plan_id = ? and cursor = ? and exists (select 1 from saved)
                """, step.planId(), from, step.planId(), step.stepIndex(),
                step.capabilityId(), step.taskId(), step.status().name(),
                step.failureClass().name(), json(step.facts()), step.reasonCode(),
                java.sql.Timestamp.from(step.completedAt()), step.planId(), from);
        return updated == 1;
    }

    @Override
    public Optional<PlanConditionResolutionRecord> findConditionResolution(
            String planId, int stepIndex, String factDigest) {
        return jdbc.query("""
                select * from agent_intent_plan_condition
                where plan_id = ? and step_index = ? and fact_digest = ?
                """, (rs, rowNum) -> {
            String raw = rs.getString("compiled_expression");
            ConditionExpression expression = parseConditionExpression(raw);
            return new PlanConditionResolutionRecord(
                    rs.getString("plan_id"), rs.getInt("step_index"), rs.getString("source_text"),
                    expression, PlanConditionResolutionRecord.Outcome.valueOf(rs.getString("outcome")),
                    rs.getString("fact_digest"), rs.getString("model_version"),
                    rs.getString("prompt_version"), rs.getTimestamp("created_at").toInstant());
        }, planId, stepIndex, factDigest).stream().findFirst();
    }

    @Override
    public void saveConditionResolution(PlanConditionResolutionRecord resolution) {
        jdbc.update("""
                insert into agent_intent_plan_condition
                    (plan_id, step_index, source_text, compiled_expression, outcome, fact_digest,
                     model_version, prompt_version, created_at)
                values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)
                on conflict (plan_id, step_index, fact_digest) do nothing
                """, resolution.planId(), resolution.stepIndex(), resolution.sourceText(),
                resolution.expression() == null ? null : json(resolution.expression()),
                resolution.outcome().name(), resolution.factDigest(), resolution.modelVersion(),
                resolution.promptVersion(), java.sql.Timestamp.from(resolution.createdAt()));
    }

    private static ConditionExpression parseConditionExpression(String raw) {
        if (raw == null) return null;
        try {
            return ContractJson.mapper().readValue(raw, ConditionExpression.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("条件表达式反序列化失败", invalid);
        }
    }

    private static final RowMapper<PlanRecord> MAPPER = JdbcIntentPlanRepository::map;

    private static PlanRecord map(ResultSet rs, int rowNum) throws SQLException {
        List<SubIntent> items = readItems(rs.getString("items"));
        IntentPlan plan = new IntentPlan(rs.getString("original"), items,
                IntentPlan.Source.valueOf(rs.getString("source")));
        String agentId = rs.getString("agent_id");
        if (agentId == null || agentId.isBlank()) {
            agentId = RequestContext.AGENT_ENTRY;
        }
        String pendingTaskId = rs.getString("pending_task_id");
        String pendingSlot = rs.getString("pending_slot");
        PlanRecord.PendingInteraction pending = pendingTaskId == null && pendingSlot == null
                ? null : new PlanRecord.PendingInteraction(
                        pendingTaskId, pendingSlot, readList(rs.getString("expected_answers")));
        return new PlanRecord(rs.getString("plan_id"), agentId, rs.getString("session_id"),
                rs.getString("trace_id"), plan, rs.getInt("cursor"),
                PlanState.valueOf(rs.getString("state")), rs.getLong("state_version"), pending);
    }

    private static List<SubIntent> readItems(String json) {
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<List<SubIntent>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("计划反序列化失败，这份计划已经没法继续办了", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return ContractJson.mapper().readValue(json,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("计划步骤事实反序列化失败", e);
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("计划待选值反序列化失败", e);
        }
    }

    private static String json(Object value) {
        try {
            return ContractJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("计划序列化失败", e);
        }
    }
}
