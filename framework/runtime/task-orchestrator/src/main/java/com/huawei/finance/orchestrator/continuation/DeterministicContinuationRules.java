package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import java.util.Map;

/** Deterministic continuation paths: structured actions and exact runtime-declared slot values. */
public class DeterministicContinuationRules {
    public Resolution resolve(String input, StructuredAction action, Context context) {
        if (context.hasPendingSwitch()) {
            PendingSwitchView pending = context.pendingSwitch();
            if (action != null) {
                try {
                    Event event = Event.valueOf(action.event());
                    if (pending.allowedEvents().contains(event)) {
                        return resolution(event, action.ref(), Map.of(), null, 1, "STRUCTURED_ACTION");
                    }
                } catch (IllegalArgumentException ignored) {
                    // PolicyGate will reject unknown actions through the unresolved result.
                }
                return unresolved();
            }
            return unresolved();
        }
        if (!context.hasForeground()) {
            if (action != null && "RESUME_SUSPENDED".equals(action.event())) {
                return resolution(Event.RESUME_SUSPENDED, action.ref(), Map.of(), null, 1, "STRUCTURED_ACTION");
            }
            return unresolved();
        }
        Snapshot current = context.foreground();
        if (action != null) {
            try {
                return resolution(Event.valueOf(action.event()), action.ref(), Map.of(), null, 1,
                        "STRUCTURED_ACTION");
            } catch (IllegalArgumentException ignored) {
                return unresolved();
            }
        }
        String text = input == null ? "" : input.trim();
        PendingInteraction pending = current.pendingInteraction();
        if (pending != null && pending.expectedSlot() != null) {
            var allowed = current.allowedSlotsAndValues().getOrDefault(pending.expectedSlot(), java.util.List.of());
            if (allowed.stream().anyMatch(text::equals))
                return resolution(Event.FILL_SLOT, current.runtimeRef(), Map.of(pending.expectedSlot(), text),
                        null, 1, "EXACT_SLOT_VALUE");
        }
        return unresolved();
    }
    private static Resolution unresolved() {
        return resolution(Event.UNRESOLVED, null, Map.of(), null, 0, "RULE_UNRESOLVED");
    }
    private static Resolution resolution(Event event, String ref, Map<String,Object> slots,
                                         String span, double confidence, String reason) {
        return new Resolution(event, ref, slots, span, confidence, reason);
    }
}
