package com.huawei.finance.runtime;

import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.TaskShape;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts failed authoritative reference resolution into a safe route before review/execution. */
final class ContextResolutionPolicyGate {

    private ContextResolutionPolicyGate() {
    }

    static Result apply(RouteDecision decision, Map<String, Object> slots) {
        if (slots == null || !slots.containsKey(ContextResolutionMarkers.FAILURE_REASON)) {
            return new Result(decision, slots == null ? Map.of() : slots);
        }
        Object rawMissing = slots.get(ContextResolutionMarkers.FAILURE_MISSING_SLOTS);
        List<String> missing = rawMissing instanceof List<?> values
                ? values.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList()
                : List.of();
        Map<String, Object> safeSlots = new LinkedHashMap<>(slots);
        safeSlots.remove(ContextResolutionMarkers.FAILURE_REASON);
        safeSlots.remove(ContextResolutionMarkers.FAILURE_MISSING_SLOTS);

        Decision held = missing.isEmpty() ? Decision.REJECT : Decision.CLARIFY;
        RouteDecision safeDecision = new RouteDecision(held, decision.target(),
                decision.candidateIds(), held == Decision.CLARIFY
                        ? TaskShape.AMBIGUOUS_GOAL : TaskShape.UNSUPPORTED_GOAL,
                null, missing, decision.confidence(),
                held == Decision.CLARIFY ? ReasonCode.MISSING_SLOT : ReasonCode.POLICY_BLOCK,
                decision.evidenceRefs(), decision.modelVersion(), decision.promptVersion(),
                decision.configVersion(), decision.shortCircuit());
        return new Result(safeDecision, Map.copyOf(safeSlots));
    }

    record Result(RouteDecision decision, Map<String, Object> slots) {
    }
}
