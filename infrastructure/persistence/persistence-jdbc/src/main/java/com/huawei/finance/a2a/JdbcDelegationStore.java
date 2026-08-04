package com.huawei.finance.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 委托台账的 Postgres 实现（架构草案 v0.2 §6.2 第 2 条）。
 *
 * <p>去重靠**主键冲突**，不靠先查后写:先查后写在并发重投下两个连接都会读到「没见过」,
 * 然后各自建档、各自发一把合法的本地幂等键,两笔转账都「合规」。
 * 主键冲突是数据库替我们做的原子判定,绕不过去。
 */
public class JdbcDelegationStore implements DelegationStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcDelegationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Claim> claim(DelegationEnvelope envelope) {
        try {
            jdbc.update("""
                    insert into agent_delegation (
                        delegation_id, tenant_id, source_agent_id, target_agent_id,
                        root_task_id, parent_task_id, source_task_id, trace_id,
                        mode, capability_id, goal, delegation_path, depth, deadline)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """,
                    envelope.delegationId(), envelope.tenantId(), envelope.sourceAgentId(),
                    envelope.targetAgentId(), envelope.rootTaskId(), envelope.parentTaskId(),
                    envelope.sourceTaskId(), envelope.traceId(), envelope.mode().name(),
                    envelope.capabilityId(), envelope.goal(), toJson(envelope.delegationPath()),
                    envelope.depth(),
                    envelope.deadline() == null ? null : Timestamp.from(envelope.deadline()));
            return Optional.empty();

        } catch (DuplicateKeyException e) {
            return Optional.of(readClaim(envelope.delegationId()));
        }
    }

    @Override
    public void settle(String delegationId, DelegationReceipt receipt) {
        // where settled_at is null：只落一次。去掉这个条件，
        // 「返回首次结果」就悄悄变成「返回最后一次结果」
        jdbc.update("""
                update agent_delegation
                   set outcome = ?, facts = ?::jsonb, missing_slots = ?::jsonb,
                       reason_code = ?, settled_at = now()
                 where delegation_id = ? and settled_at is null
                """,
                receipt.outcome().name(), toJson(receipt.facts()), toJson(receipt.missingSlots()),
                receipt.reasonCode(), delegationId);
    }

    private Claim readClaim(String delegationId) {
        return jdbc.queryForObject("""
                select outcome, facts, missing_slots, reason_code, settled_at
                  from agent_delegation where delegation_id = ?
                """,
                (rs, n) -> {
                    boolean settled = rs.getTimestamp("settled_at") != null;
                    if (!settled) {
                        return new Claim(delegationId, false, null);
                    }
                    return new Claim(delegationId, true, new DelegationReceipt(
                            DelegationEnvelope.CURRENT_VERSION, delegationId,
                            DelegationOutcome.valueOf(rs.getString("outcome")),
                            fromJsonMap(rs.getString("facts")),
                            fromJsonSlots(rs.getString("missing_slots")),
                            rs.getString("reason_code"), null));
                }, delegationId);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("委托台账序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("委托台账反序列化失败", e);
        }
    }

    private static List<DelegationReceipt.MissingSlot> fromJsonSlots(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, JSON.getTypeFactory()
                    .constructCollectionType(List.class, DelegationReceipt.MissingSlot.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("委托台账反序列化失败", e);
        }
    }
}
