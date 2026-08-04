package com.huawei.finance.runtime.loop;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates an ephemeral continuation summary from the authoritative Loop record. */
public class LoopContinuationViewProvider {
    private final AgentLoopRepository loops;

    public LoopContinuationViewProvider(AgentLoopRepository loops) {
        this.loops = loops;
    }

    public Snapshot describe(String tenant, String agent, String ref) {
        var run = loops.find(tenant, agent, ref).orElseThrow();
        List<Event> events = switch (run.status()) {
            case WAITING_USER -> List.of(Event.FILL_SLOT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case WAITING_REVIEW -> List.of(Event.REVIEW_ACCEPT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case WAITING_CONFIRMATION -> List.of(Event.CONFIRM, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
            case RUNNING -> List.of(Event.CONTINUE_CURRENT, Event.CANCEL);
            default -> List.of();
        };
        PendingInteraction pending = run.status() == Status.WAITING_USER
                || run.status() == Status.WAITING_REVIEW || run.status() == Status.WAITING_CONFIRMATION
                ? new PendingInteraction(run.status().name(), "loop:" + run.loopId() + ':' + run.iteration(),
                        run.pendingSlots().size() == 1 ? run.pendingSlots().getFirst() : null, List.of())
                : null;
        Map<String,List<String>> allowedSlots = new LinkedHashMap<>();
        run.pendingSlots().forEach(slot -> allowedSlots.put(slot, List.of()));
        SwitchMode mode = run.status() == Status.RUNNING ? SwitchMode.DENY_SWITCH : SwitchMode.ALLOW_SWITCH;
        List<Map<String, Object>> observations = loops.steps(tenant, agent, ref).stream()
                .filter(step -> step.observation() != null)
                .sorted(java.util.Comparator.comparingInt(
                        com.huawei.finance.orchestrator.loop.LoopContracts.Step::stepIndex).reversed())
                .limit(5)
                .map(LoopContinuationViewProvider::observationView)
                .toList();
        Map<String, Object> runtimeFacts = new LinkedHashMap<>();
        runtimeFacts.put("committedFacts", run.facts());
        runtimeFacts.put("recentObservations", observations);
        return new Snapshot(RuntimeType.AGENT_LOOP, ref, run.status().name(), pending, events,
                allowedSlots, "Agent Loop", run.version(), mode, run.goal(), run.confirmedSlots(),
                Map.copyOf(runtimeFacts));
    }

    private static Map<String, Object> observationView(
            com.huawei.finance.orchestrator.loop.LoopContracts.Step step) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stepIndex", step.stepIndex());
        view.put("status", step.observation().status().name());
        if (step.observation().sourceType() != null) {
            view.put("sourceType", step.observation().sourceType());
        }
        if (step.observation().sourceId() != null) {
            view.put("sourceId", step.observation().sourceId());
        }
        view.put("facts", step.observation().facts());
        if (step.observation().reasonCode() != null) {
            view.put("reasonCode", step.observation().reasonCode());
        }
        return Map.copyOf(view);
    }
}
