package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanState;
import java.util.List;
import java.util.Map;

public class StaticPlanContinuationPort implements RuntimeContinuationPort {
    private final IntentPlanRepository plans;
    public StaticPlanContinuationPort(IntentPlanRepository plans) { this.plans = plans; }
    @Override public RuntimeType runtimeType() { return RuntimeType.STATIC_PLAN; }

    @Override public Snapshot describe(String tenantId, String agentId, String ref) {
        var plan = plans.findById(agentId, ref).orElseThrow(() -> new IllegalStateException("PLAN_NOT_FOUND"));
        var detail = plan.pendingInteraction();
        PendingInteraction pending = switch (plan.state()) {
            case WAITING_USER -> new PendingInteraction("PLAN_INPUT", "plan:" + ref,
                    detail == null ? null : detail.slot(),
                    detail == null ? List.of() : detail.expectedAnswers());
            case WAITING_REVIEW -> new PendingInteraction("REVIEW", "plan-review:" + ref, null, List.of());
            case WAITING_CONFIRMATION -> new PendingInteraction("CONFIRM", "plan-confirm:" + ref, null, List.of());
            default -> null;
        };
        List<Event> events = switch (plan.state()) {
            case WAITING_USER -> List.of(Event.FILL_SLOT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case WAITING_REVIEW -> List.of(Event.REVIEW_ACCEPT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case WAITING_CONFIRMATION -> List.of(Event.CONFIRM, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case IN_PROGRESS -> List.of(Event.CONTINUE_CURRENT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            default -> List.of();
        };
        Map<String,List<String>> allowedSlots = detail == null || detail.slot() == null
                ? Map.of() : Map.of(detail.slot(), detail.expectedAnswers().isEmpty()
                        ? List.of("*") : detail.expectedAnswers());
        // IN_PROGRESS means the durable plan is waiting for the next synchronous turn. No action is
        // detached in the background, so changing focus preserves the plan and is safe to review.
        SwitchMode switchMode = SwitchMode.ALLOW_SWITCH;
        List<Map<String, Object>> recentSteps = plans.steps(ref).stream()
                .sorted(java.util.Comparator.comparingInt(
                        com.huawei.finance.orchestrator.plan.PlanStepRecord::stepIndex).reversed())
                .limit(5)
                .sorted(java.util.Comparator.comparingInt(
                        com.huawei.finance.orchestrator.plan.PlanStepRecord::stepIndex))
                .map(step -> Map.<String, Object>of(
                        "stepIndex", step.stepIndex(),
                        "capabilityId", step.capabilityId(),
                        "status", step.status().name(),
                        "failureClass", step.failureClass().name(),
                        "facts", step.facts()))
                .toList();
        return new Snapshot(RuntimeType.STATIC_PLAN, ref, plan.state().name(), pending, events, allowedSlots,
                plan.next().map(i -> i.text()).orElse(plan.plan().original()),
                plan.stateVersion(), switchMode, plan.plan().original(),
                plans.parameters(agentId, ref), Map.of("recentSteps", recentSteps));
    }
}
