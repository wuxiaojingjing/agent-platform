package com.huawei.finance.runtime.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.*;
import com.huawei.finance.oj.adapter.OjAdapterConfiguration;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.registry.asset.ArbitrationSkill;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.runtime.loop.LoopContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeepAgentLoopPlannerIntegrationTest {
    @Test
    void deepAgentUsesIsolatedGatewayToolsAcrossConcurrentPlanningTurns() throws Exception {
        ToolGateway gateway = new ToolGateway();
        new OjAdapterConfiguration().agentModelProviderRegistrar(gateway);
        ModelAgentLoopPlanner planner = new ModelAgentLoopPlanner(gateway, new ModelGatewayProperties(),
                assets(), new ContractValidator());

        Action first;
        Action second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> planner.nextAction(context("loop-a")));
            var secondFuture = executor.submit(() -> planner.nextAction(context("loop-b")));
            first = firstFuture.get();
            second = secondFuture.get();
        }

        assertThat(first.actionType()).isEqualTo(ActionType.FINISH);
        assertThat(first.proposalReasonCode()).isEqualTo("DIAGNOSIS_COMPLETE");
        assertThat(second.actionType()).isEqualTo(ActionType.FINISH);
        assertThat(second.proposalReasonCode()).isEqualTo("DIAGNOSIS_COMPLETE");
        assertThat(gateway.calls).hasValue(2);
        assertThat(gateway.finishToolNames).hasSize(2);
    }

    private static LoopContext context(String loopId) {
        Instant now = Instant.now();
        Run run = new Run("tenant", loopId, "agent.test", "session", "root", "trace", "diagnose",
                Status.RUNNING, 1, 4, List.of("cap.balance"), Map.of(), null,
                now.plusSeconds(30), 1, now, now);
        return new LoopContext(run, Map.of(), null, List.of(card()), 3);
    }

    private static AssetBundle assets() {
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setVersion("loop-test-v1");
        skill.setSystem("Use exactly one proposal tool.");
        skill.setUser("goal={{goal}} candidates={{candidates}}");
        return new AssetBundle("v", "v", List.of(card()), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, skill, null, null, null, null);
    }

    private static CapabilityCard card() {
        return new CapabilityCard("cap.balance", "Balance", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.account", List.of("account"), "query balance",
                List.of(), Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 1000,
                Enums.Idempotency.SUPPORTED, "owner", "1", Enums.CapabilityStatus.ACTIVE,
                List.of(), List.of(), List.of(), Enums.GuardrailOwner.DOMAIN, false,
                ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
    }

    private static final class ToolGateway implements ModelGatewayClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final Set<String> finishToolNames = ConcurrentHashMap.newKeySet();
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("single-chat-not-expected", 0);
        }
        @Override public GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
            calls.incrementAndGet();
            String finishTool = toolName(request, "propose_finish_");
            finishToolNames.add(finishTool);
            return GatewayResult.ok(new ToolChatReply("", List.of(
                    new ToolChatReply.ToolCallRequest("call-finish", finishTool,
                            "{\"parameters\":{},\"inputProvenance\":{},"
                                    + "\"proposalReasonCode\":\"DIAGNOSIS_COMPLETE\"}"))), 1);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public boolean available() { return true; }

        private static String toolName(ToolChatRequest request, String prefix) {
            return request.tools().stream()
                    .map(tool -> tool.get("function"))
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(function -> String.valueOf(function.get("name")))
                    .filter(name -> name.startsWith(prefix))
                    .findFirst().orElseThrow();
        }
    }
}
