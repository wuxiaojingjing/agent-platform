package com.huawei.finance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RouteDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextResolutionPolicyGateTest {

    @Test
    void missingExecutionValueClarifiesBeforeReview() {
        var result = ContextResolutionPolicyGate.apply(execute(), Map.of(
                "accountOrdinal", 2,
                ContextResolutionMarkers.FAILURE_REASON, "A2A_GATEWAY_UNAVAILABLE",
                ContextResolutionMarkers.FAILURE_MISSING_SLOTS, List.of("amount")));

        assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.MISSING_SLOT);
        assertThat(result.decision().missingSlots()).containsExactly("amount");
        assertThat(result.slots()).doesNotContainKeys(ContextResolutionMarkers.FAILURE_REASON,
                ContextResolutionMarkers.FAILURE_MISSING_SLOTS);
    }

    @Test
    void unresolvedOptionalReferenceRejectsSideEffectInsteadOfGuessing() {
        var result = ContextResolutionPolicyGate.apply(execute(), Map.of(
                "amount", "1000",
                ContextResolutionMarkers.FAILURE_REASON, "REFERENCE_OUTPUT_OUT_OF_SCHEMA",
                ContextResolutionMarkers.FAILURE_MISSING_SLOTS, List.of()));

        assertThat(result.decision().decision()).isEqualTo(Decision.REJECT);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
    }

    @Test
    void successfulOrUnneededResolutionLeavesRouteAndSlotsUntouched() {
        RouteDecision decision = execute();
        Map<String, Object> slots = Map.of("amount", "1000");

        var result = ContextResolutionPolicyGate.apply(decision, slots);

        assertThat(result.decision()).isSameAs(decision);
        assertThat(result.slots()).isSameAs(slots);
    }

    private static RouteDecision execute() {
        return RouteDecision.builder().decision(Decision.EXECUTE_CAPABILITY)
                .candidateIds(List.of("cap.transfer")).confidence(.99)
                .reasonCode(ReasonCode.CONFIRMATION_REQUIRED).build();
    }
}
