package com.huawei.finance.runtime.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.context.ContextLeaseCompiler;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.ResolvedPrincipal;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.TaskResultMetadata;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.runtime.AgentResponse;
import com.huawei.finance.runtime.AgentRuntime;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import com.huawei.finance.runtime.task.AgentTaskExecutor;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import com.huawei.finance.runtime.task.AgentTaskRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultAgentInvocationRuntimeTest {

    @Test
    void taskUsesTargetContextGuardrailPathAndDelegationAsIdempotencySeed() {
        Harness harness = new Harness(card(true, List.of()));
        TaskResult result = new TaskResult("target-task", Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE, Map.of("balance", "100"), "delegation-1",
                GuardrailCheck.passed());
        harness.nextTask.set(new AgentTaskOutcome(
                "target-task", result, GuardrailCheck.passed(), "SUCCEEDED"));

        var outcome = harness.runtime.invoke(request(DelegationMode.TASK,
                new ResolvedPrincipal("opaque-subject", true, "STRONG", "MOBILE", Map.of()),
                Map.of(), List.of(Map.of("confirmed", true))));

        AgentTaskRequest captured = harness.capturedTask.get();
        assertThat(captured.source()).isEqualTo(Enums.TaskSource.SLOW_PATH);
        assertThat(captured.invocationOrigin()).isEqualTo(Enums.InvocationOrigin.A2A);
        assertThat(captured.sourceInvocationId()).isEqualTo("delegation-1");
        assertThat(captured.context().agentId()).isEqualTo("agent.account");
        assertThat(captured.parameters()).containsEntry("principalRef", "opaque-subject");
        assertThat(captured.confirmed()).isTrue();
        assertThat(outcome.taskId()).isEqualTo("target-task");
        assertThat(outcome.facts())
                .containsEntry("balance", "100")
                .containsEntry(TaskResultMetadata.TARGET_CAPABILITY_ID,
                        "cap.account.balance.query");
    }

    @Test
    void principalAndConfirmationRequirementsStopBeforeLeafExecution() {
        Harness principalHarness = new Harness(card(true, List.of()));
        var rejected = principalHarness.runtime.invoke(request(DelegationMode.TASK,
                ResolvedPrincipal.anonymous("MOBILE"), Map.of(), List.of()));
        assertThat(rejected.reasonCode()).isEqualTo("PRINCIPAL_REQUIRED");
        assertThat(principalHarness.capturedTask).hasValue(null);

        Harness confirmationHarness = new Harness(card(false, List.of()));
        confirmationHarness.nextTask.set(new AgentTaskOutcome(
                "target-task", null, GuardrailCheck.pending(), "CONFIRM_PENDING"));
        var pending = confirmationHarness.runtime.invoke(request(DelegationMode.TASK,
                new ResolvedPrincipal("opaque", true, "STRONG", "MOBILE", Map.of()),
                Map.of(), List.of()));
        assertThat(pending.reasonCode()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(pending.missingSlots()).containsExactly("confirmation");
    }

    @Test
    void goalUsesFullTargetRuntime() {
        Harness harness = new Harness(card(false, List.of()));
        var outcome = harness.runtime.invoke(request(DelegationMode.GOAL,
                ResolvedPrincipal.anonymous("MOBILE"), Map.of(), List.of()));
        assertThat(harness.goalCalls).hasValue(1);
        assertThat(outcome.taskId()).isNull();
    }

    private static final class Harness {
        private final AtomicReference<AgentTaskRequest> capturedTask = new AtomicReference<>();
        private final AtomicReference<AgentTaskOutcome> nextTask = new AtomicReference<>();
        private final AtomicInteger goalCalls = new AtomicInteger();
        private final DefaultAgentInvocationRuntime runtime;

        private Harness(CapabilityCard card) {
            AssetBundle assets = new AssetBundle("test", "test", List.of(card), List.of(),
                    List.of(), null, null, null, Map.of(), Map.of(), null, null, null, null, null);
            AgentTaskExecutor tasks = request -> {
                capturedTask.set(request);
                return nextTask.get();
            };
            AgentRuntime goals = request -> {
                goalCalls.incrementAndGet();
                RouteDecision decision = RouteDecision.builder()
                        .decision(com.huawei.finance.contracts.model.Decision.HANDOFF)
                        .confidence(0).build();
                return new AgentResponse("trace", "not mine", decision, null,
                        null, null, false, List.of());
            };
            ContextLeaseCompiler leases = new ContextLeaseCompiler(null, null, null, null) {
                @Override
                public ContextLease compile(String agentId, String sessionId, String goal,
                                            Map<String, Object> facts,
                                            List<ContextLease.PendingItem> pendingItems) {
                    return ContextLease.degraded(sessionId, goal, Instant.now().plusSeconds(30));
                }
            };
            runtime = new DefaultAgentInvocationRuntime(goals, tasks,
                    () -> new RuntimeEngines(assets, null, null, null), leases, null,
                    new AgentIdentity("agent.account"));
        }
    }

    private static AgentInvocationRequest request(
            DelegationMode mode, ResolvedPrincipal principal, Map<String, Object> parameters,
            List<Map<String, Object>> confirmed) {
        return new AgentInvocationRequest("tenant", "agent.mobile-banking-assistant",
                "agent.account", "target-session", "root", "trace", mode, principal,
                "查余额", mode == DelegationMode.TASK ? "cap.account.balance.query" : null,
                parameters, confirmed, Instant.now().plusSeconds(30),
                mode == DelegationMode.TASK ? Enums.TaskSource.SLOW_PATH : null,
                Enums.InvocationOrigin.A2A, "delegation-1");
    }

    private static CapabilityCard card(boolean principalRequired, List<String> slots) {
        return new CapabilityCard("cap.account.balance.query", "余额", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.account", List.of("account"), "余额", List.of(),
                Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 1000,
                Enums.Idempotency.SUPPORTED, "account", "1", Enums.CapabilityStatus.ACTIVE,
                List.of(), List.of(), slots, Enums.GuardrailOwner.DOMAIN, principalRequired);
    }
}
