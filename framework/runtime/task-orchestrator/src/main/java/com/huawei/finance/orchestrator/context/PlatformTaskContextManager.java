package com.huawei.finance.orchestrator.context;

import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import java.util.Objects;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public class PlatformTaskContextManager {
    public static final int MAX_SUSPENDED = 3;
    private final TaskContextStore store;
    private final MeterRegistry meters;

    public PlatformTaskContextManager(TaskContextStore store) {
        this(store, null);
    }

    public PlatformTaskContextManager(TaskContextStore store, MeterRegistry meters) {
        this.store = Objects.requireNonNull(store); this.meters = meters;
    }

    public PlatformTask reserve(String tenantId, String agentId, String sessionId,
                                String routeDecisionId, RouteTarget target, RuntimeType runtimeType) {
        return store.reserveTask(tenantId, agentId, sessionId, routeDecisionId, target, runtimeType);
    }

    public PlatformTask bind(String tenantId, String agentId, String platformTaskId,
                             RuntimeType runtimeType, String runtimeRef, long expectedVersion) {
        return store.bindRuntime(tenantId, agentId, platformTaskId, runtimeType, runtimeRef, expectedVersion);
    }

    public PlatformTask bindAndFocus(String tenantId, String agentId, String sessionId,
                                     String platformTaskId, RuntimeType runtimeType, String runtimeRef,
                                     long expectedVersion, String pendingGoalId, String routeDecisionId) {
        return store.bindRuntimeAndFocus(tenantId, agentId, sessionId, platformTaskId, runtimeType,
                runtimeRef, expectedVersion, pendingGoalId, routeDecisionId);
    }

    public FocusFrame foreground(String tenantId, String agentId, String sessionId,
                                 String platformTaskId) {
        FocusView view = store.focus(tenantId, agentId, sessionId);
        if (view.foreground() != null) throw new TaskContextConflict("FOREGROUND_ALREADY_EXISTS");
        FocusFrame frame=store.createTaskForeground(tenantId, agentId, sessionId, platformTaskId);
        focusMetric("NONE", FocusState.FOREGROUND.name());
        return frame;
    }

    public FocusTransition switchFocus(String tenantId, String agentId, String sessionId,
                                       String frameId, long version, String pendingGoalId) {
        FocusView view = store.focus(tenantId, agentId, sessionId);
        if (view.suspended().size() >= MAX_SUSPENDED) {
            throw new TaskContextConflict("SUSPENDED_TASK_LIMIT");
        }
        FocusTransition transition=store.switchToPendingGoal(tenantId, agentId, sessionId, frameId, version, pendingGoalId);
        focusMetric(FocusState.FOREGROUND.name(), FocusState.SUSPENDED.name());
        return transition;
    }

    public FocusFrame resume(String tenantId, String agentId, String sessionId,
                             String frameId, long version) {
        if (store.focus(tenantId, agentId, sessionId).foreground() != null) {
            throw new TaskContextConflict("FOCUS_SWITCH_REQUIRED");
        }
        FocusFrame frame=store.resume(tenantId, agentId, sessionId, frameId, version);
        focusMetric(FocusState.SUSPENDED.name(), FocusState.FOREGROUND.name());
        return frame;
    }

    private void focusMetric(String from, String to) {
        if (meters != null) meters.counter(AgentMetrics.FOCUS_TRANSITION,
                AgentMetrics.TAG_FROM, from, AgentMetrics.TAG_TO, to).increment();
    }

    public static final class TaskContextConflict extends RuntimeException {
        private final String reasonCode;
        public TaskContextConflict(String reasonCode) { super(reasonCode); this.reasonCode = reasonCode; }
        public String reasonCode() { return reasonCode; }
    }
}
