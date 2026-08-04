package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The exact, versioned projection that contextual rewrite is allowed to consume. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentContext(
        String leaseId,
        String sessionId,
        String goal,
        long stateVersion,
        boolean trustworthy,
        Instant expiresAt,
        Map<String, Object> confirmedFacts,
        List<ContextEvidence> evidence,
        int trimmedItems) {

    public IntentContext {
        confirmedFacts = confirmedFacts == null ? Map.of() : Map.copyOf(confirmedFacts);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean usableAt(Instant now) {
        return trustworthy && (expiresAt == null || now.isBefore(expiresAt));
    }

    public List<String> evidenceRefs() {
        return evidence.stream().map(ContextEvidence::ref).toList();
    }

    /**
     * Ordered, role-based history sent to every context-aware model.
     *
     * <p>The authoritative facts remain in {@link #evidence}; this view restores the conversational
     * shape needed by a model. Assistant text is the exact persisted display text, never a
     * reconstruction from the current template version.
     */
    public List<Map<String, Object>> conversationHistory() {
        Map<String, List<ContextEvidence>> factsByTurn = new LinkedHashMap<>();
        evidence.stream()
                .filter(item -> item.kind() == ContextEvidence.Kind.TOOL_FACT)
                .filter(item -> item.sensitivity() != ContextEvidence.Sensitivity.RESTRICTED)
                .filter(item -> item.sourceTurnRef() != null)
                .forEach(item -> factsByTurn.computeIfAbsent(
                        item.sourceTurnRef(), ignored -> new ArrayList<>()).add(item));

        List<Map<String, Object>> messages = new ArrayList<>();
        evidence.stream()
                .filter(item -> item.kind() == ContextEvidence.Kind.USER_TURN)
                .filter(item -> item.sensitivity() != ContextEvidence.Sensitivity.RESTRICTED)
                .forEach(turn -> appendTurnMessages(messages, turn,
                        factsByTurn.getOrDefault(turn.sourceTurnRef(), List.of())));
        return List.copyOf(messages);
    }

    private static void appendTurnMessages(List<Map<String, Object>> messages,
                                           ContextEvidence turn,
                                           List<ContextEvidence> toolFacts) {
        Map<String, Object> value = turn.value();
        Object stored = value.get("messages");
        if (stored instanceof List<?> storedMessages && !storedMessages.isEmpty()) {
            for (Object item : storedMessages) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    raw.forEach((key, content) -> message.put(String.valueOf(key), content));
                    message.putIfAbsent("sourceTurnRef", turn.sourceTurnRef());
                    messages.add(Map.copyOf(message));
                }
            }
            return;
        }
        messages.add(message("user", turn.ref(), turn.sourceTurnRef(), value.get("text")));

        Map<String, Object> toolCall = new LinkedHashMap<>();
        copy(value, toolCall, "decision");
        copy(value, toolCall, "capabilityId");
        if (!toolCall.isEmpty()) {
            messages.add(message("assistant", turn.sourceTurnRef() + ":assistant",
                    turn.sourceTurnRef(), Map.copyOf(toolCall)));
        }

        Map<String, Object> toolResult = new LinkedHashMap<>();
        copy(value, toolResult, "outcome");
        copy(value, toolResult, "pending");
        copy(value, toolResult, "pendingOptions");
        Map<String, Object> facts = new LinkedHashMap<>();
        toolFacts.forEach(item -> facts.putAll(item.value()));
        if (!facts.isEmpty()) toolResult.put("facts", Map.copyOf(facts));
        if (!toolResult.isEmpty()) {
            messages.add(message("tool", turn.sourceTurnRef() + ":tool",
                    turn.sourceTurnRef(), Map.copyOf(toolResult)));
        }
    }

    private static Map<String, Object> message(String role, String ref,
                                                String sourceTurnRef, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("ref", ref);
        if (sourceTurnRef != null) message.put("sourceTurnRef", sourceTurnRef);
        message.put("content", content == null ? "" : content);
        return Map.copyOf(message);
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    public static IntentContext degraded(String sessionId, String goal, Instant expiresAt) {
        return new IntentContext(null, sessionId, goal, -1, false, expiresAt,
                Map.of(), List.of(), 0);
    }
}
