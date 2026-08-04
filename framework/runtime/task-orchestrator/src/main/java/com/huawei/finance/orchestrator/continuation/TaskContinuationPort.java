package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import java.util.List;
import java.util.Map;

public class TaskContinuationPort implements RuntimeContinuationPort {
    private final TaskRepository tasks;
    public TaskContinuationPort(TaskRepository tasks) { this.tasks = tasks; }
    @Override public RuntimeType runtimeType() { return RuntimeType.TASK; }

    @Override public Snapshot describe(String tenantId, String agentId, String ref) {
        TaskRecord task = tasks.findById(ref).filter(t -> agentId.equals(t.agentId()))
                .orElseThrow(() -> new IllegalStateException("TASK_NOT_FOUND"));
        PendingInteraction pending = switch (task.state()) {
            case CLARIFY_PENDING -> new PendingInteraction("SLOT_QUESTION", "slot:" + task.pendingSlot(),
                    task.pendingSlot(), task.expectedAnswers());
            case REVIEW_PENDING -> new PendingInteraction("REVIEW", "review:" + ref, null, List.of());
            case CONFIRM_PENDING -> new PendingInteraction("CONFIRM", "confirm:" + ref, null, List.of());
            default -> null;
        };
        List<Event> events = switch (task.state()) {
            case CLARIFY_PENDING -> List.of(Event.FILL_SLOT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case REVIEW_PENDING -> List.of(Event.CORRECTION, Event.REVIEW_ACCEPT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case CONFIRM_PENDING -> List.of(Event.CORRECTION, Event.CONFIRM, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case RUNNING -> List.of(Event.CONTINUE_CURRENT);
            default -> task.state().terminal() ? List.of() : List.of(Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
        };
        // The synchronous task runtime has no durable completion receipt channel. A RUNNING
        // invocation therefore cannot be detached safely; only persisted waiting states can switch.
        SwitchMode mode = task.state() == TaskState.RUNNING
                ? SwitchMode.DENY_SWITCH : SwitchMode.ALLOW_SWITCH;
        Map<String,List<String>> allowed = new java.util.LinkedHashMap<>();
        if (task.pendingSlot() != null) {
            allowed.put(task.pendingSlot(), task.expectedAnswers().isEmpty()
                    ? List.of("*") : task.expectedAnswers());
        }
        task.parameters().entrySet().stream().filter(entry -> !entry.getKey().startsWith("__context."))
                .forEach(entry -> allowed.putIfAbsent(entry.getKey(),
                        List.of(String.valueOf(entry.getValue()), "*")));
        Map<String, Object> runtimeFacts = tasks.resultOf(ref).<Map<String, Object>>map(result -> Map.of(
                "status", result.status().name(),
                "failureClass", result.failureClass().name(),
                "output", result.resultPayload())).orElse(Map.of());
        return new Snapshot(runtimeType(), ref, task.state().name(), pending, events, allowed,
                task.capabilityId(), task.stateVersion(), mode, task.goal(), task.parameters(),
                runtimeFacts);
    }
}
