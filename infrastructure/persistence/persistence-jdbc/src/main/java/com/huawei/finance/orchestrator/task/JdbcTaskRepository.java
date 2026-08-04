package com.huawei.finance.orchestrator.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.validation.ContractJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 任务态读写。
 *
 * <p>状态迁移一律走带 {@code where state = ?} 的条件更新。读出来判断再写回，
 * 在两个请求同时到达时会双双通过判断——转账任务因此被执行两次，而且日志上看不出异常。
 */
public class JdbcTaskRepository implements TaskRepository {

    private final JdbcTemplate jdbc;

    public JdbcTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskRecord task) {
        jdbc.update("""
                insert into agent_task (task_id, agent_id, trace_id, session_id, user_id, capability_id, domain, goal,
                                      state, risk_level, source, invocation_origin, parameters, pending_slot, expected_answers,
                                      clarify_rounds, guardrail_status, guardrail_codes, idempotency_key,
                                      source_invocation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb), ?, ?, cast(? as jsonb), ?, ?)
                """,
                task.taskId(), task.agentId(), task.traceId(), task.sessionId(), task.userId(),
                task.capabilityId(), task.domain(), task.goal(), task.state().name(), task.riskLevel().name(),
                task.source().name(), task.invocationOrigin().name(), json(task.parameters()), task.pendingSlot(),
                json(task.expectedAnswers()), task.clarifyRounds(), task.guardrail().status().name(),
                json(task.guardrail().codes()), task.idempotencyKey(), task.sourceInvocationId());

        recordTransition(task.taskId(), null, task.state(), "created", task.traceId());
    }

    public Optional<TaskRecord> findById(String taskId) {
        return jdbc.query("select * from agent_task where task_id = ?", MAPPER, taskId).stream().findFirst();
    }

    /** 某 Agent 会话内的活跃任务。唯一索引保证最多一条。 */
    public Optional<TaskRecord> findActiveBySession(String agentId, String sessionId) {
        return jdbc.query("""
                select * from agent_task
                where agent_id = ? and session_id = ?
                  and state in ('CREATED', 'CLARIFY_PENDING', 'REVIEW_PENDING', 'CONFIRM_PENDING', 'RUNNING')
                """, MAPPER, agentId, sessionId).stream().findFirst();
    }

    public Optional<TaskRecord> findBySourceInvocation(
            String agentId, Enums.InvocationOrigin origin, String sourceInvocationId) {
        if (sourceInvocationId == null || sourceInvocationId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select * from agent_task
                where agent_id = ? and invocation_origin = ? and source_invocation_id = ?
                """, MAPPER, agentId, origin.name(), sourceInvocationId).stream().findFirst();
    }

    /**
     * 迁移状态。
     *
     * @return 是否迁移成功；false 表示任务已不在预期状态，调用方不得继续按原计划执行
     */
    public boolean transition(String taskId, TaskState from, TaskState to, String reason, String traceId) {
        TaskStateMachine.assertAllowed(taskId, from, to);
        int updated = jdbc.update(
                "update agent_task set state = ?, state_version = state_version + 1, updated_at = now() "
                        + "where task_id = ? and state = ?",
                to.name(), taskId, from.name());
        if (updated == 0) {
            return false;
        }
        recordTransition(taskId, from, to, reason, traceId);
        return true;
    }

    public void updateClarifyState(String taskId, Map<String, Object> parameters, String pendingSlot,
                                   List<String> expectedAnswers, int clarifyRounds) {
        jdbc.update("""
                update agent_task
                set parameters = cast(? as jsonb), pending_slot = ?, expected_answers = cast(? as jsonb),
                    clarify_rounds = ?, state_version = state_version + 1, updated_at = now()
                where task_id = ?
                """, json(parameters), pendingSlot, json(expectedAnswers), clarifyRounds, taskId);
    }

    public void updateParameters(String taskId, Map<String, Object> parameters) {
        jdbc.update("update agent_task set parameters = cast(? as jsonb), "
                        + "state_version = state_version + 1, updated_at = now() where task_id = ?",
                json(parameters), taskId);
    }

    public void updateGuardrail(String taskId, GuardrailCheck check) {
        jdbc.update("""
                update agent_task set guardrail_status = ?, guardrail_codes = cast(? as jsonb),
                state_version = state_version + 1, updated_at = now()
                where task_id = ?
                """, check.status().name(), json(check.codes()), taskId);
    }

    /**
     * 落幂等键。
     *
     * <p>先插 {@code agent_idempotency} 再更新任务：主键冲突即说明这把凭据已经发过，
     * 此时必须让调用方知道「不要再执行一次」。若顺序反过来，任务上已经挂了键，
     * 冲突却发生在后一步，任务会停在一个自相矛盾的状态。
     *
     * @return 是否首次发放
     */
    public boolean attachIdempotencyKey(String taskId, String capabilityId, String idempotencyKey) {
        try {
            jdbc.update("insert into agent_idempotency (idempotency_key, task_id, capability_id) values (?, ?, ?)",
                    idempotencyKey, taskId, capabilityId);
        } catch (DuplicateKeyException e) {
            return false;
        }
        // CHECK 约束会在护栏未通过时拒绝这次更新，这正是我们要的最后一道闸
        jdbc.update("update agent_task set idempotency_key = ?, state_version = state_version + 1, "
                        + "updated_at = now() where task_id = ?",
                idempotencyKey, taskId);
        return true;
    }

    public Optional<String> idempotencyKeyOf(String taskId) {
        List<String> keys = jdbc.queryForList(
                "select idempotency_key from agent_task where task_id = ?", String.class, taskId);
        return keys.stream().filter(k -> k != null && !k.isBlank()).findFirst();
    }

    public void saveResult(String taskId, TaskResult result) {
        jdbc.update("""
                update agent_task set result_payload = cast(? as jsonb), result_status = ?,
                    failure_class = ?, state_version = state_version + 1, updated_at = now()
                where task_id = ?
                """, json(result.resultPayload()), result.status().name(),
                result.failureClass().name(), taskId);
    }

    public Optional<TaskResult> resultOf(String taskId) {
        return jdbc.query("""
                select task_id, result_status, failure_class, result_payload, idempotency_key,
                       guardrail_status, guardrail_codes
                from agent_task where task_id = ? and result_status is not null
                """, (rs, rowNum) -> new TaskResult(
                    rs.getString("task_id"),
                    Enums.TaskStatus.valueOf(rs.getString("result_status")),
                    Enums.FailureClass.valueOf(rs.getString("failure_class")),
                    readMap(rs.getString("result_payload")),
                    rs.getString("idempotency_key"),
                    new GuardrailCheck(Enums.GuardrailStatus.valueOf(rs.getString("guardrail_status")),
                            readList(rs.getString("guardrail_codes")))), taskId).stream().findFirst();
    }

    public List<String> transitionsOf(String taskId) {
        return jdbc.queryForList("""
                select coalesce(from_state, '-') || '->' || to_state
                from agent_task_transition where task_id = ? order by id
                """, String.class, taskId);
    }

    private void recordTransition(String taskId, TaskState from, TaskState to, String reason, String traceId) {
        jdbc.update("""
                insert into agent_task_transition (task_id, from_state, to_state, reason, trace_id)
                values (?, ?, ?, ?, ?)
                """, taskId, from == null ? null : from.name(), to.name(), reason, traceId);
    }

    private static String json(Object value) {
        try {
            return ContractJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("任务字段序列化失败", e);
        }
    }

    private static final RowMapper<TaskRecord> MAPPER = JdbcTaskRepository::mapRow;

    private static TaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        String agentId = rs.getString("agent_id");
        if (agentId == null || agentId.isBlank()) {
            agentId = com.huawei.finance.common.context.RequestContext.AGENT_ENTRY;
        }
        return new TaskRecord(
                rs.getString("task_id"),
                agentId,
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("capability_id"),
                rs.getString("domain"),
                rs.getString("goal"),
                TaskState.valueOf(rs.getString("state")),
                RiskLevel.valueOf(rs.getString("risk_level")),
                Enums.TaskSource.valueOf(rs.getString("source")),
                Enums.InvocationOrigin.valueOf(rs.getString("invocation_origin")),
                readMap(rs.getString("parameters")),
                rs.getString("pending_slot"),
                readList(rs.getString("expected_answers")),
                rs.getInt("clarify_rounds"),
                new GuardrailCheck(Enums.GuardrailStatus.valueOf(rs.getString("guardrail_status")),
                        readList(rs.getString("guardrail_codes"))),
                rs.getString("idempotency_key"),
                rs.getString("source_invocation_id"),
                rs.getLong("state_version"));
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("任务参数反序列化失败", e);
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("任务列表字段反序列化失败", e);
        }
    }
}
