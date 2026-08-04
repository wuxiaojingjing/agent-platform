package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured evidence returned by the legacy small-i assistant before policy evaluation. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XiaoiExternalEvidence(
        CallStatus callStatus,
        MatchStatus matchStatus,
        ResultType resultType,
        String knowledgeId,
        Double rawScore,
        Map<String, String> dimension,
        List<String> actionRefs,
        String legacySessionRef,
        String sourceVersion) {

    public XiaoiExternalEvidence {
        Objects.requireNonNull(callStatus, "callStatus");
        Objects.requireNonNull(matchStatus, "matchStatus");
        Objects.requireNonNull(resultType, "resultType");
        if (rawScore != null && !Double.isFinite(rawScore)) {
            throw new IllegalArgumentException("rawScore must be finite");
        }
        dimension = dimension == null ? Map.of() : Map.copyOf(dimension);
        actionRefs = actionRefs == null ? List.of() : List.copyOf(actionRefs);
        sourceVersion = sourceVersion == null ? "" : sourceVersion;
    }

    public enum CallStatus { SUCCEEDED, TIMEOUT, FAILED }
    public enum MatchStatus { MATCHED, UNMATCHED, AMBIGUOUS }
    public enum ResultType { KNOWLEDGE, MENU, SERVICE, CLARIFY, DEFAULT_REPLY }
}
