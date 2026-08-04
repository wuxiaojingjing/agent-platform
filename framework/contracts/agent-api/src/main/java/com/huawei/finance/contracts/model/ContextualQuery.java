package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/** Auditable semantic rewrite result. Lexical normalization happens after this contract. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextualQuery(
        String originalQuery,
        String standaloneQuery,
        EventType eventType,
        List<Resolution> resolutions,
        List<String> usedContextRefs,
        List<String> unusedContextRefs,
        Map<String, Object> slotUpdates,
        List<String> invalidatedContextRefs,
        double confidence,
        String reasonCode,
        long stateVersion,
        String modelVersion,
        String promptVersion,
        RewriteOutcome rewriteOutcome) {

    public ContextualQuery {
        standaloneQuery = standaloneQuery == null ? originalQuery : standaloneQuery;
        eventType = eventType == null ? EventType.NEW_TASK : eventType;
        resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
        usedContextRefs = usedContextRefs == null ? List.of() : List.copyOf(usedContextRefs);
        unusedContextRefs = unusedContextRefs == null ? List.of() : List.copyOf(unusedContextRefs);
        slotUpdates = slotUpdates == null ? Map.of() : Map.copyOf(slotUpdates);
        invalidatedContextRefs = invalidatedContextRefs == null
                ? List.of() : List.copyOf(invalidatedContextRefs);
        modelVersion = modelVersion == null ? RouteDecision.VERSION_NONE : modelVersion;
        promptVersion = promptVersion == null ? RouteDecision.VERSION_NONE : promptVersion;
        rewriteOutcome = rewriteOutcome == null
                ? (usedContextRefs.isEmpty() ? RewriteOutcome.NOT_REQUIRED : RewriteOutcome.APPLIED)
                : rewriteOutcome;
    }

    /** Source-compatible constructor for model adapters and extension implementations. */
    public ContextualQuery(
            String originalQuery,
            String standaloneQuery,
            EventType eventType,
            List<Resolution> resolutions,
            List<String> usedContextRefs,
            List<String> unusedContextRefs,
            Map<String, Object> slotUpdates,
            List<String> invalidatedContextRefs,
            double confidence,
            String reasonCode,
            long stateVersion,
            String modelVersion,
            String promptVersion) {
        this(originalQuery, standaloneQuery, eventType, resolutions, usedContextRefs,
                unusedContextRefs, slotUpdates, invalidatedContextRefs, confidence, reasonCode,
                stateVersion, modelVersion, promptVersion, null);
    }

    public boolean consumedContext() {
        return !usedContextRefs.isEmpty();
    }

    public static ContextualQuery identity(String query, long stateVersion, List<String> availableRefs) {
        return new ContextualQuery(query, query, EventType.NEW_TASK, List.of(), List.of(),
                availableRefs, Map.of(), List.of(), 1.0, "NO_CONTEXT_REQUIRED", stateVersion,
                RouteDecision.VERSION_NONE, RouteDecision.VERSION_NONE, RewriteOutcome.NOT_REQUIRED);
    }

    /**
     * The model recognized a reference, but the authoritative context cannot resolve it.
     * No proposed slot update is retained, so downstream execution cannot silently fall back.
     */
    public static ContextualQuery unresolvedReference(
            String query, long stateVersion, List<String> availableRefs, ContextualQuery proposed) {
        return new ContextualQuery(query, query, EventType.NEW_TASK,
                proposed == null ? List.of() : proposed.resolutions(), List.of(), availableRefs,
                Map.of(), List.of(), proposed == null ? 1.0 : proposed.confidence(),
                "UNRESOLVED_REFERENCE", stateVersion,
                proposed == null ? RouteDecision.VERSION_NONE : proposed.modelVersion(),
                proposed == null ? RouteDecision.VERSION_NONE : proposed.promptVersion(),
                RewriteOutcome.UNRESOLVED_REFERENCE);
    }

    public enum RewriteOutcome {
        APPLIED,
        NOT_REQUIRED,
        UNRESOLVED_REFERENCE
    }

    public enum EventType {
        SUPPLEMENT,
        CORRECTION,
        CANCEL,
        NEW_PARALLEL_TASK,
        TOPIC_SWITCH,
        CONFIRMATION,
        REVIEW_ACCEPT,
        SWITCH_ACCEPT,
        SWITCH_REJECT,
        RESUME_SUSPENDED,
        NEW_TASK
    }

    public record Resolution(
            String mention,
            String contextRef,
            String sourceTurnRef,
            String resolution,
            String resolutionType) {
    }
}
