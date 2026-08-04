package com.huawei.finance.context.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.context.ContextUnavailableException;
import com.huawei.finance.context.ConversationTurn;
import com.huawei.finance.context.TurnStore;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.validation.ContractJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 轮次落盘。真值在这里，Redis 只是它前面的一层热读。
 *
 * <p>序号用 {@code select coalesce(max(seq), -1) + 1} 在同一条 insert 语句里取，不先查后写：
 * 同会话并发时先查后写会拿到同一个序号，撞上唯一索引其中一条直接丢失——
 * 丢的那轮恰恰是并发那轮，也就是最可能出问题的那轮。
 */
public class JdbcTurnStore implements TurnStore {

    private final JdbcTemplate jdbc;

    public JdbcTurnStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversationTurn append(ConversationTurn turn) {
        try {
            Long seq = jdbc.queryForObject("""
                    insert into agent_conversation_turn
                        (tenant_id, agent_id, session_id, seq, trace_id, task_id, user_text, decision, reason_code,
                         capability_id, outcome, pending, pending_options, facts, at, messages)
                    select ?, ?, ?, coalesce(max(seq), -1) + 1, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb),
                           cast(? as jsonb), ?, cast(? as jsonb)
                    from agent_conversation_turn where tenant_id = ? and agent_id = ? and session_id = ?
                    returning seq
                    """, Long.class,
                    turn.tenantId(), turn.agentId(), turn.sessionId(), turn.traceId(), turn.taskId(), turn.userText(),
                    name(turn.decision()), name(turn.reasonCode()), turn.capabilityId(),
                    name(turn.outcome()), turn.pending().name(), json(turn.pendingOptions()),
                    json(turn.facts()), java.sql.Timestamp.from(turn.at()), json(turn.messages()),
                    turn.tenantId(), turn.agentId(), turn.sessionId());

            return new ConversationTurn(turn.tenantId(), turn.agentId(), turn.sessionId(), seq == null ? 0 : seq,
                    turn.traceId(), turn.taskId(), turn.userText(), turn.decision(), turn.reasonCode(),
                    turn.capabilityId(), turn.outcome(), turn.pending(), turn.pendingOptions(),
                    turn.facts(), turn.at(), turn.messages());
        } catch (RuntimeException e) {
            throw new ContextUnavailableException("轮次落盘失败 session=" + turn.sessionId(), e);
        }
    }

    @Override
    public List<ConversationTurn> recent(String tenantId, String agentId, String sessionId, int limit) {
        try {
            List<ConversationTurn> desc = jdbc.query("""
                    select * from agent_conversation_turn
                    where tenant_id = ? and agent_id = ? and session_id = ? order by seq desc limit ?
                    """, MAPPER, tenantId, agentId, sessionId, limit);
            List<ConversationTurn> asc = new ArrayList<>(desc);
            java.util.Collections.reverse(asc);
            return List.copyOf(asc);
        } catch (RuntimeException e) {
            throw new ContextUnavailableException("轮次读取失败 session=" + sessionId, e);
        }
    }

    private static final RowMapper<ConversationTurn> MAPPER = JdbcTurnStore::mapRow;

    private static ConversationTurn mapRow(ResultSet rs, int rowNum) throws SQLException {
        String agentId = rs.getString("agent_id");
        if (agentId == null || agentId.isBlank()) {
            agentId = RequestContext.AGENT_ENTRY;
        }
        return new ConversationTurn(
                rs.getString("tenant_id"),
                agentId,
                rs.getString("session_id"),
                rs.getLong("seq"),
                rs.getString("trace_id"),
                rs.getString("task_id"),
                rs.getString("user_text"),
                enumOf(Decision.class, rs.getString("decision")),
                enumOf(ReasonCode.class, rs.getString("reason_code")),
                rs.getString("capability_id"),
                enumOf(Enums.ToolOutcome.class, rs.getString("outcome")),
                enumOf(Enums.PendingAction.class, rs.getString("pending")),
                readList(rs.getString("pending_options")),
                readMap(rs.getString("facts")),
                rs.getTimestamp("at").toInstant(),
                readMessages(rs.getString("messages")));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String json(Object value) {
        try {
            return ContractJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("轮次字段序列化失败", e);
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("轮次列表字段反序列化失败", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("轮次 facts 反序列化失败", e);
        }
    }

    private static List<ConversationTurn.Message> readMessages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return ContractJson.mapper().readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("轮次消息字段反序列化失败", e);
        }
    }
}
