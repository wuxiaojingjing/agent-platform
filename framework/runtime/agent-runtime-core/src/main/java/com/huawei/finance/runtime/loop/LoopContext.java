package com.huawei.finance.runtime.loop;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import java.util.List;
import java.util.Map;

public record LoopContext(Run run, Map<String,Object> confirmedSlots, Observation lastObservation,
                          List<CapabilityCard> candidates, int remainingIterations,
                          List<Map<String, Object>> conversationHistory,
                          List<ContextEvidence> availableContext) {
    public LoopContext {
        confirmedSlots = confirmedSlots == null ? Map.of() : Map.copyOf(confirmedSlots);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        availableContext = availableContext == null ? List.of() : List.copyOf(availableContext);
    }

    public LoopContext(Run run, Map<String,Object> confirmedSlots, Observation lastObservation,
                       List<CapabilityCard> candidates, int remainingIterations) {
        this(run, confirmedSlots, lastObservation, candidates, remainingIterations, List.of(), List.of());
    }
}
