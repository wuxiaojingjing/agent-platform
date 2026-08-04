package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;

/** Child-produced context changes. Only the parent may merge them into authoritative state. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextDelta(
        long baseStateVersion,
        List<ContextEvidence> upserts,
        List<String> invalidatedRefs,
        List<PendingQuestion> pendingQuestions,
        List<MemorySuggestion> memorySuggestions) {

    public ContextDelta {
        upserts = upserts == null ? List.of() : List.copyOf(upserts);
        invalidatedRefs = invalidatedRefs == null ? List.of() : List.copyOf(invalidatedRefs);
        pendingQuestions = pendingQuestions == null ? List.of() : List.copyOf(pendingQuestions);
        memorySuggestions = memorySuggestions == null ? List.of() : List.copyOf(memorySuggestions);
    }

    public record PendingQuestion(String slot, List<String> options, String reasonCode) {
        public PendingQuestion {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** Suggestions are never automatically persisted as memory. */
    public record MemorySuggestion(String namespace, String factRef, String reasonCode) {
    }
}
