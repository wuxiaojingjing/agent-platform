package com.huawei.finance.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextDeltaMergerTest {

    private final ContextDeltaMerger merger = new ContextDeltaMerger();

    @Test
    void appliesDeltaWhenBothVersionsMatch() {
        SubtaskContextEnvelope delegated = envelope(8, Instant.now().plusSeconds(30));
        ContextDelta delta = new ContextDelta(8,
                List.of(fact("fact:agent.account:cards", Map.of("cards", List.of("a", "b")))),
                List.of(), List.of(), List.of());

        ContextDeltaMerger.MergeResult result = merger.merge(8, delegated, delta);

        assertThat(result.applied()).isTrue();
        assertThat(result.newStateVersion()).isEqualTo(9);
        assertThat(result.facts()).containsKey("fact:agent.account:cards");
    }

    @Test
    void staleChildDeltaIsRejectedInsteadOfLastWriteWins() {
        ContextDelta delta = new ContextDelta(7,
                List.of(fact("fact:amount", Map.of("amount", "1000"))),
                List.of(), List.of(), List.of());

        ContextDeltaMerger.MergeResult result = merger.merge(
                8, envelope(7, Instant.now().plusSeconds(30)), delta);

        assertThat(result.status()).isEqualTo(ContextDeltaMerger.Status.REJECTED);
        assertThat(result.reasonCode()).isEqualTo("CONTEXT_VERSION_CONFLICT");
        assertThat(result.newStateVersion()).isEqualTo(8);
    }

    @Test
    void expiredEnvelopeAndRawHistoryAreRejected() {
        ContextDelta expiredDelta = new ContextDelta(3, List.of(), List.of(), List.of(), List.of());
        assertThat(merger.merge(3, envelope(3, Instant.now().minusSeconds(1)), expiredDelta)
                .reasonCode()).isEqualTo("CONTEXT_LEASE_EXPIRED");

        ContextEvidence raw = new ContextEvidence("turn:raw", ContextEvidence.Kind.USER_TURN,
                Map.of("text", "完整聊天历史"), "child", "task", "turn:1",
                Instant.now(), null, ContextEvidence.Sensitivity.SENSITIVE);
        ContextDelta rawDelta = new ContextDelta(3, List.of(raw), List.of(), List.of(), List.of());
        assertThat(merger.merge(3, envelope(3, Instant.now().plusSeconds(30)), rawDelta)
                .reasonCode()).isEqualTo("CONTEXT_RAW_HISTORY_FORBIDDEN");
    }

    @Test
    void envelopeConstructorForbidsRawHistory() {
        ContextEvidence raw = new ContextEvidence("turn:raw", ContextEvidence.Kind.USER_TURN,
                Map.of("text", "history"), "source", "task", "turn:1",
                Instant.now(), null, ContextEvidence.Sensitivity.SENSITIVE);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new SubtaskContextEnvelope("lease", 1, Instant.now().plusSeconds(30), "goal",
                        Map.of(), List.of(raw), List.of("cap.read"),
                        List.of(SubtaskContextEnvelope.Scope.SUBTASK),
                        SubtaskContextEnvelope.Scope.SUBTASK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER_TURN");
    }

    private static SubtaskContextEnvelope envelope(long version, Instant expiresAt) {
        return new SubtaskContextEnvelope("lease", version, expiresAt, "goal", Map.of(),
                List.of(), List.of("cap.read"),
                List.of(SubtaskContextEnvelope.Scope.SUBTASK),
                SubtaskContextEnvelope.Scope.SUBTASK);
    }

    private static ContextEvidence fact(String ref, Map<String, Object> value) {
        return new ContextEvidence(ref, ContextEvidence.Kind.TOOL_FACT, value,
                "agent.child", "task-child", null, Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
    }
}
