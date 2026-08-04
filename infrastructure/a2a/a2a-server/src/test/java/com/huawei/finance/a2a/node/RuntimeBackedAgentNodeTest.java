package com.huawei.finance.a2a.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.a2a.PrincipalContext;
import com.huawei.finance.contracts.a2a.ResolvedPrincipal;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.runtime.invocation.AgentInvocationOutcome;
import com.huawei.finance.runtime.invocation.AgentInvocationRequest;
import com.huawei.finance.runtime.invocation.AgentInvocationRuntime;
import com.huawei.finance.runtime.invocation.TargetSessionKeys;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class RuntimeBackedAgentNodeTest {

    @Test
    void taskEntersTargetRuntimeWithIsolatedOpaqueSession() {
        AtomicReference<AgentInvocationRequest> captured = new AtomicReference<>();
        AgentInvocationRuntime runtime = request -> {
            captured.set(request);
            TaskResult result = new TaskResult("target-task", Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("balance", "100.00"),
                    request.sourceInvocationId(), GuardrailCheck.passed());
            return new AgentInvocationOutcome("target-task", "SUCCESS", result,
                    result.resultPayload(), List.of(), null);
        };
        var node = new RuntimeBackedAgentNode("agent.account", runtime,
                (tenant, target, principal) -> new ResolvedPrincipal(
                        "subject-token", true, principal.authLevel(), principal.channel(), Map.of()));

        var receipt = node.handle(envelope(DelegationEnvelope.CURRENT_VERSION,
                DelegationMode.TASK, "session-secret", Instant.now().plusSeconds(30)));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsEntry("targetTaskId", "target-task")
                .containsEntry("intentPath", "SLOW_PATH")
                .containsEntry("invocationOrigin", "A2A")
                .containsEntry("principalVerified", true);
        assertThat(captured.get().mode()).isEqualTo(DelegationMode.TASK);
        assertThat(captured.get().sourceInvocationId()).isEqualTo("delegation-1");
        assertThat(captured.get().intentPath()).isEqualTo(Enums.TaskSource.SLOW_PATH);
        assertThat(captured.get().invocationOrigin()).isEqualTo(Enums.InvocationOrigin.A2A);
        assertThat(captured.get().rootTaskId()).isEqualTo("root-task");
        assertThat(captured.get().parentTaskId()).isEqualTo("parent");
        assertThat(captured.get().sourceTaskId()).isEqualTo("source-task");
        assertThat(captured.get().delegationPath())
                .containsExactly("agent.mobile-banking-assistant");
        assertThat(captured.get().targetSessionId())
                .isEqualTo(TargetSessionKeys.of("agent.mobile-banking-assistant",
                        "session-secret", "root-task"))
                .doesNotContain("session-secret")
                .doesNotContain("agent.mobile-banking-assistant")
                .doesNotContain("root-task");
    }

    @Test
    void sourceSessionAndSourceAgentAreBothPartOfIsolationKey() {
        String first = TargetSessionKeys.of("agent.entry-a", "same-session", "root");
        String second = TargetSessionKeys.of("agent.entry-b", "same-session", "root");
        String third = TargetSessionKeys.of("agent.entry-a", "other-session", "root");
        String otherRoot = TargetSessionKeys.of("agent.entry-a", "same-session", "root-2");

        assertThat(first).isNotEqualTo(second).isNotEqualTo(third).isNotEqualTo(otherRoot);
    }

    @Test
    void goalAlsoEntersRuntimeInsteadOfDomainDirectNode() {
        AtomicReference<AgentInvocationRequest> captured = new AtomicReference<>();
        var node = new RuntimeBackedAgentNode("agent.account", request -> {
            captured.set(request);
            return AgentInvocationOutcome.rejected("NO_MATCH");
        }, (tenant, target, principal) -> ResolvedPrincipal.anonymous(principal.channel()));

        var receipt = node.handle(envelope(DelegationEnvelope.CURRENT_VERSION,
                DelegationMode.GOAL, "session", Instant.now().plusSeconds(30)));

        assertThat(captured.get().mode()).isEqualTo(DelegationMode.GOAL);
        assertThat(captured.get().goal()).isEqualTo("查余额");
        assertThat(receipt.reasonCode()).isEqualTo("NO_MATCH");
    }

    @Test
    void missingSlotsStayStructured() {
        var node = new RuntimeBackedAgentNode("agent.account", request ->
                new AgentInvocationOutcome("target-task", "PENDING", null, Map.of(),
                        List.of("accountRef"), "MISSING_SLOT"),
                (tenant, target, principal) -> ResolvedPrincipal.anonymous(principal.channel()));

        var receipt = node.handle(envelope(DelegationEnvelope.CURRENT_VERSION,
                DelegationMode.TASK, "session", Instant.now().plusSeconds(30)));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.NEED_USER);
        assertThat(receipt.missingSlots()).extracting(slot -> slot.slot())
                .containsExactly("accountRef");
        assertThat(receipt.facts()).isEmpty();
    }

    @Test
    void contextEnvelopeIsForwardedAndDeltaReturned() {
        AtomicReference<AgentInvocationRequest> captured = new AtomicReference<>();
        ContextDelta delta = new ContextDelta(4,
                List.of(new ContextEvidence("fact:account:cards", ContextEvidence.Kind.TOOL_FACT,
                        Map.of("cards", List.of("account-ref-1")), "agent.account", "target-task",
                        null, Instant.now(), null, ContextEvidence.Sensitivity.SENSITIVE)),
                List.of(), List.of(), List.of());
        var node = new RuntimeBackedAgentNode("agent.account", request -> {
            captured.set(request);
            TaskResult result = new TaskResult("target-task", Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("cards", List.of("account-ref-1")),
                    request.sourceInvocationId(), GuardrailCheck.passed());
            return new AgentInvocationOutcome("target-task", "SUCCESS", result,
                    result.resultPayload(), List.of(), null, request.intentPath(),
                    Enums.InvocationOrigin.A2A, delta);
        }, (tenant, target, principal) -> ResolvedPrincipal.anonymous(principal.channel()));
        SubtaskContextEnvelope context = new SubtaskContextEnvelope("lease", 4,
                Instant.now().plusSeconds(30), "查余额", Map.of(), List.of(),
                List.of("cap.account.balance.query"),
                List.of(SubtaskContextEnvelope.Scope.SUBTASK),
                SubtaskContextEnvelope.Scope.SUBTASK);
        DelegationEnvelope base = envelope(DelegationEnvelope.CURRENT_VERSION,
                DelegationMode.TASK, "session", Instant.now().plusSeconds(30));
        DelegationEnvelope withContext = new DelegationEnvelope(
                base.version(), base.tenantId(), base.sourceAgentId(), base.targetAgentId(),
                base.rootTaskId(), base.parentTaskId(), base.sourceTaskId(), base.delegationId(),
                base.traceId(), base.principal(), base.mode(), base.intentPath(), base.goal(),
                base.capabilityId(), base.parameters(), base.confirmedFacts(), base.deadline(),
                base.delegationPath(), context);

        var receipt = node.handle(withContext);

        assertThat(captured.get().subtaskContext()).isEqualTo(context);
        assertThat(receipt.contextDelta()).isEqualTo(delta);
    }

    @Test
    void needUserResumeUsesSameTargetSessionNewDelegationAndDoesNotReplayCompletion() {
        List<AgentInvocationRequest> invocations = new ArrayList<>();
        AtomicInteger completedActions = new AtomicInteger();
        AgentInvocationRuntime runtime = request -> {
            invocations.add(request);
            long version = request.subtaskContext().baseStateVersion();
            if (!request.parameters().containsKey("cardRef")) {
                ContextDelta delta = new ContextDelta(version, List.of(), List.of(),
                        List.of(new ContextDelta.PendingQuestion("cardRef", List.of(), "MISSING")),
                        List.of());
                return new AgentInvocationOutcome("creditcard-task", "CLARIFY_PENDING", null,
                        Map.of(), List.of("cardRef"), "MISSING_SLOT", request.intentPath(),
                        Enums.InvocationOrigin.A2A, delta);
            }
            completedActions.incrementAndGet();
            TaskResult result = new TaskResult("creditcard-task", Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("billAmount", "1200.00"),
                    request.sourceInvocationId(), GuardrailCheck.passed());
            ContextEvidence fact = new ContextEvidence("fact:creditcard:bill",
                    ContextEvidence.Kind.TOOL_FACT, result.resultPayload(), "agent.creditcard",
                    "creditcard-task", null, Instant.now(), Instant.now().plusSeconds(30),
                    ContextEvidence.Sensitivity.SENSITIVE);
            return new AgentInvocationOutcome("creditcard-task", "SUCCESS", result,
                    result.resultPayload(), List.of(), null, request.intentPath(),
                    Enums.InvocationOrigin.A2A,
                    new ContextDelta(version, List.of(fact), List.of(), List.of(), List.of()));
        };
        var node = new RuntimeBackedAgentNode("agent.creditcard", runtime,
                (tenant, target, principal) -> new ResolvedPrincipal(
                        "opaque-subject", true, "STRONG", principal.channel(), Map.of()));
        SubtaskContextEnvelope context = context("lease-creditcard", 5, List.of());

        DelegationReceipt first = node.handle(taskEnvelope("agent.creditcard", "root-creditcard",
                "delegation-first", Map.of(), context));
        DelegationReceipt resumed = node.handle(taskEnvelope("agent.creditcard", "root-creditcard",
                "delegation-resume", Map.of("cardRef", "opaque-card-8821"), context));

        assertThat(first.outcome()).isEqualTo(DelegationOutcome.NEED_USER);
        assertThat(first.contextDelta()).isNotNull();
        assertThat(first.contextDelta().pendingQuestions()).extracting(ContextDelta.PendingQuestion::slot)
                .containsExactly("cardRef");
        assertThat(resumed.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(first.delegationId()).isNotEqualTo(resumed.delegationId());
        assertThat(invocations).hasSize(2);
        assertThat(invocations.get(0).targetSessionId())
                .isEqualTo(invocations.get(1).targetSessionId());
        assertThat(invocations).extracting(AgentInvocationRequest::sourceInvocationId)
                .containsExactly("delegation-first", "delegation-resume");
        assertThat(completedActions).hasValue(1);
    }

    @Test
    void sameSourceSessionWithDifferentRootsCannotSeePreviousRootFacts() {
        List<AgentInvocationRequest> invocations = new ArrayList<>();
        var node = new RuntimeBackedAgentNode("agent.research", request -> {
            invocations.add(request);
            TaskResult result = new TaskResult("task-" + request.rootTaskId(),
                    Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    Map.of("compared", request.parameters().get("products")),
                    request.sourceInvocationId(), GuardrailCheck.passed());
            return new AgentInvocationOutcome(result.taskId(), "SUCCESS", result,
                    result.resultPayload(), List.of(), null);
        }, (tenant, target, principal) -> ResolvedPrincipal.anonymous(principal.channel()));

        SubtaskContextEnvelope firstContext = context("lease-root-1", 1,
                List.of(fact("fact:products:ab", Map.of("products", List.of("A", "B")))));
        SubtaskContextEnvelope secondContext = context("lease-root-2", 1,
                List.of(fact("fact:products:cd", Map.of("products", List.of("C", "D")))));
        node.handle(taskEnvelope("agent.research", "root-1", "delegation-root-1",
                Map.of("products", List.of("A", "B")), firstContext));
        node.handle(taskEnvelope("agent.research", "root-2", "delegation-root-2",
                Map.of("products", List.of("C", "D")), secondContext));

        assertThat(invocations).hasSize(2);
        assertThat(invocations.get(0).targetSessionId())
                .isNotEqualTo(invocations.get(1).targetSessionId());
        assertThat(invocations.get(1).subtaskContext().facts())
                .extracting(ContextEvidence::ref)
                .containsExactly("fact:products:cd")
                .doesNotContain("fact:products:ab");
    }

    @Test
    void rejectsWrongVersionMissingPrincipalAndExpiredDeadlineBeforeRuntime() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var node = new RuntimeBackedAgentNode("agent.account", request -> {
            calls.incrementAndGet();
            return AgentInvocationOutcome.rejected("UNEXPECTED");
        }, (tenant, target, principal) -> ResolvedPrincipal.anonymous("TEST"));

        assertThat(node.handle(envelope("a2a/1", DelegationMode.TASK,
                "session", Instant.now().plusSeconds(30))).reasonCode())
                .isEqualTo("A2A_VERSION_UNSUPPORTED");
        DelegationEnvelope withoutPrincipal = new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "tenant", "agent.mobile-banking-assistant",
                "agent.account", "root-task", "parent", "source-task", "delegation-2",
                "trace", null, DelegationMode.TASK, null, "cap.account.balance.query",
                Map.of(), List.of(), Instant.now().plusSeconds(30),
                List.of("agent.mobile-banking-assistant"));
        assertThat(node.handle(withoutPrincipal).reasonCode()).isEqualTo("PRINCIPAL_CONTEXT_INVALID");
        assertThat(node.handle(envelope(DelegationEnvelope.CURRENT_VERSION, DelegationMode.TASK,
                "session", Instant.now().minusSeconds(1))).reasonCode())
                .isEqualTo("DELEGATION_DEADLINE_PASSED");
        assertThat(calls).hasValue(0);
    }

    private static DelegationEnvelope envelope(
            String version, DelegationMode mode, String sourceSession, Instant deadline) {
        return new DelegationEnvelope(version, "tenant", "agent.mobile-banking-assistant",
                "agent.account", "root-task", "parent", "source-task", "delegation-1",
                "trace", new PrincipalContext("opaque-principal", "STRONG", "MOBILE",
                        sourceSession), mode,
                mode == DelegationMode.TASK ? Enums.TaskSource.SLOW_PATH : null,
                mode == DelegationMode.GOAL ? "查余额" : null,
                mode == DelegationMode.TASK ? "cap.account.balance.query" : null,
                Map.of("accountRef", "default"), List.of(), deadline,
                List.of("agent.mobile-banking-assistant"));
    }

    private static DelegationEnvelope taskEnvelope(
            String target, String rootTaskId, String delegationId, Map<String, Object> parameters,
            SubtaskContextEnvelope context) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "tenant",
                "agent.mobile-banking-assistant", target, rootTaskId, "parent", "source-task",
                delegationId, "trace-context", new PrincipalContext("opaque-principal", "STRONG",
                "MOBILE", "same-source-session"), DelegationMode.TASK, Enums.TaskSource.FAST_PATH,
                null, "cap.test", parameters, List.of(), Instant.now().plusSeconds(30),
                List.of("agent.mobile-banking-assistant"), context);
    }

    private static SubtaskContextEnvelope context(
            String leaseId, long version, List<ContextEvidence> facts) {
        return new SubtaskContextEnvelope(leaseId, version, Instant.now().plusSeconds(30),
                "context test", Map.of(), facts, List.of("cap.test"),
                List.of(SubtaskContextEnvelope.Scope.SUBTASK),
                SubtaskContextEnvelope.Scope.SUBTASK);
    }

    private static ContextEvidence fact(String ref, Map<String, Object> value) {
        return new ContextEvidence(ref, ContextEvidence.Kind.TOOL_FACT, value,
                "agent.mobile-banking-assistant", "source-task", null, Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
    }
}
