package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A bounded, referenceable context item. Free-form history is never passed without this envelope. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextEvidence(
        String ref,
        Kind kind,
        Map<String, Object> value,
        String sourceAgentId,
        String sourceTaskId,
        String sourceTurnRef,
        Instant observedAt,
        Instant validUntil,
        Sensitivity sensitivity) {

    public ContextEvidence {
        Objects.requireNonNull(ref, "context evidence ref must not be null");
        Objects.requireNonNull(kind, "context evidence kind must not be null");
        value = value == null ? Map.of() : Map.copyOf(value);
        sensitivity = sensitivity == null ? Sensitivity.INTERNAL : sensitivity;
    }

    public boolean validAt(Instant now) {
        return validUntil == null || now.isBefore(validUntil);
    }

    public enum Kind {
        USER_TURN,
        TOOL_FACT,
        CONFIRMED_INPUT,
        RUNTIME_STATE
    }

    public enum Sensitivity {
        PUBLIC,
        INTERNAL,
        SENSITIVE,
        RESTRICTED
    }
}
