package com.huawei.finance.orchestrator.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

public class JdbcAgentLoopRepository implements AgentLoopRepository {
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    public JdbcAgentLoopRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc; this.transactions = transactions;
    }

    @Override public Run open(StartRequest r) {
        String id = "loop-" + UUID.randomUUID();
        jdbc.update("""
                insert into agent_loop_run
                (tenant_id,loop_id,agent_id,session_id,root_task_id,trace_id,goal,status,max_iterations,
                 candidate_ids,confirmed_slots,facts,deadline)
                values (?,?,?,?,?,?,?,'NEW',?,cast(? as jsonb),cast(? as jsonb),'{}',?)
                """, r.tenantId(), id, r.agentId(), r.sessionId(), r.rootTaskId(), r.traceId(), r.goal(),
                r.maxIterations(), json(r.candidateIds()), json(r.confirmedSlots()), timestamp(r.deadline()));
        return find(r.tenantId(), r.agentId(), id).orElseThrow();
    }
    @Override public Optional<Run> find(String tenantId, String agentId, String loopId) {
        return jdbc.query("select * from agent_loop_run where tenant_id=? and agent_id=? and loop_id=?",
                JdbcAgentLoopRepository::run, tenantId, agentId, loopId).stream().findFirst();
    }
    @Override public List<Step> steps(String tenantId, String agentId, String loopId) {
        return jdbc.query("select * from agent_loop_step where tenant_id=? and agent_id=? and loop_id=? order by step_index",
                JdbcAgentLoopRepository::step, tenantId, agentId, loopId);
    }
    @Override public Optional<String> reasonCode(String tenantId, String agentId, String loopId) {
        List<String> values = jdbc.query(
                "select reason_code from agent_loop_run where tenant_id=? and agent_id=? and loop_id=?",
                (result, row) -> result.getString(1), tenantId, agentId, loopId);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }
    @Override public boolean propose(String tenantId, String agentId, String loopId, long version, Action action) {
        return transactions.execute(status -> {
            int n = jdbc.update("""
                    update agent_loop_run set pending_action=cast(? as jsonb),status='RUNNING',version=version+1,
                    updated_at=now() where tenant_id=? and agent_id=? and loop_id=? and version=?
                    and status in ('NEW','RUNNING')
                    """, json(action), tenantId, agentId, loopId, version);
            if (n == 0) return false;
            Run current = find(tenantId, agentId, loopId).orElseThrow();
            jdbc.update("""
                    insert into agent_loop_step(tenant_id,agent_id,loop_id,step_index,action,status)
                    values (?,?,?,?,cast(? as jsonb),'PROPOSED')
                    """, tenantId, agentId, loopId, current.iteration(), json(action));
            return true;
        });
    }
    @Override public boolean claim(String tenantId, String agentId, String loopId, int step, long version) {
        return transactions.execute(status -> {
            int run = jdbc.update("""
                    update agent_loop_run set version=version+1,updated_at=now()
                    where tenant_id=? and agent_id=? and loop_id=? and version=? and status='RUNNING'
                    """, tenantId, agentId, loopId, version);
            if (run == 0) return false;
            boolean claimed = jdbc.update("""
                    update agent_loop_step set status='CLAIMED',claimed_at=now()
                    where tenant_id=? and agent_id=? and loop_id=?
                    and step_index=? and status='PROPOSED'
                    """, tenantId, agentId, loopId, step) == 1;
            if (!claimed) status.setRollbackOnly();
            return claimed;
        });
    }
    @Override public boolean recoverClaimed(String tenantId, String agentId, String loopId, int stepIndex,
                                            long version, Instant claimedBefore, String reasonCode) {
        return transactions.execute(status -> {
            int run = jdbc.update("""
                    update agent_loop_run set status='FAILED',pending_action=null,reason_code=?,
                    version=version+1,updated_at=now()
                    where tenant_id=? and agent_id=? and loop_id=? and version=? and status='RUNNING'
                    """, reasonCode, tenantId, agentId, loopId, version);
            if (run == 0) return false;
            int step = jdbc.update("""
                    update agent_loop_step set status='UNKNOWN_OUTCOME',reason_code=?,completed_at=now()
                    where tenant_id=? and agent_id=? and loop_id=? and step_index=? and status='CLAIMED'
                    and coalesce(claimed_at,created_at)<=?
                    """, reasonCode, tenantId, agentId, loopId, stepIndex, timestamp(claimedBefore));
            if (step == 0) status.setRollbackOnly();
            return step == 1;
        });
    }
    @Override public boolean waitForInput(String tenantId, String agentId, String loopId, long version,
                                          List<String> pendingSlots, String reasonCode) {
        return jdbc.update("""
                update agent_loop_run set status='WAITING_USER',pending_slots=cast(? as jsonb),reason_code=?,
                version=version+1,updated_at=now()
                where tenant_id=? and agent_id=? and loop_id=? and version=? and status='RUNNING'
                """, json(pendingSlots), reasonCode, tenantId, agentId, loopId, version) == 1;
    }
    @Override public Run resume(String tenantId, String agentId, String loopId, long version,
                                Status waitingStatus, Map<String,Object> slotUpdates) {
        if (waitingStatus != Status.WAITING_USER && waitingStatus != Status.WAITING_REVIEW
                && waitingStatus != Status.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("LOOP_NOT_WAITING");
        }
        return transactions.execute(status -> {
            Run current = find(tenantId, agentId, loopId).orElseThrow();
            if (current.version() != version || current.status() != waitingStatus) {
                throw new IllegalStateException("LOOP_RESUME_CONFLICT");
            }
            Map<String,Object> confirmed = new LinkedHashMap<>(current.confirmedSlots());
            if (slotUpdates != null) confirmed.putAll(slotUpdates);
            boolean userInput = waitingStatus == Status.WAITING_USER;
            boolean cancelPending = userInput && current.pendingAction() != null;
            int changed = jdbc.update("""
                    update agent_loop_run set status='RUNNING',confirmed_slots=cast(? as jsonb),reason_code=null,
                    pending_slots='[]'::jsonb,pending_action=case when ? then null else pending_action end,
                    iteration=iteration+case when ? then 1 else 0 end,version=version+1,updated_at=now()
                    where tenant_id=? and agent_id=? and loop_id=? and version=? and status=?
                    """, json(confirmed), userInput, cancelPending, tenantId, agentId, loopId, version,
                    waitingStatus.name());
            if (changed == 0) throw new IllegalStateException("LOOP_RESUME_CONFLICT");
            if (cancelPending) {
                int step = jdbc.update("""
                        update agent_loop_step set status='CANCELLED',reason_code='USER_INPUT_RECEIVED',
                        completed_at=now() where tenant_id=? and agent_id=? and loop_id=?
                        and step_index=? and status='PROPOSED'
                        """, tenantId, agentId, loopId, current.iteration());
                if (step == 0) throw new IllegalStateException("LOOP_PENDING_STEP_MISSING");
            }
            return find(tenantId, agentId, loopId).orElseThrow();
        });
    }
    @Override public Run complete(String tenantId, String agentId, String loopId, int stepIndex,
                                  long version, Observation observation, Status nextStatus) {
        return transactions.execute(status -> {
            int step = jdbc.update("""
                    update agent_loop_step set status=?,observation=cast(? as jsonb),reason_code=?,completed_at=now()
                    where tenant_id=? and agent_id=? and loop_id=? and step_index=? and status='CLAIMED'
                    """, switch (observation.status()) {
                        case SUCCESS -> "COMPLETED";
                        case NEED_USER -> "COMPLETED";
                        case PARTIAL -> "UNKNOWN_OUTCOME";
                        case CANCELLED -> "CANCELLED";
                        default -> "FAILED";
                    },
                    json(observation), observation.reasonCode(), tenantId, agentId, loopId, stepIndex);
            if (step == 0) throw new IllegalStateException("LOOP_STEP_NOT_CLAIMED");
            Run run = find(tenantId, agentId, loopId).orElseThrow();
            Map<String,Object> facts = new LinkedHashMap<>(run.facts());
            if (observation.sourceId() != null && !observation.facts().isEmpty()) {
                facts.put(observation.sourceId(), observation.facts());
            }
            int changed = jdbc.update("""
                    update agent_loop_run set status=?,iteration=iteration+1,facts=cast(? as jsonb),reason_code=?,
                    pending_action=null,pending_slots=cast(? as jsonb),version=version+1,updated_at=now()
                    where tenant_id=? and agent_id=? and loop_id=? and version=?
                    """, nextStatus.name(), json(facts), observation.reasonCode(),
                    json(nextStatus == Status.WAITING_USER ? missingSlots(observation) : List.of()),
                    tenantId, agentId, loopId, version);
            if (changed == 0) throw new IllegalStateException("LOOP_VERSION_CONFLICT");
            return find(tenantId, agentId, loopId).orElseThrow();
        });
    }
    @Override public boolean transition(String tenantId, String agentId, String loopId, long version,
                                        Status from, Status to, String reasonCode) {
        return jdbc.update("""
                update agent_loop_run set status=?,reason_code=?,version=version+1,updated_at=now()
                where tenant_id=? and agent_id=? and loop_id=? and version=? and status=?
                """, to.name(), reasonCode, tenantId, agentId, loopId, version, from.name()) == 1;
    }
    private static Run run(ResultSet r, int row) throws SQLException {
        return new Run(r.getString("tenant_id"),r.getString("loop_id"),r.getString("agent_id"),
                r.getString("session_id"),r.getString("root_task_id"),r.getString("trace_id"),r.getString("goal"),
                Status.valueOf(r.getString("status")),r.getInt("iteration"),r.getInt("max_iterations"),
                read(r.getString("candidate_ids"),new TypeReference<List<String>>(){}),
                read(r.getString("confirmed_slots"),new TypeReference<Map<String,Object>>(){}),
                read(r.getString("facts"),new TypeReference<Map<String,Object>>(){}),
                readNullable(r.getString("pending_action"),Action.class),
                read(r.getString("pending_slots"),new TypeReference<List<String>>(){}),
                instant(r,"deadline"),r.getLong("version"),
                instant(r,"created_at"),instant(r,"updated_at"));
    }
    private static Step step(ResultSet r, int row) throws SQLException {
        return new Step(r.getString("loop_id"),r.getInt("step_index"),
                readNullable(r.getString("action"),Action.class),StepStatus.valueOf(r.getString("status")),
                r.getString("task_id"),r.getString("delegation_id"),
                readNullable(r.getString("observation"),Observation.class),r.getString("reason_code"),
                instant(r,"created_at"),instant(r,"completed_at"));
    }
    private static String json(Object value) { try { return ContractJson.mapper().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); } }
    private static <T> T read(String value, TypeReference<T> type) { try { return ContractJson.mapper().readValue(value,type); }
        catch (Exception e) { throw new IllegalStateException(e); } }
    private static <T> T readNullable(String value, Class<T> type) { if(value==null) return null;
        try { return ContractJson.mapper().readValue(value,type); } catch(Exception e){ throw new IllegalStateException(e); } }
    private static List<String> missingSlots(Observation observation) {
        Object value = observation.displayHints().get("missingSlots");
        if (value instanceof Iterable<?> items) {
            List<String> slots = new java.util.ArrayList<>();
            items.forEach(item -> { if (item != null && !String.valueOf(item).isBlank()) slots.add(String.valueOf(item)); });
            if (!slots.isEmpty()) return List.copyOf(slots);
        }
        return List.of("userResponse");
    }
    private static Instant instant(ResultSet r,String name)throws SQLException{var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
}
