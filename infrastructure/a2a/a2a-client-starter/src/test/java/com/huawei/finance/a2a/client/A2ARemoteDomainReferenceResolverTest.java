package com.huawei.finance.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class A2ARemoteDomainReferenceResolverTest {

    private static AssetBundle assets;

    @BeforeAll
    static void loadAssets() {
        assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void contextCarriesOnlyTheBasisAndExecutionMaterializesTheAuthoritativeAmount() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<DelegationEnvelope> sent = new AtomicReference<>();
        var resolver = resolver(envelope -> {
            sent.set(envelope);
            calls.incrementAndGet();
            boolean execution = ContextResolutionMarkers.EXECUTION.equals(
                    envelope.parameters().get(ContextResolutionMarkers.RESOLUTION_MODE));
            Map<String, Object> resolved = new java.util.LinkedHashMap<>(Map.of(
                    "accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF",
                    "fromAccount", "opaque-card-ref"));
            if (execution) resolved.put("amount", "3000");
            return DelegationReceipt.succeeded(envelope.delegationId(), Map.of(
                    ContextResolutionMarkers.RESOLVED_SLOTS,
                    resolved,
                    "refreshAtExecution", true));
        });
        RequestContextHolder.set(context());

        Map<String, Object> preview = resolver.references.resolve(
                Map.of("payee", "opaque-payee", "accountOrdinal", 2,
                        "amountBasis", "REQUERY_THEN_HALF"), null, "ignored",
                intentContext(), contextualQuery(), assets.capability("cap.transfer"));

        assertThat(preview).containsEntry("fromAccount", "opaque-card-ref")
                .containsEntry("amountBasis", "REQUERY_THEN_HALF")
                .doesNotContainKey("amount")
                .containsEntry(ContextResolutionMarkers.REFRESH_AT_EXECUTION, true);
        assertThat(sent.get().targetAgentId()).isEqualTo("agent.account");
        assertThat(sent.get().capabilityId()).isEqualTo("cap.account.reference.resolve");
        assertThat(sent.get().parameters()).containsOnlyKeys(
                        "accountOrdinal", "amountBasis", ContextResolutionMarkers.RESOLUTION_MODE)
                .containsEntry(ContextResolutionMarkers.RESOLUTION_MODE,
                        ContextResolutionMarkers.CONTEXT_ONLY)
                .doesNotContainKey("payee");

        var refreshed = resolver.execution.resolve("cap.transfer", preview, null, "ignored");
        assertThat(refreshed.resolved()).isTrue();
        assertThat(refreshed.parameters()).containsEntry("amount", "3000")
                .containsEntry("fromAccount", "opaque-card-ref");
        assertThat(sent.get().parameters()).containsEntry(
                ContextResolutionMarkers.RESOLUTION_MODE, ContextResolutionMarkers.EXECUTION);
        assertThat(calls).hasValue(2);
    }

    @Test
    void sameDomainReadLetsTheTargetCapabilityResolveItsOwnOrdinal() {
        AtomicInteger calls = new AtomicInteger();
        var resolver = resolver(envelope -> {
            calls.incrementAndGet();
            return DelegationReceipt.fatal(envelope.delegationId(), "SHOULD_NOT_CALL", "");
        });
        RequestContextHolder.set(context());

        Map<String, Object> result = resolver.references.resolve(Map.of("accountOrdinal", 2),
                null, "ignored", intentContext(), contextualQuery(),
                assets.capability("cap.account.balance.query"));

        assertThat(result).containsEntry("accountOrdinal", 2)
                .doesNotContainKey(ContextResolutionMarkers.RESOLVER_AGENT_ID);
        assertThat(calls).hasValue(0);
    }

    @Test
    void missingValidatedSourceReferenceDoesNotDelegateOrGuessAnAgent() {
        AtomicInteger calls = new AtomicInteger();
        var resolver = resolver(envelope -> {
            calls.incrementAndGet();
            return DelegationReceipt.fatal(envelope.delegationId(), "SHOULD_NOT_CALL", "");
        });
        RequestContextHolder.set(context());
        IntentContext noSource = new IntentContext("lease", "session", "goal", 1, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(), 0);

        Map<String, Object> result = resolver.references.resolve(Map.of("accountOrdinal", 2),
                null, "ignored", noSource, contextualQuery(), assets.capability("cap.transfer"));

        assertThat(result).containsEntry("accountOrdinal", 2)
                .doesNotContainKey(ContextResolutionMarkers.RESOLVER_AGENT_ID);
        assertThat(calls).hasValue(0);
    }

    @Test
    void outOfSchemaChildSlotsAreRejectedInsteadOfMerged() {
        var resolver = resolver(envelope -> DelegationReceipt.succeeded(envelope.delegationId(), Map.of(
                ContextResolutionMarkers.RESOLVED_SLOTS,
                Map.of("accountOrdinal", 2, "amount", "4000", "unauthorized", "value"),
                "refreshAtExecution", true)));
        RequestContextHolder.set(context());

        Map<String, Object> result = resolver.references.resolve(Map.of("accountOrdinal", 2),
                null, "ignored", intentContext(), contextualQuery(),
                assets.capability("cap.transfer"));

        assertThat(result).containsEntry("accountOrdinal", 2)
                .containsEntry(ContextResolutionMarkers.FAILURE_REASON,
                        "REFERENCE_OUTPUT_OUT_OF_SCHEMA")
                .doesNotContainKeys("amount", "unauthorized",
                        ContextResolutionMarkers.RESOLVER_AGENT_ID);
    }

    @Test
    void referenceResolutionEmitsNestedClientAndDelegateSpansWithoutBusinessValues() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Tracer tracer = new OtelTracer(sdk.getTracer("reference-resolution-test"),
                new OtelCurrentTraceContext(), event -> { });
        Pair resolver = resolver(envelope -> DelegationReceipt.succeeded(envelope.delegationId(),
                Map.of(ContextResolutionMarkers.RESOLVED_SLOTS,
                        Map.of("accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF",
                                "fromAccount", "opaque-card-ref", "amount", "4000"))), tracer);
        RequestContextHolder.set(context());

        Span inbound = tracer.nextSpan().name("inbound").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(inbound)) {
            resolver.references.resolve(
                    Map.of("accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF"),
                    null, "ignored", intentContext(), contextualQuery(),
                    assets.capability("cap.transfer"));
        } finally {
            inbound.end();
        }

        try {
            var spans = exporter.getFinishedSpanItems();
            var client = spans.stream().filter(span -> "agent.a2a.client".equals(span.getName()))
                    .findFirst().orElseThrow();
            var delegate = spans.stream().filter(span -> "agent.a2a.delegate".equals(span.getName()))
                    .findFirst().orElseThrow();
            assertThat(client.getParentSpanId()).isEqualTo(spans.stream()
                    .filter(span -> "inbound".equals(span.getName())).findFirst().orElseThrow()
                    .getSpanId());
            assertThat(delegate.getParentSpanId()).isEqualTo(client.getSpanId());
            assertThat(delegate.getAttributes().toString())
                    .contains("CONTEXT_REFERENCE_RESOLUTION", "cap.account.reference.resolve")
                    .doesNotContain("opaque-card-ref", "4000", "REQUERY_THEN_HALF");
        } finally {
            provider.close();
        }
    }

    private static Pair resolver(com.huawei.finance.a2a.A2ADispatcher dispatcher) {
        return resolver(dispatcher, null);
    }

    private static Pair resolver(com.huawei.finance.a2a.A2ADispatcher dispatcher, Tracer tracer) {
        AgentCard account = new AgentCard("agent.account", "account", "account", "account",
                List.of("account"), "R0", 2000, "owner", "1", AgentCard.Status.ACTIVE,
                Map.of());
        AgentCardRegistry registry = new AgentCardRegistry(List.of(account), List.of());
        DelegationClient delegations = new DelegationClient(dispatcher, new A2AProperties(),
                new SimpleMeterRegistry(), Clock.systemUTC());
        var client = new A2AReferenceResolutionClient(delegations, registry, assets,
                new AgentIdentity("agent.mobile-banking-assistant"), tracer);
        return new Pair(new A2ARemoteDomainReferenceResolver(client, registry),
                new A2AExecutionParameterResolver(client));
    }

    private static RequestContext context() {
        return new RequestContext("0123456789abcdef0123456789abcdef", "session", "user",
                "tenant", "agent.mobile-banking-assistant", "MOBILE", "account", "", false);
    }

    private static IntentContext intentContext() {
        ContextEvidence evidence = new ContextEvidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                Map.of("cards", List.of(Map.of("index", 1), Map.of("index", 2))),
                "agent.account", "source-task", "turn:1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        return new IntentContext("lease", "session", "goal", 1, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(evidence), 0);
    }

    private static ContextualQuery contextualQuery() {
        return new ContextualQuery("input", "standalone", ContextualQuery.EventType.NEW_TASK,
                List.of(
                        new ContextualQuery.Resolution("ordinal", "fact:accounts", "turn:1",
                                "second", "ORDINAL_REFERENCE"),
                        new ContextualQuery.Resolution("ratio", "fact:accounts", "turn:1",
                                "latest-half", "REQUERY_THEN_HALF")),
                List.of("fact:accounts"), List.of(),
                Map.of("accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF"),
                List.of(), .99, "MODEL_CONTEXT", 1, "model", "prompt");
    }

    private record Pair(A2ARemoteDomainReferenceResolver references,
                        A2AExecutionParameterResolver execution) {
    }
}
