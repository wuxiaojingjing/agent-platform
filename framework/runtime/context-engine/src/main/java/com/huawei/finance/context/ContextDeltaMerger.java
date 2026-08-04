package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parent-side CAS and scope gate for child ContextDelta. */
public final class ContextDeltaMerger {

    public MergeResult merge(long currentStateVersion, SubtaskContextEnvelope delegated,
                             ContextDelta delta) {
        if (delegated == null || delta == null) {
            return new MergeResult(Status.NO_DELTA, currentStateVersion, Map.of(), List.of(), null);
        }
        if (delegated.expired(Instant.now())) {
            return rejected(currentStateVersion, "CONTEXT_LEASE_EXPIRED");
        }
        if (delta.baseStateVersion() != delegated.baseStateVersion()
                || delta.baseStateVersion() != currentStateVersion) {
            return rejected(currentStateVersion, "CONTEXT_VERSION_CONFLICT");
        }
        Map<String, ContextEvidence> merged = new LinkedHashMap<>();
        for (ContextEvidence fact : delegated.facts()) merged.put(fact.ref(), fact);
        for (String ref : delta.invalidatedRefs()) merged.remove(ref);
        for (ContextEvidence fact : delta.upserts()) {
            if (fact.kind() == ContextEvidence.Kind.USER_TURN) {
                return rejected(currentStateVersion, "CONTEXT_RAW_HISTORY_FORBIDDEN");
            }
            if (!fact.validAt(Instant.now())) continue;
            merged.put(fact.ref(), fact);
        }
        return new MergeResult(Status.APPLIED, currentStateVersion + 1,
                Map.copyOf(merged), delta.pendingQuestions(), null);
    }

    private static MergeResult rejected(long version, String reason) {
        return new MergeResult(Status.REJECTED, version, Map.of(), List.of(), reason);
    }

    public enum Status { APPLIED, REJECTED, NO_DELTA }

    public record MergeResult(Status status, long newStateVersion,
                              Map<String, ContextEvidence> facts,
                              List<ContextDelta.PendingQuestion> pendingQuestions,
                              String reasonCode) {
        public boolean applied() { return status == Status.APPLIED; }
    }
}
