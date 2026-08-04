package com.huawei.finance.runtime.loop;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.orchestrator.loop.LoopContracts.Run;
import com.huawei.finance.orchestrator.loop.LoopContracts.Step;
import com.huawei.finance.orchestrator.loop.LoopContracts.Observation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the bounded planner view from Loop-owned records and current-turn confirmed input. */
public class LoopContextBuilder {
    public LoopContext build(Run run, Map<String,Object> currentTurnSlots,
                             List<Step> priorSteps, List<CapabilityCard> candidates) {
        return build(run, currentTurnSlots, priorSteps, candidates, null);
    }

    public LoopContext build(Run run, Map<String,Object> currentTurnSlots,
                             List<Step> priorSteps, List<CapabilityCard> candidates,
                             IntentContext intentContext) {
        Map<String,Object> confirmed = new LinkedHashMap<>(run.confirmedSlots());
        if (currentTurnSlots != null) confirmed.putAll(currentTurnSlots);
        Observation last = priorSteps == null || priorSteps.isEmpty()
                ? null : plannerObservation(priorSteps.getLast().observation());
        return new LoopContext(run, confirmed, last,
                candidates, Math.max(0, run.maxIterations() - run.iteration()),
                intentContext == null ? List.of() : intentContext.conversationHistory(),
                intentContext == null ? List.of() : intentContext.evidence());
    }

    private static Observation plannerObservation(Observation source) {
        if (source == null) return null;
        return new Observation(source.status(), source.sourceType(), source.sourceId(), source.facts(),
                source.reasonCode(), source.failureClass(), source.taskId(), source.delegationId(),
                source.retryable(), Map.of());
    }
}
