package com.huawei.finance.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.LoopAccess;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicEntryRulesTest {

    @Test
    void lowMarginWorkflowCandidateIsNotPromotedFromClarifyToExecution() {
        CapabilityCard workflow = card("workflow.card.replace", Enums.CapabilityType.WORKFLOW);
        RouteDecision clarify = RouteDecision.builder().decision(Decision.CLARIFY)
                .candidateIds(List.of(workflow.capabilityId())).reasonCode(ReasonCode.LOW_MARGIN)
                .confidence(.51).build();

        RouteDecision normalized = new DeterministicEntryRules().normalize(
                new IntentEvidence("换卡", "换卡", clarify, null, null, Map.of(), null),
                assets(workflow));

        assertThat(normalized.decision()).isEqualTo(Decision.CLARIFY);
        assertThat(normalized.reasonCode()).isEqualTo(ReasonCode.LOW_MARGIN);
    }

    @Test
    void executableWorkflowCandidateGetsTheExplicitWorkflowRoute() {
        CapabilityCard workflow = card("workflow.card.replace", Enums.CapabilityType.WORKFLOW);
        RouteDecision executable = RouteDecision.builder().decision(Decision.EXECUTE_CAPABILITY)
                .candidateIds(List.of(workflow.capabilityId())).reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .confidence(.95).build();

        RouteDecision normalized = new DeterministicEntryRules().normalize(
                new IntentEvidence("启动换卡流程", "启动换卡流程", executable,
                        null, null, Map.of(), null), assets(workflow));

        assertThat(normalized.decision()).isEqualTo(Decision.START_WORKFLOW);
        assertThat(normalized.target().type()).isEqualTo(com.huawei.finance.contracts.model.RouteTarget.Type.WORKFLOW);
    }

    private static CapabilityCard card(String id, Enums.CapabilityType type) {
        return new CapabilityCard(id, id, type, Enums.Granularity.WORKFLOW,
                "agent.test", List.of("test"), "", List.of(), Map.of(), Map.of(), List.of(),
                List.of(), RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "owner", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false, ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
    }

    private static AssetBundle assets(CapabilityCard card) {
        return new AssetBundle("v", "v", List.of(card), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, null, null, null);
    }
}
