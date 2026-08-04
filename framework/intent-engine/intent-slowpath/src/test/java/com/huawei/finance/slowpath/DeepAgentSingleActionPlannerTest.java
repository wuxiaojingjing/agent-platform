package com.huawei.finance.slowpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeepAgentSingleActionPlannerTest {

    @Test
    void targetedToolCapturesExactlyOneProposalFromAllowedCandidates() {
        List<DeepAgentSingleActionPlanner.Proposal> proposals = new ArrayList<>();
        var tool = new DeepAgentSingleActionPlanner.ProposalActionTool(
                "propose_capability", "CALL_CAPABILITY", List.of("cap.balance"), proposals);

        Object receipt = tool.invoke(Map.of(
                "targetId", "cap.balance",
                "parameters", Map.of("accountType", "DEBIT"),
                "inputProvenance", Map.of("accountType", "CONFIRMED_SLOT"),
                "proposalReasonCode", "CHECK_BALANCE"), Map.of());

        assertThat(proposals).containsExactly(new DeepAgentSingleActionPlanner.Proposal(
                "CALL_CAPABILITY", "cap.balance", Map.of("accountType", "DEBIT"),
                Map.of("accountType", "CONFIRMED_SLOT"), "CHECK_BALANCE"));
        assertThat(receipt).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("accepted", true);
    }

    @Test
    void secondProposalAndOutsideTargetAreRejected() {
        List<DeepAgentSingleActionPlanner.Proposal> proposals = new ArrayList<>();
        var tool = new DeepAgentSingleActionPlanner.ProposalActionTool(
                "propose_capability", "CALL_CAPABILITY", List.of("cap.balance"), proposals);
        Map<String,Object> valid = Map.of("targetId", "cap.balance", "parameters", Map.of(),
                "inputProvenance", Map.of(), "proposalReasonCode", "FIRST");

        tool.invoke(valid, Map.of());

        assertThatThrownBy(() -> tool.invoke(valid, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MULTIPLE_ACTIONS_PROPOSED");
        var fresh = new DeepAgentSingleActionPlanner.ProposalActionTool(
                "propose_capability", "CALL_CAPABILITY", List.of("cap.balance"), new ArrayList<>());
        assertThatThrownBy(() -> fresh.invoke(Map.of("targetId", "cap.transfer",
                        "parameters", Map.of(), "inputProvenance", Map.of(),
                        "proposalReasonCode", "OUTSIDE"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TARGET_OUTSIDE_ALLOWED_CANDIDATES");
    }

    @Test
    @SuppressWarnings("unchecked")
    void targetedToolSchemaRestrictsTargetId() {
        var tool = new DeepAgentSingleActionPlanner.ProposalActionTool(
                "propose_capability", "CALL_CAPABILITY", List.of("cap.a", "cap.b"), new ArrayList<>());

        Map<String,Object> schema = tool.getCard().getInputParams();
        Map<String,Object> properties = (Map<String,Object>) schema.get("properties");
        Map<String,Object> target = (Map<String,Object>) properties.get("targetId");

        assertThat(target.get("enum")).isEqualTo(List.of("cap.a", "cap.b"));
        assertThat((List<String>) schema.get("required")).contains("targetId");
        assertThat(schema).containsEntry("additionalProperties", false);
    }

    @Test
    void controlActionCannotCarryTargetParametersOrProvenance() {
        List<DeepAgentSingleActionPlanner.Proposal> proposals = new ArrayList<>();
        var tool = new DeepAgentSingleActionPlanner.ProposalActionTool(
                "propose_finish", "FINISH", List.of(), proposals);

        tool.invoke(Map.of("parameters", Map.of("unsafe", true),
                "inputProvenance", Map.of("unsafe", "CONFIRMED_SLOT"),
                "proposalReasonCode", "DONE"), Map.of());

        assertThat(proposals.getFirst().targetId()).isNull();
        assertThat(proposals.getFirst().parameters()).isEmpty();
        assertThat(proposals.getFirst().inputProvenance()).isEmpty();
    }
}
