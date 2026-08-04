package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record RouteDecision(
        Decision decision,
        RouteTarget target,
        List<String> candidateIds,
        TaskShape taskShape,
        IntentPlan intentPlan,
        List<String> missingSlots,
        double confidence,
        ReasonCode reasonCode,
        List<String> evidenceRefs,
        String modelVersion,
        String promptVersion,
        String configVersion,
        ShortCircuitLevel shortCircuit) {

    public static final String VERSION_NONE = "none";

    public RouteDecision {
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        modelVersion = modelVersion == null ? VERSION_NONE : modelVersion;
        promptVersion = promptVersion == null ? VERSION_NONE : promptVersion;
        configVersion = configVersion == null ? VERSION_NONE : configVersion;
        shortCircuit = shortCircuit == null ? ShortCircuitLevel.NONE : shortCircuit;
        taskShape = taskShape == null ? inferShape(decision, intentPlan) : taskShape;
    }

    public String selectedCandidateId() {
        return candidateIds.isEmpty() ? null : candidateIds.getFirst();
    }

    public boolean decidedByModel() {
        return !VERSION_NONE.equals(modelVersion);
    }

    public RouteDecision withIntentPlan(IntentPlan plan) {
        return new RouteDecision(decision, target, candidateIds, taskShape, plan, missingSlots,
                confidence, reasonCode, evidenceRefs, modelVersion, promptVersion, configVersion, shortCircuit);
    }

    public RouteDecision withEvidenceRefs(List<String> refs) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(evidenceRefs);
        if (refs != null) merged.addAll(refs);
        return new RouteDecision(decision, target, candidateIds, taskShape, intentPlan, missingSlots,
                confidence, reasonCode, List.copyOf(merged), modelVersion, promptVersion,
                configVersion, shortCircuit);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static TaskShape inferShape(Decision decision, IntentPlan plan) {
        if (decision == Decision.STATIC_PLAN && plan != null) {
            return plan.hasConditional() ? TaskShape.CONDITIONAL_PLAN : TaskShape.FIXED_MULTI_STEP;
        }
        if (decision == Decision.CLARIFY) return TaskShape.AMBIGUOUS_GOAL;
        if (decision == Decision.REJECT || decision == Decision.HANDOFF) return TaskShape.UNSUPPORTED_GOAL;
        return TaskShape.SINGLE_ACTION;
    }

    public static final class Builder {
        private Decision decision;
        private RouteTarget target;
        private List<String> candidateIds = List.of();
        private TaskShape taskShape;
        private IntentPlan intentPlan;
        private List<String> missingSlots = List.of();
        private double confidence;
        private ReasonCode reasonCode;
        private List<String> evidenceRefs = List.of();
        private String modelVersion;
        private String promptVersion;
        private String configVersion;
        private ShortCircuitLevel shortCircuit = ShortCircuitLevel.NONE;

        public Builder decision(Decision v) { decision = v; return this; }
        public Builder target(RouteTarget v) { target = v; return this; }
        public Builder candidateIds(List<String> v) { candidateIds = v; return this; }
        public Builder taskShape(TaskShape v) { taskShape = v; return this; }
        public Builder intentPlan(IntentPlan v) { intentPlan = v; return this; }
        public Builder missingSlots(List<String> v) { missingSlots = v; return this; }
        public Builder confidence(double v) { confidence = v; return this; }
        public Builder reasonCode(ReasonCode v) { reasonCode = v; return this; }
        public Builder evidenceRefs(List<String> v) { evidenceRefs = v; return this; }
        public Builder modelVersion(String v) { modelVersion = v; return this; }
        public Builder promptVersion(String v) { promptVersion = v; return this; }
        public Builder configVersion(String v) { configVersion = v; return this; }
        public Builder shortCircuit(ShortCircuitLevel v) { shortCircuit = v; return this; }
        public RouteDecision build() {
            if (target == null && candidateIds != null && candidateIds.size() == 1) {
                target = new RouteTarget(RouteTarget.Type.CAPABILITY, candidateIds.getFirst());
            }
            return new RouteDecision(decision, target, candidateIds, taskShape, intentPlan, missingSlots,
                    confidence, reasonCode, evidenceRefs, modelVersion, promptVersion, configVersion, shortCircuit);
        }
    }
}
