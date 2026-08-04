package com.huawei.finance.runtime.loop;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts executor-specific results into the only shape visible to the planner. */
public class LoopObservationNormalizer {
    private static final int MAX_FACTS = 64;
    private static final int MAX_LIST_ITEMS = 32;
    private static final int MAX_DEPTH = 3;
    private static final Pattern STRUCTURED_TEXT = Pattern.compile("[\\p{L}\\p{N}_.:/+\\-]{1,128}");
    private static final Set<String> FREE_TEXT_KEYS = Set.of(
            "answer", "content", "description", "detail", "error", "message", "prompt", "raw", "stack", "text");

    public Observation knowledgeSuccess(String knowledgeId, String answer) {
        return new Observation(ObservationStatus.SUCCESS, "KNOWLEDGE", knowledgeId,
                Map.of("knowledgeId", knowledgeId), "KNOWLEDGE_MATCHED", null,
                null, null, false, Map.of("answer", answer));
    }

    public Observation navigationSuccess(String menuId, String menuName, String path) {
        return new Observation(ObservationStatus.SUCCESS, "NAVIGATION", menuId,
                Map.of("menuId", menuId, "menuName", menuName, "path", path),
                "MENU_RESOLVED", null, null, null, false, Map.of("action", "OPEN_MENU"));
    }

    public Observation failure(String sourceType, String sourceId, String reasonCode,
                               String failureClass, boolean retryable) {
        return new Observation(ObservationStatus.FAILED, sourceType, sourceId, Map.of(),
                code(reasonCode, "UNCLASSIFIED_FAILURE"), code(failureClass, "FATAL"),
                null, null, retryable, Map.of());
    }

    public Observation normalizeTask(CapabilityCard card, AgentTaskOutcome outcome) {
        if (outcome.result() == null) {
            return new Observation(ObservationStatus.NEED_USER, "CAPABILITY", card.capabilityId(),
                    Map.of(), code(outcome.orchestrationState(), "NEED_USER"), "NEED_USER",
                    outcome.taskId(), null, false, Map.of());
        }
        var result = outcome.result();
        ObservationStatus status = switch (result.status()) {
            case SUCCESS -> ObservationStatus.SUCCESS;
            case NEED_USER -> ObservationStatus.NEED_USER;
            case PARTIAL -> ObservationStatus.PARTIAL;
            case FAILED -> ObservationStatus.FAILED;
            case CANCELLED -> ObservationStatus.CANCELLED;
        };
        Map<String, Object> facts = sanitizeFacts(result.resultPayload(), declaredOutputKeys(card));
        Map<String, Object> hints = result.status() == Enums.TaskStatus.NEED_USER
                ? missingSlotHints(result.resultPayload()) : Map.of();
        return new Observation(status, "CAPABILITY", card.capabilityId(), facts,
                code(result.failureClass().name(), result.status().name()), result.failureClass().name(),
                outcome.taskId(), null, result.failureClass() == Enums.FailureClass.RETRYABLE, hints);
    }

    public Observation normalizeExternal(Observation observation) {
        if (observation == null) {
            return failure("LOOP", null, "EMPTY_EXECUTOR_RESULT", "FATAL", false);
        }
        return new Observation(observation.status(), code(observation.sourceType(), "EXTERNAL"),
                observation.sourceId(), sanitizeFacts(observation.facts(), Set.of()),
                code(observation.reasonCode(), "UNCLASSIFIED_RESULT"),
                code(observation.failureClass(), null), observation.taskId(), observation.delegationId(),
                observation.retryable(), sanitizeHints(observation.displayHints()));
    }

    private static Set<String> declaredOutputKeys(CapabilityCard card) {
        Object properties = card.outputSchema().get("properties");
        if (!(properties instanceof Map<?, ?> map)) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        map.keySet().forEach(key -> keys.add(String.valueOf(key)));
        return Set.copyOf(keys);
    }

    private static Map<String, Object> sanitizeFacts(Map<String, Object> raw, Set<String> declaredKeys) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (normalized.size() >= MAX_FACTS || unsafeKey(entry.getKey())) {
                continue;
            }
            if (!declaredKeys.isEmpty() && !declaredKeys.contains(entry.getKey())) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), 0,
                    !declaredKeys.isEmpty() && declaredKeys.contains(entry.getKey()));
            if (value != null) {
                normalized.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(normalized);
    }

    private static Object sanitizeValue(Object value, int depth) {
        return sanitizeValue(value, depth, false);
    }

    private static Object sanitizeValue(Object value, int depth, boolean schemaDeclared) {
        if (value == null || depth >= MAX_DEPTH) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (schemaDeclared) {
                return !trimmed.isEmpty() && trimmed.length() <= 128
                        && trimmed.chars().noneMatch(Character::isISOControl) ? trimmed : null;
            }
            return STRUCTURED_TEXT.matcher(trimmed).matches() ? trimmed : null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (nested.size() >= MAX_FACTS) break;
                String key = String.valueOf(entry.getKey());
                if (unsafeKey(key)) continue;
                Object normalized = sanitizeValue(entry.getValue(), depth + 1, schemaDeclared);
                if (normalized != null) nested.put(key, normalized);
            }
            return nested.isEmpty() ? null : Map.copyOf(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) {
                if (items.size() >= MAX_LIST_ITEMS) break;
                Object normalized = sanitizeValue(item, depth + 1, schemaDeclared);
                if (normalized != null) items.add(normalized);
            }
            return items.isEmpty() ? null : List.copyOf(items);
        }
        return null;
    }

    private static Map<String, Object> missingSlotHints(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        Object slots = sanitizeValue(payload.get("missingSlots"), 0);
        return slots == null ? Map.of() : Map.of("missingSlots", slots);
    }

    private static Map<String, Object> sanitizeHints(Map<String, Object> hints) {
        if (hints == null || hints.isEmpty()) return Map.of();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String key : List.of("missingSlots", "action")) {
            Object value = sanitizeValue(hints.get(key), 0);
            if (value != null) normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private static boolean unsafeKey(String key) {
        return key == null || FREE_TEXT_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static String code(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return STRUCTURED_TEXT.matcher(value).matches() ? value : fallback;
    }
}
