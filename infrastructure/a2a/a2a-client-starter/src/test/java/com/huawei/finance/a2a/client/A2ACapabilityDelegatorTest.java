package com.huawei.finance.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.common.context.InvocationLineage;
import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.registry.asset.AssetBundle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class A2ACapabilityDelegatorTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void mapsGuardedTaskToTaskDelegationAndPreservesFacts() {
        AtomicReference<DelegationEnvelope> sent = new AtomicReference<>();
        DelegationClient client = client(envelope -> {
            sent.set(envelope);
            return DelegationReceipt.succeeded(envelope.delegationId(), Map.of("balance", "100.00"));
        });
        CapabilityCard capability = capability("cap.account.balance.query", "agent.account",
                List.of("account"), RiskLevel.R0, List.of());
        A2ACapabilityDelegator delegator = delegator(client, capability,
                agent("agent.account", "account"));
        RequestContext context = new RequestContext("trace-1", "session-1", "user-1", "tenant-1",
                "agent.mobile-banking-assistant", "WEB", "home", "", false);
        RequestContextHolder.set(context);

        TaskResult result = delegator.delegate(
                task(capability, Map.of(), Enums.TaskSource.SLOW_PATH), capability).orElseThrow();

        assertThat(result.success()).isTrue();
        assertThat(result.resultPayload()).containsEntry("balance", "100.00")
                .containsKey("a2aDelegationId");
        assertThat(sent.get().mode().name()).isEqualTo("TASK");
        assertThat(sent.get().tenantId()).isEqualTo("tenant-1");
        assertThat(sent.get().sourceAgentId()).isEqualTo("agent.mobile-banking-assistant");
        assertThat(sent.get().targetAgentId()).isEqualTo("agent.account");
        assertThat(sent.get().capabilityId()).isEqualTo(capability.capabilityId());
        assertThat(sent.get().sourceTaskId()).isEqualTo("task-1");
        assertThat(sent.get().intentPath()).isEqualTo(Enums.TaskSource.SLOW_PATH);
        assertThat(sent.get().confirmedFacts()).singleElement()
                .satisfies(fact -> assertThat(fact).containsEntry("confirmedAt", "now"));
        assertThat(sent.get().deadline()).isNotNull();
        assertThat(context.gatewayCalls()).containsExactly("a2a:agent.account");
        assertThat(context.moduleSteps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.module()).isEqualTo("a2a-client");
                    assertThat(step.role()).isEqualTo("CHILD");
                    assertThat(step.output()).containsEntry("outcome", "SUCCEEDED");
                });
    }

    @Test
    void emitsA2aClientSpanAlongsideExistingDelegateSpan() {
        CapabilityCard capability = capability("cap.account.balance.query", "agent.account",
                List.of("account"), RiskLevel.R0, List.of());
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Tracer tracer = new OtelTracer(sdk.getTracer("a2a-client-test"),
                new OtelCurrentTraceContext(), event -> { });
        A2ACapabilityDelegator delegator = delegator(
                client(envelope -> DelegationReceipt.succeeded(
                        envelope.delegationId(), Map.of("balance", "100.00"))),
                capability, tracer, agent("agent.account", "account"));

        Span inbound = tracer.nextSpan().name("inbound").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(inbound)) {
            delegator.delegate(task(capability, Map.of()), capability).orElseThrow();
        } finally {
            inbound.end();
        }

        try {
            assertThat(exporter.getFinishedSpanItems())
                    .extracting(io.opentelemetry.sdk.trace.data.SpanData::getName)
                    .containsExactlyInAnyOrder("inbound", "agent.a2a.client", "agent.a2a.delegate");
        } finally {
            provider.close();
        }
    }

    @Test
    void domainNotOpenIsAnExplicitResultAndNeverFallsBack() {
        CapabilityCard capability = capability("agent.payment", null,
                List.of("payment"), RiskLevel.R0, List.of());
        DelegationClient client = client(envelope -> new DelegationReceipt(
                DelegationEnvelope.CURRENT_VERSION, envelope.delegationId(),
                DelegationOutcome.DOMAIN_NOT_OPEN, Map.of(), List.of(),
                "DOMAIN_NOT_OPEN", "尚未交付"));
        A2ACapabilityDelegator delegator = delegator(client, capability,
                agent("agent.payment", "payment"));

        assertThat(delegator.handles(capability.capabilityId())).isTrue();
        TaskResult result = delegator.delegate(task(capability, Map.of()), capability).orElseThrow();
        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.FATAL);
        assertThat(result.resultPayload()).containsEntry("reasonCode", "DOMAIN_NOT_OPEN");
    }

    @Test
    void agentCapabilityUsesGoalWhileLeafCapabilityUsesTask() {
        AtomicReference<DelegationEnvelope> sent = new AtomicReference<>();
        DelegationClient client = client(envelope -> {
            sent.set(envelope);
            return DelegationReceipt.succeeded(envelope.delegationId(), Map.of("done", true));
        });
        CapabilityCard agentCapability = new CapabilityCard(
                "agent.finance_assistant", "金融助手", Enums.CapabilityType.AGENT,
                Enums.Granularity.AGENT, null, List.of("finance_assistant"), "金融助手",
                List.of(), Map.of(), Map.of(), List.of(), List.of(), RiskLevel.R0, 5000,
                Enums.Idempotency.SUPPORTED, "test", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN);
        RequestContextHolder.set(new RequestContext(
                "trace-1", "session-1", "opaque-subject", "tenant-1",
                "agent.mobile-banking-assistant", "WEB", "finance-center", "", false,
                new PrincipalState("opaque-subject", true, "STRONG", "WEB"), null));

        delegator(client, agentCapability, agent("agent.finance_assistant", "finance_assistant"))
                .delegate(task(agentCapability, Map.of()), agentCapability).orElseThrow();

        assertThat(sent.get().mode()).isEqualTo(com.huawei.finance.contracts.a2a.DelegationMode.GOAL);
        assertThat(sent.get().intentPath()).isNull();
        assertThat(sent.get().goal()).isEqualTo("测试目标");
    }

    @Test
    void childDelegationKeepsRootPathDeadlineAndVerifiedPrincipal() {
        AtomicReference<DelegationEnvelope> sent = new AtomicReference<>();
        DelegationClient client = client(envelope -> {
            sent.set(envelope);
            return DelegationReceipt.succeeded(envelope.delegationId(), Map.of("done", true));
        });
        CapabilityCard capability = capability("cap.fund.product.query", "agent.fund_service",
                List.of("fund_service"), RiskLevel.R0, List.of());
        AssetBundle assets = new AssetBundle("test", "test", List.of(capability),
                List.of(), List.of(), null, null, null, Map.of(), Map.of(), null,
                null, null, null, null);
        A2ACapabilityDelegator delegator = new A2ACapabilityDelegator(client,
                new AgentCardRegistry(List.of(agent("agent.fund_service", "fund_service")), List.of()),
                assets, new AgentIdentity("agent.finance_assistant"));
        Instant inheritedDeadline = Instant.now().plusSeconds(10);
        RequestContextHolder.set(new RequestContext(
                "trace-1", "target-session", "opaque-subject", "tenant-1",
                "agent.finance_assistant", "WEB", "", "", false,
                new PrincipalState("opaque-subject", true, "STRONG", "WEB"),
                new InvocationLineage("root-mobile", "task-mobile", "task-mobile",
                        List.of("agent.mobile-banking-assistant"), inheritedDeadline)));

        delegator.delegate(task(capability, Map.of()), capability).orElseThrow();

        assertThat(sent.get().rootTaskId()).isEqualTo("root-mobile");
        assertThat(sent.get().parentTaskId()).isEqualTo("task-1");
        assertThat(sent.get().delegationPath())
                .containsExactly("agent.mobile-banking-assistant", "agent.finance_assistant");
        assertThat(sent.get().deadline()).isBeforeOrEqualTo(inheritedDeadline);
        assertThat(sent.get().principal().principalRef()).isEqualTo("opaque-subject");
        assertThat(sent.get().principal().authLevel()).isEqualTo("STRONG");
    }

    @Test
    void networkFailureIsRetryableForReadsAndPartialForSideEffects() {
        DelegationClient broken = client(envelope -> {
            throw new IllegalStateException("gateway down");
        });
        AgentCard target = agent("agent.transfer", "transfer");

        CapabilityCard read = capability("cap.transfer.status", "agent.transfer",
                List.of("transfer"), RiskLevel.R0, List.of());
        TaskResult readResult = delegator(broken, read, target)
                .delegate(task(read, Map.of()), read).orElseThrow();
        assertThat(readResult.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);

        CapabilityCard write = capability("cap.transfer", "agent.transfer",
                List.of("transfer"), RiskLevel.R2, List.of("资金划转"));
        TaskResult writeResult = delegator(broken, write, target)
                .delegate(task(write, Map.of("payee", "张三", "amount", "100")), write).orElseThrow();
        assertThat(writeResult.status()).isEqualTo(Enums.TaskStatus.PARTIAL);
        assertThat(writeResult.failureClass()).isEqualTo(Enums.FailureClass.PARTIAL);
        assertThat(writeResult.resultPayload()).containsEntry("reasonCode", "A2A_GATEWAY_UNAVAILABLE");
    }

    @Test
    void contextDeltaIsCasMergedAndProvenanceIsKept() {
        CapabilityCard capability = capability("cap.account.balance.query", "agent.account",
                List.of("account"), RiskLevel.R0, List.of());
        ContextEvidence returned = new ContextEvidence("fact:agent.account:cards",
                ContextEvidence.Kind.TOOL_FACT, Map.of("cards", List.of("opaque-account")),
                "agent.account", "target-task", null, Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        DelegationClient client = client(envelope -> new DelegationReceipt(
                DelegationEnvelope.CURRENT_VERSION, envelope.delegationId(),
                DelegationOutcome.SUCCEEDED, Map.of("cards", List.of("opaque-account")),
                List.of(), null, null,
                new ContextDelta(2, List.of(returned), List.of(), List.of(), List.of())));
        AssetBundle assets = new AssetBundle("test", "test", List.of(capability),
                List.of(), List.of(), null, null, null, Map.of(), Map.of(), null,
                null, null, null, null);
        var delegator = new A2ACapabilityDelegator(client,
                new AgentCardRegistry(List.of(agent("agent.account", "account")), List.of()),
                assets, new AgentIdentity("agent.mobile-banking-assistant"),
                new SimpleMeterRegistry(), null, (tenant, agent, session) -> 2);
        RequestContextHolder.set(new RequestContext("trace", "session", "user", "tenant",
                "agent.mobile-banking-assistant", "WEB", "", "", false));

        TaskResult result = delegator.delegate(withContext(task(capability, Map.of()), 2), capability)
                .orElseThrow();

        assertThat(result.success()).isTrue();
        assertThat(result.resultPayload()).containsEntry("contextDeltaBaseVersion", 2L)
                .containsKey("contextDeltaFacts");
    }

    @Test
    void staleDeltaAndContextIncapableR2TargetAreRejected() {
        CapabilityCard read = capability("cap.account.balance.query", "agent.account",
                List.of("account"), RiskLevel.R0, List.of());
        DelegationClient staleClient = client(envelope -> new DelegationReceipt(
                DelegationEnvelope.CURRENT_VERSION, envelope.delegationId(),
                DelegationOutcome.SUCCEEDED, Map.of("balance", "100"), List.of(), null, null,
                new ContextDelta(2, List.of(), List.of(), List.of(), List.of())));
        AssetBundle readAssets = new AssetBundle("test", "test", List.of(read), List.of(),
                List.of(), null, null, null, Map.of(), Map.of(), null, null, null, null, null);
        var staleDelegator = new A2ACapabilityDelegator(staleClient,
                new AgentCardRegistry(List.of(agent("agent.account", "account")), List.of()),
                readAssets, new AgentIdentity("agent.mobile-banking-assistant"),
                new SimpleMeterRegistry(), null, (tenant, agent, session) -> 3);
        RequestContextHolder.set(new RequestContext("trace", "session", "user", "tenant",
                "agent.mobile-banking-assistant", "WEB", "", "", false));

        TaskResult stale = staleDelegator.delegate(withContext(task(read, Map.of()), 2), read).orElseThrow();
        assertThat(stale.status()).isEqualTo(Enums.TaskStatus.NEED_USER);
        assertThat(stale.failureClass()).isEqualTo(Enums.FailureClass.NEED_USER);
        assertThat(stale.resultPayload()).containsEntry("reasonCode", "CONTEXT_VERSION_CONFLICT");
        assertThat(stale.resultPayload().get("missingSlots")).asList().singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("slot", "contextReconfirmation");

        CapabilityCard write = capability("cap.transfer", "agent.python", List.of("transfer"),
                RiskLevel.R2, List.of("资金划转"));
        AgentCard python = new AgentCard("agent.python", "transfer", "python", "python",
                List.of("transfer"), "R0", 5000, "external", "1", AgentCard.Status.ACTIVE,
                Map.of(), AgentCard.ContextContract.STATELESS_READ_ONLY, "python");
        TaskResult denied = delegator(staleClient, write, python)
                .delegate(withContext(task(write, Map.of()), 2), write).orElseThrow();
        assertThat(denied.resultPayload()).containsEntry("reasonCode", "CONTEXT_CONTRACT_UNSUPPORTED");
    }

    @Test
    void fullContextPythonTargetUsesLanguageNeutralA2a2JsonContracts() throws Exception {
        AtomicReference<DelegationEnvelope> sent = new AtomicReference<>();
        AtomicReference<DelegationReceipt> returned = new AtomicReference<>();
        CapabilityCard read = capability("cap.research.public-review", "agent.python-research",
                List.of("research"), RiskLevel.R0, List.of());
        ContextEvidence resultFact = new ContextEvidence("fact:research:public-review",
                ContextEvidence.Kind.TOOL_FACT, Map.of("rating", "POSITIVE"),
                "agent.python-research", "python-task", null, Instant.now(), null,
                ContextEvidence.Sensitivity.PUBLIC);
        DelegationClient client = client(envelope -> {
            sent.set(envelope);
            DelegationReceipt receipt = new DelegationReceipt(
                    DelegationEnvelope.CURRENT_VERSION, envelope.delegationId(),
                    DelegationOutcome.SUCCEEDED,
                    Map.of("targetTaskId", "python-task", "rating", "POSITIVE"),
                    List.of(), null, null,
                    new ContextDelta(envelope.subtaskContext().baseStateVersion(),
                            List.of(resultFact), List.of(), List.of(), List.of()));
            returned.set(receipt);
            return receipt;
        });
        AgentCard python = new AgentCard("agent.python-research", "research", "Python Research",
                "public product research", List.of("research"), "R0", 5000, "external", "1",
                AgentCard.Status.ACTIVE, Map.of(), AgentCard.ContextContract.FULL, "python");
        RequestContextHolder.set(new RequestContext("trace", "session", "user", "tenant",
                "agent.mobile-banking-assistant", "WEB", "", "", false));

        TaskResult result = delegator(client, read, python)
                .delegate(withContext(task(read, Map.of("productRef", "opaque-product-a")), 4), read)
                .orElseThrow();

        assertThat(result.success()).isTrue();
        assertThat(python.runtime()).isEqualTo("python");
        assertThat(sent.get().version()).isEqualTo("a2a/2");
        assertThat(sent.get().mode()).isEqualTo(com.huawei.finance.contracts.a2a.DelegationMode.TASK);
        assertThat(sent.get().subtaskContext().readScopes())
                .containsExactly(SubtaskContextEnvelope.Scope.SUBTASK);
        new ContractValidator().validate(SchemaRef.SUBTASK_CONTEXT_ENVELOPE,
                sent.get().subtaskContext()).orThrow("python SubtaskContextEnvelope");
        new ContractValidator().validate(SchemaRef.CONTEXT_DELTA,
                returned.get().contextDelta()).orThrow("python ContextDelta");
        String envelopeJson = ContractJson.mapper().writeValueAsString(sent.get());
        String receiptJson = ContractJson.mapper().writeValueAsString(returned.get());
        assertThat(envelopeJson).doesNotContain("@class", "com.huawei", "java.");
        assertThat(receiptJson).doesNotContain("@class", "com.huawei", "java.");
        assertThat(receiptJson).contains("\"contextDelta\"").contains("\"rating\":\"POSITIVE\"");
    }

    private static A2ACapabilityDelegator delegator(DelegationClient client,
                                                     CapabilityCard capability,
                                                     AgentCard... cards) {
        return delegator(client, capability, null, cards);
    }

    private static A2ACapabilityDelegator delegator(DelegationClient client,
                                                     CapabilityCard capability,
                                                     Tracer tracer,
                                                     AgentCard... cards) {
        AssetBundle assets = new AssetBundle("test", "test", List.of(capability),
                List.of(), List.of(), null, null, null, Map.of(), Map.of(), null,
                null, null, null, null);
        return new A2ACapabilityDelegator(client,
                new AgentCardRegistry(List.of(cards), List.of()), assets,
                new AgentIdentity("agent.mobile-banking-assistant"),
                new SimpleMeterRegistry(), tracer);
    }

    private static DelegationClient client(com.huawei.finance.a2a.A2ADispatcher dispatcher) {
        return new DelegationClient(dispatcher, new A2AProperties(),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    private static AgentCard agent(String id, String domain) {
        return new AgentCard(id, domain, id, id, List.of(domain), "R0", 5000,
                "test", "1.0.0", AgentCard.Status.ACTIVE, Map.of());
    }

    private static UnifiedTask task(CapabilityCard card, Map<String, Object> parameters) {
        return task(card, parameters, Enums.TaskSource.FAST_PATH);
    }

    private static UnifiedTask task(
            CapabilityCard card, Map<String, Object> parameters, Enums.TaskSource intentPath) {
        return new UnifiedTask("task-1", "trace-1", intentPath,
                "测试目标", card.capabilityId(), parameters, card.riskLevel(),
                Map.of("confirmedAt", "now"), GuardrailCheck.passed(), "idem-1", List.of(),
                Instant.now().plusSeconds(30));
    }

    private static UnifiedTask withContext(UnifiedTask task, long version) {
        SubtaskContextEnvelope context = new SubtaskContextEnvelope("lease", version,
                Instant.now().plusSeconds(30), task.goal(), Map.of(), List.of(),
                List.of(task.capabilityId()), List.of(SubtaskContextEnvelope.Scope.SUBTASK),
                SubtaskContextEnvelope.Scope.SUBTASK);
        return new UnifiedTask(task.taskId(), task.traceId(), task.source(), task.invocationOrigin(),
                task.goal(), task.capabilityId(), task.parameters(), task.riskLevel(),
                task.confirmation(), task.guardrailCheck(), task.idempotencyKey(), task.contextRefs(),
                task.deadline(), context);
    }

    private static CapabilityCard capability(String id, String parent, List<String> domains,
                                             RiskLevel risk, List<String> sideEffects) {
        return new CapabilityCard(id, id, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                parent, domains, id, List.of(), Map.of(), Map.of(), List.of(), sideEffects,
                risk, 5000, Enums.Idempotency.SUPPORTED, "test", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN);
    }
}
