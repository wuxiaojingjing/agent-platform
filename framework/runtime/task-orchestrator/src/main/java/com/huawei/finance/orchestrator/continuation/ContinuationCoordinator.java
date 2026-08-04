package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public class ContinuationCoordinator {
    private final ContinuationContextAssembler contexts;
    private final DeterministicContinuationRules rules;
    private final ContinuationUnderstandingModel model;
    private final ContinuationPolicyGate policy;
    private final MeterRegistry meters;
    private final SlotValueNormalizer slotValues;

    public ContinuationCoordinator(ContinuationContextAssembler contexts,
                                   DeterministicContinuationRules rules,
                                   ContinuationUnderstandingModel model,
                                   ContinuationPolicyGate policy) {
        this(contexts, rules, model, policy, null);
    }

    public ContinuationCoordinator(ContinuationContextAssembler contexts,
                                   DeterministicContinuationRules rules,
                                   ContinuationUnderstandingModel model,
                                   ContinuationPolicyGate policy, MeterRegistry meters) {
        this(contexts, rules, model, policy, meters, SlotValueNormalizer.IDENTITY);
    }

    public ContinuationCoordinator(ContinuationContextAssembler contexts,
                                   DeterministicContinuationRules rules,
                                   ContinuationUnderstandingModel model,
                                   ContinuationPolicyGate policy, MeterRegistry meters,
                                   SlotValueNormalizer slotValues) {
        this.contexts = contexts; this.rules = rules; this.model = model; this.policy = policy; this.meters = meters;
        this.slotValues = slotValues == null ? SlotValueNormalizer.IDENTITY : slotValues;
    }

    public Decision decide(String tenantId, String agentId, String sessionId, String input,
                           StructuredAction action) {
        Context context = contexts.assemble(tenantId, agentId, sessionId);
        return decide(tenantId, agentId, sessionId, input, action, context);
    }

    public Context context(String tenantId, String agentId, String sessionId) {
        return contexts.assemble(tenantId, agentId, sessionId);
    }

    public Decision decide(String tenantId, String agentId, String sessionId,
                           String input, StructuredAction action, Context context) {
        return decide(tenantId, agentId, sessionId, input, action, context, null);
    }

    public Decision decide(String tenantId, String agentId, String sessionId,
                           String input, StructuredAction action, Context context,
                           IntentContext intentContext) {
        Resolution resolution = rules.resolve(normalizeExactSlotInput(input, context), action, context);
        boolean modelUsed = false;
        if (resolution.event() == Event.UNRESOLVED
                && (context.hasForeground() || context.hasPendingSwitch() || !context.suspended().isEmpty())
                && action == null) {
            resolution = model.understand(tenantId, agentId, sessionId, input, context, intentContext);
            modelUsed = true;
        }
        resolution = normalizeSlotUpdates(resolution, context);
        Resolution accepted = policy.validate(input, action, context, resolution);
        if (meters != null) meters.counter(AgentMetrics.CONTINUATION_DECISION,
                AgentMetrics.TAG_DECISION, accepted.event().name(), AgentMetrics.TAG_MODE,
                modelUsed ? "MODEL" : "RULE", AgentMetrics.TAG_REASON_CODE,
                accepted.event() == Event.UNRESOLVED ? accepted.reasonCode() : "ACCEPTED").increment();
        Snapshot snapshot = accepted.targetRef() == null ? context.foreground()
                : java.util.stream.Stream.concat(
                        java.util.stream.Stream.ofNullable(context.foreground()), context.suspended().stream())
                    .filter(s -> accepted.targetRef().equals(s.runtimeRef())).findFirst().orElse(context.foreground());
        return new Decision(accepted, snapshot, context.suspended(), context.pendingSwitch(), modelUsed);
    }

    private String normalizeExactSlotInput(String input, Context context) {
        if (input == null || context.foreground() == null
                || context.foreground().pendingInteraction() == null) return input;
        PendingInteraction pending = context.foreground().pendingInteraction();
        if (pending.expectedSlot() == null) return input;
        var allowed = context.foreground().allowedSlotsAndValues()
                .getOrDefault(pending.expectedSlot(), java.util.List.of());
        Object normalized = slotValues.normalize(pending.expectedSlot(), input.trim(), allowed);
        return normalized == null ? input : String.valueOf(normalized);
    }

    private Resolution normalizeSlotUpdates(Resolution resolution, Context context) {
        if (resolution == null || resolution.slotUpdates().isEmpty()) return resolution;
        Snapshot target = target(context, resolution.targetRef());
        if (target == null) return resolution;
        var normalized = new java.util.LinkedHashMap<String, Object>();
        resolution.slotUpdates().forEach((slot, value) -> normalized.put(slot,
                slotValues.normalize(slot, value,
                        target.allowedSlotsAndValues().getOrDefault(slot, java.util.List.of()))));
        return new Resolution(resolution.event(), resolution.targetRef(), normalized,
                resolution.newGoalSpan(), resolution.confidence(), resolution.reasonCode(),
                resolution.confirmationStrength());
    }

    private static Snapshot target(Context context, String ref) {
        if (ref == null || ref.isBlank()) return context.foreground();
        return java.util.stream.Stream.concat(java.util.stream.Stream.ofNullable(context.foreground()),
                        context.suspended().stream())
                .filter(snapshot -> ref.equals(snapshot.runtimeRef())).findFirst().orElse(null);
    }
}
