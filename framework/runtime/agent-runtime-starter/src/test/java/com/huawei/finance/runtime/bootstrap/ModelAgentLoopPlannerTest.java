package com.huawei.finance.runtime.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.LoopAccess;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.orchestrator.loop.LoopContracts.Action;
import com.huawei.finance.orchestrator.loop.LoopContracts.ActionType;
import com.huawei.finance.orchestrator.loop.LoopContracts.Run;
import com.huawei.finance.orchestrator.loop.LoopContracts.Status;
import com.huawei.finance.registry.asset.ArbitrationSkill;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.runtime.loop.LoopContext;
import com.huawei.finance.slowpath.DeepAgentSingleActionPlanner;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class ModelAgentLoopPlannerTest {

    @Test
    void validDeepAgentProposalBecomesOneRuntimeAction() {
        var deepAgent = new StubDeepAgent(new DeepAgentSingleActionPlanner.Proposal(
                "CALL_CAPABILITY", "cap.balance", Map.of("accountType", "DEBIT"),
                Map.of("accountType", "CONFIRMED_SLOT"), "CHECK_BALANCE"));
        var planner = planner(deepAgent);

        Action action = planner.nextAction(context(Map.of("accountType", "DEBIT")));

        assertThat(action.actionType()).isEqualTo(ActionType.CALL_CAPABILITY);
        assertThat(action.targetId()).isEqualTo("cap.balance");
        assertThat(action.parameters()).containsEntry("accountType", "DEBIT");
        assertThat(action.inputProvenance()).containsEntry("accountType", "CONFIRMED_SLOT");
        assertThat(action.proposalReasonCode()).isEqualTo("CHECK_BALANCE");
        assertThat(action.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(deepAgent.calls).isEqualTo(1);
    }

    @Test
    void plannerReceivesVisibleTranscriptButKeepsItOutsideParameterProvenance() {
        var deepAgent = new StubDeepAgent(new DeepAgentSingleActionPlanner.Proposal(
                "FINISH", null, Map.of(), Map.of(), "DONE"));
        var planner = planner(deepAgent);
        Instant now = Instant.now();
        Run run = new Run("tenant", "loop-1", "agent.test", "session", "root", "trace",
                "排查余额", Status.RUNNING, 0, 4, List.of("cap.balance"), Map.of(), null,
                now.plusSeconds(30), 1, now, now);
        LoopContext context = new LoopContext(run, Map.of(), null, List.of(card()), 3,
                List.of(Map.of("role", "assistant", "text", "已展示余额卡片",
                        "data", Map.of("actions", List.of("查看明细")))),
                List.of(new ContextEvidence("fact:balance", ContextEvidence.Kind.TOOL_FACT,
                        Map.of("balance", "8000"), "agent.account", "task-1", "turn:1",
                        now, null, ContextEvidence.Sensitivity.SENSITIVE)));

        Action action = planner.nextAction(context);

        assertThat(action.parameters()).isEmpty();
        assertThat(action.inputProvenance()).isEmpty();
        assertThat(deepAgent.lastUserPrompt).contains("已展示余额卡片", "查看明细", "fact:balance");
    }

    @Test
    void invalidOrMissingDeepAgentProposalUsesConstrainedFallback() {
        var invalid = planner(new StubDeepAgent(new DeepAgentSingleActionPlanner.Proposal(
                "NOT_AN_ACTION", null, Map.of(), Map.of(), "INVALID")));
        var missing = planner(new StubDeepAgent());

        Action invalidFallback = invalid.nextAction(context(Map.of()));
        Action missingFallback = missing.nextAction(context(Map.of()));

        assertThat(invalidFallback.actionType()).isEqualTo(ActionType.CALL_CAPABILITY);
        assertThat(invalidFallback.targetId()).isEqualTo("cap.balance");
        assertThat(missingFallback.actionType()).isEqualTo(ActionType.CALL_CAPABILITY);
        assertThat(missingFallback.targetId()).isEqualTo("cap.balance");
    }

    @Test
    void fingerprintIsStableAcrossNestedParameterMapOrder() {
        Map<String,Object> nestedFirst = new LinkedHashMap<>();
        nestedFirst.put("z", 2);
        nestedFirst.put("a", 1);
        Map<String,Object> first = new LinkedHashMap<>();
        first.put("outerZ", nestedFirst);
        first.put("outerA", "same");

        Map<String,Object> nestedSecond = new LinkedHashMap<>();
        nestedSecond.put("a", 1);
        nestedSecond.put("z", 2);
        Map<String,Object> second = new LinkedHashMap<>();
        second.put("outerA", "same");
        second.put("outerZ", nestedSecond);

        var deepAgent = new StubDeepAgent(
                new DeepAgentSingleActionPlanner.Proposal(
                        "CALL_CAPABILITY", "cap.balance", first,
                        Map.of("outerZ", "CONFIRMED_SLOT", "outerA", "CONFIRMED_SLOT"), "CHECK"),
                new DeepAgentSingleActionPlanner.Proposal(
                        "CALL_CAPABILITY", "cap.balance", second,
                        Map.of("outerZ", "CONFIRMED_SLOT", "outerA", "CONFIRMED_SLOT"), "CHECK"));
        var planner = planner(deepAgent);

        assertThat(planner.nextAction(context(Map.of())).fingerprint())
                .isEqualTo(planner.nextAction(context(Map.of())).fingerprint());
    }

    private static ModelAgentLoopPlanner planner(DeepAgentSingleActionPlanner deepAgent) {
        return new ModelAgentLoopPlanner(new AvailableGateway(), new ModelGatewayProperties(),
                assets(), new ContractValidator(), deepAgent);
    }

    private static LoopContext context(Map<String,Object> confirmedSlots) {
        Instant now = Instant.now();
        Run run = new Run("tenant", "loop-1", "agent.test", "session", "root", "trace",
                "查询余额异常原因", Status.RUNNING, 0, 4, List.of("cap.balance"), Map.of(), null,
                now.plusSeconds(30), 1, now, now);
        return new LoopContext(run, confirmedSlots, null, List.of(card()), 3);
    }

    private static CapabilityCard card() {
        return new CapabilityCard("cap.balance", "余额查询", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.account", List.of("account"), "查询账户余额",
                List.of("查余额"), Map.of("type", "object"), Map.of(), List.of(), List.of(),
                RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "account", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false, ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
    }

    private static AssetBundle assets() {
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setSystem("只提议一个动作");
        skill.setUser("goal={{goal}} conversationHistory={{conversationHistory}} "
                + "availableContext={{availableContext}} candidates={{candidates}}");
        return new AssetBundle("v", "v", List.of(card()), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, skill, null, null, null, null);
    }

    private static final class StubDeepAgent extends DeepAgentSingleActionPlanner {
        private final Queue<Proposal> proposals = new ArrayDeque<>();
        private int calls;
        private String lastUserPrompt;

        private StubDeepAgent(Proposal... proposals) {
            this.proposals.addAll(List.of(proposals));
        }

        @Override public Optional<Proposal> propose(
                String systemPrompt, String userPrompt, List<CapabilityCard> candidates,
                String modelName, int maxTokens, double temperature, int maxIterations,
                String workspacePath, String agentId, String sessionId) {
            calls++;
            lastUserPrompt = userPrompt;
            return Optional.ofNullable(proposals.poll());
        }
    }

    private static final class AvailableGateway implements ModelGatewayClient {
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(
                String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public boolean available() { return true; }
    }
}
