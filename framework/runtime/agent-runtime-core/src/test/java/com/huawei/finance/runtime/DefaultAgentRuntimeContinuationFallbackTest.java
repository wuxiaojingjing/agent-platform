package com.huawei.finance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultAgentRuntimeContinuationFallbackTest {

    @Test
    void unresolvedEntryOffersMultipleResumeTargetsInsteadOfHandoff() {
        assertThat(DefaultAgentRuntime.shouldOfferSuspendedTaskChoice(
                continuation("UNRESOLVED", 2), noCandidate())).isTrue();
    }

    @Test
    void recognizedNewGoalAndSingleResumeTargetKeepTheirOwnRoutingSemantics() {
        assertThat(DefaultAgentRuntime.shouldOfferSuspendedTaskChoice(
                continuation("NEW_GOAL", 2), noCandidate())).isFalse();
        assertThat(DefaultAgentRuntime.shouldOfferSuspendedTaskChoice(
                continuation("UNRESOLVED", 1), noCandidate())).isFalse();
        assertThat(DefaultAgentRuntime.shouldOfferSuspendedTaskChoice(
                continuation("UNRESOLVED", 2), RouteDecision.builder()
                        .decision(Decision.EXECUTE_CAPABILITY)
                        .reasonCode(ReasonCode.HIGH_CONFIDENCE).build())).isFalse();
    }

    private static ContinuationContracts.Decision continuation(String reason, int suspendedCount) {
        List<ContinuationContracts.Snapshot> suspended = java.util.stream.IntStream
                .range(0, suspendedCount)
                .mapToObj(index -> new ContinuationContracts.Snapshot(
                        RuntimeType.TASK, "task-" + index, "CLARIFY_PENDING", null,
                        List.of(ContinuationContracts.Event.FILL_SLOT), Map.of(),
                        "任务" + index, 1, ContinuationContracts.SwitchMode.ALLOW_SWITCH))
                .toList();
        return new ContinuationContracts.Decision(
                new ContinuationContracts.Resolution(
                        ContinuationContracts.Event.UNRESOLVED, null, Map.of(), null, 0, reason),
                null, suspended, null, true);
    }

    private static RouteDecision noCandidate() {
        return RouteDecision.builder().decision(Decision.HANDOFF)
                .reasonCode(ReasonCode.NO_CANDIDATE).build();
    }
}
