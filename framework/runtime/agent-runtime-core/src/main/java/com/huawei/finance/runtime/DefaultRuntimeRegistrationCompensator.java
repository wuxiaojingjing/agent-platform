package com.huawei.finance.runtime;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.Run;
import com.huawei.finance.orchestrator.loop.LoopContracts.Status;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import java.util.Optional;

/** Compensates through each Runtime's own repository; it never copies Runtime state into platform tables. */
public class DefaultRuntimeRegistrationCompensator implements RuntimeRegistrationCompensator {
    private static final String TRACE_SUFFIX = ":platform-registration-compensation";
    private final TaskRepository tasks;
    private final IntentPlanRepository plans;
    private final Optional<AgentLoopRepository> loops;

    public DefaultRuntimeRegistrationCompensator(TaskRepository tasks, IntentPlanRepository plans,
                                                 Optional<AgentLoopRepository> loops) {
        this.tasks = tasks;
        this.plans = plans;
        this.loops = loops;
    }

    @Override
    public void compensate(RequestContext context, RuntimeType type, String runtimeRef, String reason) {
        if (runtimeRef == null) return;
        switch (type) {
            case TASK, WORKFLOW -> cancelTask(context, runtimeRef, reason);
            case STATIC_PLAN -> abandonPlan(context, runtimeRef, reason);
            case AGENT_LOOP -> cancelLoop(context, runtimeRef, reason);
        }
    }

    private void cancelTask(RequestContext context, String taskId, String reason) {
        TaskRecord task = tasks.findById(taskId).orElse(null);
        if (task != null && task.state().active()) {
            tasks.transition(taskId, task.state(), TaskState.CANCELLED, reason,
                    context.traceId() + TRACE_SUFFIX);
        }
    }

    private void abandonPlan(RequestContext context, String planId, String reason) {
        plans.findById(context.agentId(), planId)
                .filter(plan -> plan.state() == PlanState.IN_PROGRESS
                        || plan.state() == PlanState.WAITING_USER
                        || plan.state() == PlanState.WAITING_REVIEW
                        || plan.state() == PlanState.WAITING_CONFIRMATION)
                .ifPresent(plan -> plans.abandonActive(context.agentId(), context.sessionId(), reason));
    }

    private void cancelLoop(RequestContext context, String loopId, String reason) {
        AgentLoopRepository repository = loops.orElse(null);
        if (repository == null) return;
        Run run = repository.find(context.spaceId(), context.agentId(), loopId).orElse(null);
        if (run != null && !run.terminal()) {
            repository.transition(context.spaceId(), context.agentId(), loopId, run.version(),
                    run.status(), Status.CANCELLED, reason);
        }
    }
}
