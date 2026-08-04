package com.huawei.finance.orchestrator.context;

import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

public class JdbcTaskContextStore implements TaskContextStore {
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcTaskContextStore(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public PlatformTask reserveTask(String tenantId, String agentId, String sessionId,
                                    String routeDecisionId, RouteTarget target, RuntimeType runtimeType) {
        Optional<PlatformTask> existing = jdbc.query("""
                select * from agent_platform_task where tenant_id=? and agent_id=? and route_decision_id=?
                """, JdbcTaskContextStore::task, tenantId, agentId, routeDecisionId).stream().findFirst();
        if (existing.isPresent()) return existing.get();
        String id = "pt-" + UUID.randomUUID();
        jdbc.update("""
                insert into agent_platform_task
                (tenant_id,platform_task_id,agent_id,session_id,route_target_type,route_target_id,
                 runtime_type,status,binding_state,route_decision_id)
                values (?,?,?,?,?,?,?,?,?,?)
                """, tenantId, id, agentId, sessionId, target == null ? null : target.type().name(),
                target == null ? null : target.id(), runtimeType.name(), PlatformTaskStatus.OPEN.name(),
                BindingState.RESERVED.name(), routeDecisionId);
        return task(tenantId, agentId, id).orElseThrow();
    }

    @Override public Optional<PlatformTask> task(String tenantId, String agentId, String id) {
        return jdbc.query("select * from agent_platform_task where tenant_id=? and agent_id=? and platform_task_id=?",
                JdbcTaskContextStore::task, tenantId, agentId, id).stream().findFirst();
    }

    @Override
    public PlatformTask bindRuntime(String tenantId, String agentId, String id, RuntimeType type,
                                    String ref, long version) {
        int n = jdbc.update("""
                update agent_platform_task set runtime_ref=?, binding_state='BOUND', version=version+1,
                updated_at=now() where tenant_id=? and agent_id=? and platform_task_id=? and runtime_type=?
                and status='OPEN' and binding_state='RESERVED' and version=?
                """, ref, tenantId, agentId, id, type.name(), version);
        if (n == 0) {
            PlatformTask current = task(tenantId, agentId, id).orElseThrow();
            if (current.bindingState() == BindingState.BOUND && ref.equals(current.runtimeRef())) return current;
            throw conflict("TASK_BINDING_CONFLICT");
        }
        return task(tenantId, agentId, id).orElseThrow();
    }

    @Override
    public PlatformTask bindRuntimeAndFocus(String tenantId, String agentId, String sessionId,
                                            String id, RuntimeType type, String ref, long version,
                                            String pendingGoalId, String routeDecisionId) {
        return transactions.execute(status -> {
            PlatformTask bound = bindRuntime(tenantId, agentId, id, type, ref, version);
            FocusView view = focus(tenantId, agentId, sessionId);
            if (pendingGoalId == null) {
                if (view.foreground() != null) throw conflict("FOREGROUND_ALREADY_EXISTS");
                createTaskForeground(tenantId, agentId, sessionId, id);
                return bound;
            }

            FocusFrame foreground = view.foreground();
            if (foreground == null || foreground.subjectType() != FocusSubjectType.PENDING_GOAL
                    || !pendingGoalId.equals(foreground.subjectRef())) {
                throw conflict("PENDING_GOAL_FOCUS_CONFLICT");
            }
            PendingGoal goal = pendingGoal(tenantId, agentId, pendingGoalId).orElseThrow();
            PendingGoal starting = transitionPendingGoal(tenantId, agentId, pendingGoalId,
                    PendingGoalState.ROUTING, PendingGoalState.STARTING_RUNTIME,
                    routeDecisionId, null, goal.version());
            bindPendingGoal(tenantId, agentId, foreground.frameId(), pendingGoalId,
                    bound.platformTaskId(), foreground.version());
            transitionPendingGoal(tenantId, agentId, pendingGoalId,
                    PendingGoalState.STARTING_RUNTIME, PendingGoalState.BOUND,
                    routeDecisionId, bound.platformTaskId(), starting.version());
            return bound;
        });
    }

    @Override
    public void failRuntimeRegistration(String tenantId, String agentId, String sessionId,
                                        String platformTaskId, String pendingGoalId, String reason) {
        transactions.executeWithoutResult(status -> {
            if (platformTaskId != null) {
                PlatformTask current = task(tenantId, agentId, platformTaskId).orElse(null);
                if (current != null && current.status() == PlatformTaskStatus.OPEN) {
                    closeTask(tenantId, agentId, platformTaskId, reason, current.version());
                }
            }
            if (pendingGoalId == null) return;
            PendingGoal goal = pendingGoal(tenantId, agentId, pendingGoalId).orElse(null);
            if (goal == null || goal.state() == PendingGoalState.COMPLETED
                    || goal.state() == PendingGoalState.FAILED) return;
            PendingGoal failed = transitionPendingGoal(tenantId, agentId, pendingGoalId, goal.state(),
                    PendingGoalState.FAILED, goal.routeDecisionId(), goal.boundPlatformTaskId(), goal.version());
            FocusFrame foreground = focus(tenantId, agentId, sessionId).foreground();
            if (foreground != null && foreground.subjectType() == FocusSubjectType.PENDING_GOAL
                    && pendingGoalId.equals(foreground.subjectRef())) {
                failPendingGoalAndRestore(tenantId, agentId, sessionId, foreground.frameId(),
                        foreground.version(), failed.previousFrameId());
            }
        });
    }

    @Override
    public PlatformTask closeTask(String tenantId, String agentId, String id, String reason, long version) {
        int n = jdbc.update("""
                update agent_platform_task set status='CLOSED', close_reason=?, version=version+1, updated_at=now()
                where tenant_id=? and agent_id=? and platform_task_id=? and status='OPEN' and version=?
                """, reason, tenantId, agentId, id, version);
        if (n == 0) throw conflict("TASK_VERSION_CONFLICT");
        return task(tenantId, agentId, id).orElseThrow();
    }

    @Override public FocusView focus(String tenantId, String agentId, String sessionId) {
        List<FocusFrame> all = jdbc.query("""
                select * from agent_conversation_focus where tenant_id=? and agent_id=? and session_id=?
                and focus_state in ('FOREGROUND','SUSPENDED') order by suspended_at desc nulls first
                """, JdbcTaskContextStore::focus, tenantId, agentId, sessionId);
        FocusFrame foreground = all.stream().filter(f -> f.state() == FocusState.FOREGROUND).findFirst().orElse(null);
        return new FocusView(foreground, all.stream().filter(f -> f.state() == FocusState.SUSPENDED).toList());
    }

    @Override
    public FocusFrame createTaskForeground(String tenantId, String agentId, String sessionId, String taskId) {
        String frameId = "frame-" + UUID.randomUUID();
        jdbc.update("""
                insert into agent_conversation_focus
                (tenant_id,frame_id,agent_id,session_id,subject_type,subject_ref,focus_state,last_focused_at)
                values (?,?,?,?,? ,?,'FOREGROUND',now())
                """, tenantId, frameId, agentId, sessionId, FocusSubjectType.PLATFORM_TASK.name(), taskId);
        return frame(tenantId, agentId, frameId).orElseThrow();
    }

    @Override
    public FocusTransition switchToPendingGoal(String tenantId, String agentId, String sessionId,
                                               String frameId, long version, String pendingGoalId) {
        return transactions.execute(status -> {
            FocusFrame previous = frame(tenantId, agentId, frameId).orElseThrow();
            int n = jdbc.update("""
                    update agent_conversation_focus set focus_state='SUSPENDED', suspended_at=now(),
                    version=version+1 where tenant_id=? and agent_id=? and session_id=? and frame_id=?
                    and focus_state='FOREGROUND' and version=?
                    """, tenantId, agentId, sessionId, frameId, version);
            if (n == 0) throw conflict("FOCUS_VERSION_CONFLICT");
            String nextId = "frame-" + UUID.randomUUID();
            jdbc.update("""
                    insert into agent_conversation_focus
                    (tenant_id,frame_id,agent_id,session_id,subject_type,subject_ref,focus_state,last_focused_at)
                    values (?,?,?,?,? ,?,'FOREGROUND',now())
                    """, tenantId, nextId, agentId, sessionId, FocusSubjectType.PENDING_GOAL.name(), pendingGoalId);
            FocusFrame next = frame(tenantId, agentId, nextId).orElseThrow();
            return new FocusTransition(previous, next, focus(tenantId, agentId, sessionId));
        });
    }

    @Override
    public FocusFrame bindPendingGoal(String tenantId, String agentId, String frameId,
                                      String pendingGoalId, String taskId, long version) {
        int n = jdbc.update("""
                update agent_conversation_focus set subject_type='PLATFORM_TASK', subject_ref=?, version=version+1
                where tenant_id=? and agent_id=? and frame_id=? and subject_type='PENDING_GOAL'
                and subject_ref=? and focus_state='FOREGROUND' and version=?
                """, taskId, tenantId, agentId, frameId, pendingGoalId, version);
        if (n == 0) throw conflict("PENDING_GOAL_FOCUS_CONFLICT");
        return frame(tenantId, agentId, frameId).orElseThrow();
    }

    @Override
    public FocusTransition failPendingGoalAndRestore(String tenantId, String agentId, String sessionId,
                                                     String frameId, long version, String previousFrameId) {
        return transactions.execute(status -> {
            FocusFrame failed = closeFrame(tenantId, agentId, frameId, version);
            FocusFrame previous = frame(tenantId, agentId, previousFrameId).orElse(null);
            FocusFrame restored = null;
            if (previous != null && previous.state() == FocusState.SUSPENDED) {
                restored = resume(tenantId, agentId, sessionId, previousFrameId, previous.version());
            }
            return new FocusTransition(failed, restored, focus(tenantId, agentId, sessionId));
        });
    }

    @Override public FocusFrame closeFrame(String tenantId, String agentId, String frameId, long version) {
        int n = jdbc.update("""
                update agent_conversation_focus set focus_state='CLOSED', closed_at=now(), version=version+1
                where tenant_id=? and agent_id=? and frame_id=? and focus_state<>'CLOSED' and version=?
                """, tenantId, agentId, frameId, version);
        if (n == 0) throw conflict("FOCUS_VERSION_CONFLICT");
        return frame(tenantId, agentId, frameId).orElseThrow();
    }

    @Override public FocusFrame resume(String tenantId, String agentId, String sessionId, String frameId, long version) {
        int n = jdbc.update("""
                update agent_conversation_focus set focus_state='FOREGROUND', last_focused_at=now(),
                suspended_at=null, version=version+1 where tenant_id=? and agent_id=? and session_id=?
                and frame_id=? and focus_state='SUSPENDED' and version=?
                """, tenantId, agentId, sessionId, frameId, version);
        if (n == 0) throw conflict("FOCUS_VERSION_CONFLICT");
        return frame(tenantId, agentId, frameId).orElseThrow();
    }

    @Override public PendingSwitch proposeSwitch(PendingSwitch v) {
        jdbc.update("update agent_pending_switch set state='STALE',resolved_at=now(),version=version+1 where tenant_id=? and agent_id=? and session_id=? and state='PENDING'",
                v.tenantId(), v.agentId(), v.sessionId());
        jdbc.update("""
                insert into agent_pending_switch
                (tenant_id,switch_id,agent_id,session_id,foreground_frame_id,foreground_frame_version,
                 source_turn_id,span_start,span_end,span_hash,state) values (?,?,?,?,?,?,?,?,?,?,'PENDING')
                """, v.tenantId(), v.switchId(), v.agentId(), v.sessionId(), v.foregroundFrameId(),
                v.foregroundFrameVersion(), v.sourceTurnId(), v.spanStart(), v.spanEnd(), v.spanHash());
        return pendingSwitch(v.tenantId(), v.agentId(), v.sessionId()).orElseThrow();
    }

    @Override public Optional<PendingSwitch> pendingSwitch(String tenantId, String agentId, String sessionId) {
        return jdbc.query("select * from agent_pending_switch where tenant_id=? and agent_id=? and session_id=? and state='PENDING'",
                JdbcTaskContextStore::pendingSwitch, tenantId, agentId, sessionId).stream().findFirst();
    }

    @Override public PendingSwitch resolveSwitch(String tenantId, String agentId, String id,
                                                 SwitchState state, String turn, long version) {
        int n = jdbc.update("""
                update agent_pending_switch set state=?,resolved_turn_id=?,resolved_at=now(),version=version+1
                where tenant_id=? and agent_id=? and switch_id=? and state='PENDING' and version=?
                """, state.name(), turn, tenantId, agentId, id, version);
        if (n == 0) throw conflict("SWITCH_VERSION_CONFLICT");
        return switchById(tenantId, agentId, id).orElseThrow();
    }

    @Override
    public PendingGoal acceptSwitchAtomically(PendingSwitch pending, PendingGoal goal,
                                              String resolvedTurnId, long switchVersion) {
        return transactions.execute(status -> {
            resolveSwitch(pending.tenantId(), pending.agentId(), pending.switchId(),
                    SwitchState.ACCEPTED, resolvedTurnId, switchVersion);
            PendingGoal created = createPendingGoal(goal);
            switchToPendingGoal(pending.tenantId(), pending.agentId(), pending.sessionId(),
                    pending.foregroundFrameId(), pending.foregroundFrameVersion(), created.pendingGoalId());
            return created;
        });
    }

    @Override public PendingGoal createPendingGoal(PendingGoal v) {
        jdbc.update("""
                insert into agent_pending_goal
                (tenant_id,pending_goal_id,agent_id,session_id,switch_id,previous_frame_id,source_turn_id,
                 span_start,span_end,span_hash,state) values (?,?,?,?,?,?,?,?,?,?,'ROUTING')
                """, v.tenantId(), v.pendingGoalId(), v.agentId(), v.sessionId(), v.switchId(),
                v.previousFrameId(), v.sourceTurnId(), v.spanStart(), v.spanEnd(), v.spanHash());
        return pendingGoal(v.tenantId(), v.agentId(), v.pendingGoalId()).orElseThrow();
    }

    @Override public Optional<PendingGoal> pendingGoal(String tenantId, String agentId, String id) {
        return jdbc.query("select * from agent_pending_goal where tenant_id=? and agent_id=? and pending_goal_id=?",
                JdbcTaskContextStore::pendingGoal, tenantId, agentId, id).stream().findFirst();
    }

    @Override public PendingGoal transitionPendingGoal(String tenantId, String agentId, String id,
                                                       PendingGoalState from, PendingGoalState to,
                                                       String decisionId, String taskId, long version) {
        int n = jdbc.update("""
                update agent_pending_goal set state=?,route_decision_id=coalesce(?,route_decision_id),
                bound_platform_task_id=coalesce(?,bound_platform_task_id),version=version+1,updated_at=now()
                where tenant_id=? and agent_id=? and pending_goal_id=? and state=? and version=?
                """, to.name(), decisionId, taskId, tenantId, agentId, id, from.name(), version);
        if (n == 0) throw conflict("PENDING_GOAL_VERSION_CONFLICT");
        return pendingGoal(tenantId, agentId, id).orElseThrow();
    }

    private Optional<FocusFrame> frame(String tenantId, String agentId, String id) {
        return jdbc.query("select * from agent_conversation_focus where tenant_id=? and agent_id=? and frame_id=?",
                JdbcTaskContextStore::focus, tenantId, agentId, id).stream().findFirst();
    }
    private Optional<PendingSwitch> switchById(String tenantId, String agentId, String id) {
        return jdbc.query("select * from agent_pending_switch where tenant_id=? and agent_id=? and switch_id=?",
                JdbcTaskContextStore::pendingSwitch, tenantId, agentId, id).stream().findFirst();
    }
    private static PlatformTask task(ResultSet r, int row) throws SQLException {
        String tt = r.getString("route_target_type");
        RouteTarget target = tt == null ? null : new RouteTarget(RouteTarget.Type.valueOf(tt), r.getString("route_target_id"));
        return new PlatformTask(r.getString("tenant_id"), r.getString("platform_task_id"), r.getString("agent_id"),
                r.getString("session_id"), target, RuntimeType.valueOf(r.getString("runtime_type")),
                r.getString("runtime_ref"), PlatformTaskStatus.valueOf(r.getString("status")),
                BindingState.valueOf(r.getString("binding_state")), r.getString("close_reason"),
                r.getString("route_decision_id"), r.getLong("version"), instant(r,"created_at"), instant(r,"updated_at"));
    }
    private static FocusFrame focus(ResultSet r, int row) throws SQLException {
        return new FocusFrame(r.getString("tenant_id"), r.getString("frame_id"), r.getString("agent_id"),
                r.getString("session_id"), FocusSubjectType.valueOf(r.getString("subject_type")),
                r.getString("subject_ref"), FocusState.valueOf(r.getString("focus_state")), r.getLong("version"),
                instant(r,"last_focused_at"), instant(r,"suspended_at"), instant(r,"closed_at"));
    }
    private static PendingSwitch pendingSwitch(ResultSet r, int row) throws SQLException {
        return new PendingSwitch(r.getString("tenant_id"), r.getString("switch_id"), r.getString("agent_id"),
                r.getString("session_id"), r.getString("foreground_frame_id"), r.getLong("foreground_frame_version"),
                r.getString("source_turn_id"), r.getInt("span_start"), r.getInt("span_end"), r.getString("span_hash"),
                SwitchState.valueOf(r.getString("state")), r.getString("resolved_turn_id"), r.getLong("version"),
                instant(r,"created_at"), instant(r,"resolved_at"));
    }
    private static PendingGoal pendingGoal(ResultSet r, int row) throws SQLException {
        return new PendingGoal(r.getString("tenant_id"), r.getString("pending_goal_id"), r.getString("agent_id"),
                r.getString("session_id"), r.getString("switch_id"), r.getString("previous_frame_id"),
                r.getString("source_turn_id"), r.getInt("span_start"), r.getInt("span_end"), r.getString("span_hash"),
                PendingGoalState.valueOf(r.getString("state")), r.getString("route_decision_id"),
                r.getString("bound_platform_task_id"), r.getLong("version"), instant(r,"created_at"), instant(r,"updated_at"));
    }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        var t = r.getTimestamp(name); return t == null ? null : t.toInstant();
    }
    private static OptimisticLockingFailureException conflict(String code) {
        return new OptimisticLockingFailureException(code);
    }
}
