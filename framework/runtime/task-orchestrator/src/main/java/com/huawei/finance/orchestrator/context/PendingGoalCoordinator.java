package com.huawei.finance.orchestrator.context;

import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public class PendingGoalCoordinator {
    private final TaskContextStore store;
    private final MeterRegistry meters;
    public PendingGoalCoordinator(TaskContextStore store) { this(store, null); }
    public PendingGoalCoordinator(TaskContextStore store, MeterRegistry meters) { this.store = store; this.meters = meters; }

    public PendingGoal startRuntime(String tenantId, String agentId, String pendingGoalId,
                                    String routeDecisionId, long version) {
        PendingGoal goal=store.transitionPendingGoal(tenantId, agentId, pendingGoalId, PendingGoalState.ROUTING,
                PendingGoalState.STARTING_RUNTIME, routeDecisionId, null, version);
        metric(PendingGoalState.ROUTING, PendingGoalState.STARTING_RUNTIME); return goal;
    }

    public PendingGoal bind(String tenantId, String agentId, String pendingGoalId,
                            String platformTaskId, long version) {
        PendingGoal goal=store.transitionPendingGoal(tenantId, agentId, pendingGoalId, PendingGoalState.STARTING_RUNTIME,
                PendingGoalState.BOUND, null, platformTaskId, version);
        metric(PendingGoalState.STARTING_RUNTIME, PendingGoalState.BOUND); return goal;
    }

    public PendingGoal completeDirect(String tenantId, String agentId, String pendingGoalId,
                                      String routeDecisionId, long version) {
        PendingGoal goal=store.transitionPendingGoal(tenantId, agentId, pendingGoalId, PendingGoalState.ROUTING,
                PendingGoalState.COMPLETED, routeDecisionId, null, version);
        metric(PendingGoalState.ROUTING, PendingGoalState.COMPLETED); return goal;
    }

    public PendingGoal fail(String tenantId, String agentId, PendingGoal goal) {
        PendingGoal failed=store.transitionPendingGoal(tenantId, agentId, goal.pendingGoalId(), goal.state(),
                PendingGoalState.FAILED, goal.routeDecisionId(), goal.boundPlatformTaskId(), goal.version());
        metric(goal.state(), PendingGoalState.FAILED); return failed;
    }
    private void metric(PendingGoalState from, PendingGoalState to) {
        if (meters != null) meters.counter(AgentMetrics.PENDING_GOAL_TRANSITION,
                AgentMetrics.TAG_FROM, from.name(), AgentMetrics.TAG_TO, to.name()).increment();
    }
}
