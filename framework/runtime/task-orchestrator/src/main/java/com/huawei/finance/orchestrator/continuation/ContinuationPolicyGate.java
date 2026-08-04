package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import java.util.LinkedHashMap;

public class ContinuationPolicyGate {
    private final double threshold;
    private final double r2ConfirmationThreshold;

    public ContinuationPolicyGate(double threshold, double r2ConfirmationThreshold) {
        this.threshold = threshold;
        this.r2ConfirmationThreshold = r2ConfirmationThreshold;
    }

    public Resolution validate(String input, StructuredAction action, Context context, Resolution candidate) {
        if (candidate == null) return unresolved("UNRESOLVED");
        if (candidate.event() == Event.UNRESOLVED) {
            String relation = candidate.reasonCode() == null ? "" : candidate.reasonCode();
            return switch (relation) {
                case "AMBIGUOUS_RESUME" -> unresolved("AMBIGUOUS_RESUME");
                case "NEW_GOAL" -> unresolved("NEW_GOAL");
                default -> unresolved("UNRESOLVED");
            };
        }
        if (candidate.event() == Event.SWITCH_ACCEPT || candidate.event() == Event.SWITCH_REJECT) {
            PendingSwitchView pending = context.pendingSwitch();
            if (pending == null || !pending.switchId().equals(candidate.targetRef())) {
                return unresolved("PENDING_SWITCH_NOT_FOUND");
            }
            if (!pending.allowedEvents().contains(candidate.event())) return unresolved("EVENT_NOT_ALLOWED");
            if (action != null && (action.version() != pending.version()
                    || !pending.switchId().equals(action.ref()))) return unresolved("STALE_ACTION");
            if (action == null && candidate.confidence() < threshold) return unresolved("LOW_CONFIDENCE");
            if (candidate.event() == Event.SWITCH_ACCEPT
                    && context.suspended().size() >= com.huawei.finance.orchestrator.context.PlatformTaskContextManager.MAX_SUSPENDED) {
                return unresolved("SUSPENDED_TASK_LIMIT");
            }
            return new Resolution(candidate.event(), pending.switchId(), java.util.Map.of(), null,
                    candidate.confidence(), candidate.reasonCode(), candidate.confirmationStrength());
        }
        // The model decides whether the utterance is a new goal. Binding that decision to the
        // only foreground runtime is deterministic platform work and must not depend on copying
        // an opaque runtime UUID exactly.
        Snapshot target = action == null && candidate.event() != Event.RESUME_SUSPENDED
                ? context.foreground() : target(context, candidate.targetRef());
        if (candidate.event() == Event.RESUME_SUSPENDED) target = target(context, candidate.targetRef());
        if (target == null) return unresolved("TARGET_NOT_FOUND");
        if (candidate.event() == Event.SWITCH_TO_NEW_GOAL && target.switchMode() == SwitchMode.DENY_SWITCH)
            return unresolved("RUNTIME_DENIES_SWITCH");
        if (candidate.event() != Event.RESUME_SUSPENDED
                && !target.allowedEvents().contains(candidate.event())) return unresolved("EVENT_NOT_ALLOWED");
        if (action != null && (action.version() != target.stateVersion() || !action.ref().equals(target.runtimeRef())))
            return unresolved("STALE_ACTION");
        double required = candidate.event() == Event.CONFIRM ? r2ConfirmationThreshold : threshold;
        if (action == null && candidate.confidence() < required) return unresolved("LOW_CONFIDENCE");
        if (candidate.event() == Event.CONFIRM && action == null
                && candidate.confirmationStrength() != ConfirmationStrength.EXPLICIT_ACTION)
            return unresolved("CONFIRM_SEMANTICS_REQUIRED");
        Snapshot validatedTarget = target;
        var safeSlots = new LinkedHashMap<String,Object>();
        candidate.slotUpdates().forEach((slot, value) -> {
            var allowed = validatedTarget.allowedSlotsAndValues().get(slot);
            if (allowed != null && (allowed.isEmpty() || allowed.contains("*")
                    || allowed.contains(String.valueOf(value)))) safeSlots.put(slot, value);
        });
        if (candidate.event() == Event.FILL_SLOT && safeSlots.isEmpty()) return unresolved("SLOT_NOT_ALLOWED");
        if (candidate.event() == Event.CORRECTION && safeSlots.isEmpty()) return unresolved("SLOT_NOT_ALLOWED");
        if (candidate.event() != Event.FILL_SLOT && candidate.event() != Event.CORRECTION
                && !candidate.slotUpdates().isEmpty()) return unresolved("SLOT_UPDATE_NOT_ALLOWED");
        String span = candidate.newGoalSpan();
        if (span != null && (input == null || !input.contains(span))) return unresolved("SPAN_NOT_IN_INPUT");
        return new Resolution(candidate.event(), target.runtimeRef(), safeSlots, span,
                candidate.confidence(), candidate.reasonCode(), candidate.confirmationStrength());
    }

    private static Snapshot target(Context context, String ref) {
        if (context.foreground() != null && context.foreground().runtimeRef().equals(ref)) return context.foreground();
        return context.suspended().stream().filter(s -> s.runtimeRef().equals(ref)).findFirst().orElse(null);
    }
    private static Resolution unresolved(String reason) {
        return new Resolution(Event.UNRESOLVED, null, java.util.Map.of(), null, 0, reason);
    }
}
