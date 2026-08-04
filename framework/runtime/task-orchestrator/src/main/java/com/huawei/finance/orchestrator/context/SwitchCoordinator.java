package com.huawei.finance.orchestrator.context;

import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public class SwitchCoordinator {
    private final TaskContextStore store;
    private final PlatformTaskContextManager tasks;
    private final MeterRegistry meters;

    public SwitchCoordinator(TaskContextStore store, PlatformTaskContextManager tasks) {
        this(store, tasks, null);
    }
    public SwitchCoordinator(TaskContextStore store, PlatformTaskContextManager tasks, MeterRegistry meters) {
        this.store = store; this.tasks = tasks; this.meters = meters;
    }

    public PendingSwitch propose(String tenantId, String agentId, String sessionId,
                                 String sourceTurnId, String newGoal, int start, int end) {
        FocusFrame foreground = store.focus(tenantId, agentId, sessionId).foreground();
        if (foreground == null) throw new PlatformTaskContextManager.TaskContextConflict("NO_FOREGROUND");
        PendingSwitch pending = new PendingSwitch(tenantId, UUID.randomUUID().toString(), agentId, sessionId,
                foreground.frameId(), foreground.version(), sourceTurnId, start, end, hash(newGoal),
                SwitchState.PENDING, null, 0, Instant.now(), null);
        PendingSwitch saved=store.proposeSwitch(pending);
        metric("NONE", SwitchState.PENDING.name());
        return saved;
    }

    public PendingGoal accept(String tenantId, String agentId, String sessionId,
                              String switchId, long switchVersion, String resolvedTurnId) {
        PendingSwitch pending = store.pendingSwitch(tenantId, agentId, sessionId)
                .filter(s -> s.switchId().equals(switchId)).orElseThrow();
        if (store.focus(tenantId, agentId, sessionId).suspended().size()
                >= PlatformTaskContextManager.MAX_SUSPENDED) {
            throw new PlatformTaskContextManager.TaskContextConflict("SUSPENDED_TASK_LIMIT");
        }
        String goalId = UUID.randomUUID().toString();
        PendingGoal proposedGoal = new PendingGoal(tenantId, goalId, agentId, sessionId,
                switchId, pending.foregroundFrameId(), pending.sourceTurnId(), pending.spanStart(), pending.spanEnd(),
                pending.spanHash(), PendingGoalState.ROUTING, null, null, 0, Instant.now(), Instant.now());
        PendingGoal goal = store.acceptSwitchAtomically(pending, proposedGoal, resolvedTurnId, switchVersion);
        metric(SwitchState.PENDING.name(), SwitchState.ACCEPTED.name());
        return goal;
    }

    public PendingSwitch reject(String tenantId, String agentId, String sessionId,
                                String switchId, long version, String resolvedTurnId) {
        PendingSwitch rejected=store.resolveSwitch(tenantId, agentId, switchId, SwitchState.REJECTED, resolvedTurnId, version);
        metric(SwitchState.PENDING.name(), SwitchState.REJECTED.name());
        return rejected;
    }

    private void metric(String from, String to) {
        if (meters != null) meters.counter(AgentMetrics.SWITCH_TRANSITION,
                AgentMetrics.TAG_FROM, from, AgentMetrics.TAG_TO, to).increment();
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
