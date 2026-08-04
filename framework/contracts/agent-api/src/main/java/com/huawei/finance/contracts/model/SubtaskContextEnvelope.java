package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal context delegated to a child Agent. It is not a chat-history transport. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubtaskContextEnvelope(
        String contextLeaseId,
        long baseStateVersion,
        Instant expiresAt,
        String goal,
        Map<String, Object> confirmedInputs,
        List<ContextEvidence> facts,
        List<String> allowedCapabilities,
        List<Scope> readScopes,
        Scope writeScope) {

    public SubtaskContextEnvelope {
        confirmedInputs = confirmedInputs == null ? Map.of() : Map.copyOf(confirmedInputs);
        facts = facts == null ? List.of() : List.copyOf(facts);
        allowedCapabilities = allowedCapabilities == null ? List.of() : List.copyOf(allowedCapabilities);
        readScopes = readScopes == null ? List.of(Scope.SUBTASK) : List.copyOf(readScopes);
        writeScope = writeScope == null ? Scope.SUBTASK : writeScope;
        if (facts.stream().anyMatch(f -> f.kind() == ContextEvidence.Kind.USER_TURN)) {
            throw new IllegalArgumentException("SubtaskContextEnvelope must not contain raw USER_TURN history");
        }
    }

    public boolean allowsCapability(String capabilityId) {
        return capabilityId != null && allowedCapabilities.contains(capabilityId);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public enum Scope {
        SUBTASK,
        DOMAIN,
        ROOT_TASK
    }
}
