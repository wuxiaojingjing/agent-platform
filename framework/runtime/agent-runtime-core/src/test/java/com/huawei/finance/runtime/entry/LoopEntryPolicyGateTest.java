package com.huawei.finance.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopEntryPolicyGateTest {
    @Test
    void executionWithMissingSlotsIsTightenedToClarify() {
        CapabilityCard card = card("cap.test", LoopAccess.DEFAULT);
        RouteDecision proposed = RouteDecision.builder().decision(Decision.START_LOOP)
                .candidateIds(List.of(card.capabilityId())).taskShape(TaskShape.OBSERVATION_DRIVEN)
                .missingSlots(List.of("accountRef")).reasonCode(ReasonCode.AFTER_OBSERVATION).build();

        RouteDecision result = new LoopEntryPolicyGate(true).tighten(proposed,
                evidence(proposed), assets(card));

        assertThat(result.decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.MISSING_SLOT);
    }

    @Test
    void loopWithOnlyDeniedCandidatesIsTightenedToHandoff() {
        CapabilityCard card = card("cap.denied", LoopAccess.DENY);
        RouteDecision proposed = RouteDecision.builder().decision(Decision.START_LOOP)
                .candidateIds(List.of(card.capabilityId())).taskShape(TaskShape.OPEN_ENDED_DIAGNOSIS)
                .reasonCode(ReasonCode.AFTER_OBSERVATION).build();

        RouteDecision result = new LoopEntryPolicyGate(true).tighten(proposed,
                evidence(proposed), assets(card));

        assertThat(result.decision()).isEqualTo(Decision.HANDOFF);
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
    }

    @Test
    void validLoopNormalizesTaskShapeReasonToAfterObservation() {
        CapabilityCard card = card("cap.allowed", LoopAccess.DEFAULT);
        RouteDecision proposed = RouteDecision.builder().decision(Decision.START_LOOP)
                .candidateIds(List.of(card.capabilityId())).taskShape(TaskShape.OPEN_ENDED_DIAGNOSIS)
                .reasonCode(ReasonCode.OPEN_ENDED_DIAGNOSIS).build();

        RouteDecision result = new LoopEntryPolicyGate(true).tighten(proposed,
                evidence(proposed), assets(card));

        assertThat(result.decision()).isEqualTo(Decision.START_LOOP);
        assertThat(result.taskShape()).isEqualTo(TaskShape.OPEN_ENDED_DIAGNOSIS);
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.AFTER_OBSERVATION);
    }

    private static IntentEvidence evidence(RouteDecision decision) {
        return new IntentEvidence("goal", "goal", decision, null, null, Map.of(), null);
    }

    private static CapabilityCard card(String id, LoopAccess access) {
        return new CapabilityCard(id, id, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.test", List.of("test"), "", List.of(), Map.of(), Map.of(), List.of(),
                List.of(), RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "owner", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false, ConfirmationPolicy.NONE, access);
    }

    private static AssetBundle assets(CapabilityCard card) {
        return new AssetBundle("v", "v", List.of(card), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, null, null, null);
    }
}
