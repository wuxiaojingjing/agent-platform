package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** A frozen context-rewrite model call and its policy-level truth. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextTrajectory(
        String caseId,
        String query,
        String userPrompt,
        List<Map<String, Object>> conversationHistory,
        String assetVersion,
        Truth truth) {

    public ContextTrajectory {
        conversationHistory = conversationHistory == null
                ? List.of() : List.copyOf(conversationHistory);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Truth(
            boolean consumed,
            String eventType,
            String resolutionType,
            Map<String, String> slots,
            List<String> usedContextRefs,
            List<String> standaloneContains,
            List<String> forbiddenSlotKeys) {
        public Truth {
            slots = slots == null ? Map.of() : new LinkedHashMap<>(slots);
            usedContextRefs = usedContextRefs == null ? List.of() : List.copyOf(usedContextRefs);
            standaloneContains = standaloneContains == null ? List.of() : List.copyOf(standaloneContains);
            forbiddenSlotKeys = forbiddenSlotKeys == null ? List.of() : List.copyOf(forbiddenSlotKeys);
        }
    }
}
