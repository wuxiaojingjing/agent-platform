package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

@Api
public final class ContinuationContracts {
    private ContinuationContracts() {}

    public enum Event {
        FILL_SLOT, CORRECTION, REVIEW_ACCEPT, CONFIRM, CANCEL, CONTINUE_CURRENT,
        SWITCH_TO_NEW_GOAL, SWITCH_ACCEPT, SWITCH_REJECT, RESUME_SUSPENDED, UNRESOLVED
    }
    public enum ConfirmationStrength { NONE, EXPLICIT_ACTION }
    public enum SwitchMode { ALLOW_SWITCH, ALLOW_BACKGROUND, DENY_SWITCH }
    public record PendingInteraction(String type, String questionId, String expectedSlot,
                                     List<String> expectedAnswers) {
        public PendingInteraction { expectedAnswers = expectedAnswers == null ? List.of() : List.copyOf(expectedAnswers); }
    }
    public record Snapshot(RuntimeType runtimeType, String runtimeRef, String runtimeState,
                           PendingInteraction pendingInteraction, List<Event> allowedEvents,
                           Map<String, List<String>> allowedSlotsAndValues,
                           String displaySummary, long stateVersion, SwitchMode switchMode,
                           String goal, Map<String, Object> confirmedFacts,
                           Map<String, Object> runtimeFacts) {
        public Snapshot {
            allowedEvents = allowedEvents == null ? List.of() : List.copyOf(allowedEvents);
            allowedSlotsAndValues = allowedSlotsAndValues == null ? Map.of() : Map.copyOf(allowedSlotsAndValues);
            confirmedFacts = confirmedFacts == null ? Map.of() : Map.copyOf(confirmedFacts);
            runtimeFacts = runtimeFacts == null ? Map.of() : Map.copyOf(runtimeFacts);
        }
        public Snapshot(RuntimeType runtimeType, String runtimeRef, String runtimeState,
                        PendingInteraction pendingInteraction, List<Event> allowedEvents,
                        Map<String, List<String>> allowedSlotsAndValues,
                        String displaySummary, long stateVersion, SwitchMode switchMode,
                        String goal, Map<String, Object> confirmedFacts) {
            this(runtimeType, runtimeRef, runtimeState, pendingInteraction, allowedEvents,
                    allowedSlotsAndValues, displaySummary, stateVersion, switchMode,
                    goal, confirmedFacts, Map.of());
        }
        public Snapshot(RuntimeType runtimeType, String runtimeRef, String runtimeState,
                        PendingInteraction pendingInteraction, List<Event> allowedEvents,
                        Map<String, List<String>> allowedSlotsAndValues,
                        String displaySummary, long stateVersion, SwitchMode switchMode) {
            this(runtimeType, runtimeRef, runtimeState, pendingInteraction, allowedEvents,
                    allowedSlotsAndValues, displaySummary, stateVersion, switchMode,
                    displaySummary, Map.of(), Map.of());
        }
    }
    public record Resolution(Event event, String targetRef, Map<String, Object> slotUpdates,
                             String newGoalSpan, double confidence, String reasonCode,
                             ConfirmationStrength confirmationStrength) {
        public Resolution {
            slotUpdates = slotUpdates == null ? Map.of() : Map.copyOf(slotUpdates);
            confirmationStrength = confirmationStrength == null
                    ? ConfirmationStrength.NONE : confirmationStrength;
        }
        public Resolution(Event event, String targetRef, Map<String, Object> slotUpdates,
                          String newGoalSpan, double confidence, String reasonCode) {
            this(event, targetRef, slotUpdates, newGoalSpan, confidence, reasonCode,
                    ConfirmationStrength.NONE);
        }
    }
    public record StructuredAction(String event, String ref, long version) {}
    public record PendingSwitchView(String switchId, long version, List<Event> allowedEvents) {
        public PendingSwitchView {
            allowedEvents = allowedEvents == null ? List.of() : List.copyOf(allowedEvents);
        }
    }
    public record Context(Snapshot foreground, List<Snapshot> suspended, PendingSwitchView pendingSwitch) {
        public Context { suspended = suspended == null ? List.of() : List.copyOf(suspended); }
        public Context(Snapshot foreground, List<Snapshot> suspended) {
            this(foreground, suspended, null);
        }
        public boolean hasForeground() { return foreground != null; }
        public boolean hasPendingSwitch() { return pendingSwitch != null; }
    }
    public record Decision(Resolution resolution, Snapshot snapshot, List<Snapshot> suspended,
                           PendingSwitchView pendingSwitch, boolean modelUsed) {
        public Decision {
            suspended = suspended == null ? List.of() : List.copyOf(suspended);
        }
        public Decision(Resolution resolution, Snapshot snapshot, boolean modelUsed) {
            this(resolution, snapshot, List.of(), null, modelUsed);
        }
    }
}
