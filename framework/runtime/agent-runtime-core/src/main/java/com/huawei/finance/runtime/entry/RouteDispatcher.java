package com.huawei.finance.runtime.entry;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.orchestrator.context.TaskContextModels.PlatformTask;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.runtime.PlatformRuntimeBridge;
import java.util.Optional;

public class RouteDispatcher {
    private final Optional<PlatformRuntimeBridge> platform;

    public RouteDispatcher() {
        this(Optional.empty());
    }

    public RouteDispatcher(Optional<PlatformRuntimeBridge> platform) {
        this.platform = platform == null ? Optional.empty() : platform;
    }

    public Handler handler(Decision decision){return switch(decision){
        case DIRECT_KNOWLEDGE->Handler.KNOWLEDGE;case NAVIGATION->Handler.NAVIGATION;
        case EXECUTE_CAPABILITY,RESUME_TASK->Handler.TASK;case START_WORKFLOW->Handler.WORKFLOW;
        case STATIC_PLAN->Handler.STATIC_PLAN;case DELEGATE_GOAL->Handler.DELEGATION;
        case START_LOOP,RESUME_LOOP->Handler.AGENT_LOOP;case CLARIFY->Handler.CLARIFY;
        case CANCEL->Handler.CANCEL;case REJECT->Handler.REJECT;case HANDOFF->Handler.HANDOFF;};}

    public boolean hasPlatformRegistry() {
        return platform.isPresent();
    }

    public Optional<PlatformTask> reserve(RequestContext context, RouteDecision decision, RuntimeType type) {
        return platform.map(bridge -> bridge.reserve(context, decision, type));
    }

    public Optional<PlatformTask> bindAndFocus(RequestContext context, PlatformTask reserved,
                                               RuntimeType type, String runtimeRef,
                                               String pendingGoalId) {
        return platform.map(bridge -> bridge.bindAndFocus(
                context, reserved, type, runtimeRef, pendingGoalId));
    }

    public void failReservation(RequestContext context, PlatformTask reserved,
                                String pendingGoalId, String reason) {
        platform.ifPresent(bridge -> bridge.failReservedRuntime(
                context, reserved, pendingGoalId, reason));
    }

    public void closeIfTerminal(RequestContext context, String runtimeRef, boolean terminal) {
        platform.ifPresent(bridge -> bridge.closeForegroundIfTerminal(context, runtimeRef, terminal));
    }

    public Optional<PlatformTask> register(RequestContext context, RouteDecision decision,
                                           RuntimeType type, String runtimeRef, boolean terminal,
                                           String pendingGoalId) {
        return platform.map(bridge -> bridge.register(
                context, decision, type, runtimeRef, terminal, pendingGoalId));
    }

    public enum Handler{KNOWLEDGE,NAVIGATION,TASK,WORKFLOW,STATIC_PLAN,DELEGATION,AGENT_LOOP,CLARIFY,CANCEL,REJECT,HANDOFF}
}
