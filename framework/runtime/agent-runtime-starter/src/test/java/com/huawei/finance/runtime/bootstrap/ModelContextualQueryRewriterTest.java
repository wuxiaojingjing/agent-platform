package com.huawei.finance.runtime.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.context.ContextRewritePolicyGate;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.ArbitrationSkill;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelContextualQueryRewriterTest {

    @Test
    void schemaValidatedModelRewriteIsCachedByStateVersion() {
        AtomicInteger calls = new AtomicInteger();
        StubGateway gateway = new StubGateway(calls, """
                {"originalQuery":"它最近三笔呢","standaloneQuery":"查询账户引用的最近三笔流水",
                 "eventType":"SUPPLEMENT","resolutions":[{"mention":"它","contextRef":"fact:account",
                 "sourceTurnRef":"turn:s#1","resolution":"上轮账户引用","resolutionType":"COREFERENCE"}],
                 "usedContextRefs":["fact:account"],"unusedContextRefs":[],"slotUpdates":{},
                 "invalidatedContextRefs":[],"confidence":0.96,"reasonCode":"MODEL_COREFERENCE",
                 "stateVersion":3}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContextualQueryRewriter(gateway, new ModelGatewayProperties(),
                assets, new ContractValidator(), new SimpleMeterRegistry());
        IntentContext context = context(3);

        ContextualQuery first = model.rewrite("它最近三笔呢", context);
        ContextualQuery cached = model.rewrite("它最近三笔呢", context);
        ContextualQuery gated = new ContextRewritePolicyGate()
                .apply("它最近三笔呢", context, first);

        assertThat(gated.usedContextRefs()).containsExactly("fact:account");
        assertThat(gated.standaloneQuery()).contains("最近三笔流水");
        assertThat(cached).isEqualTo(first);
        assertThat(calls).hasValue(1);
        assertThat(gateway.captured().get().userPrompt())
                .contains("knowledgeExamples=[]")
                .contains("conversationHistory=[{")
                .contains("上一轮查了账户余额");
        assertThat(gateway.captured().get().promptVersion()).isEqualTo("context-rewrite-v10");
        assertThat(gateway.captured().get().systemPrompt())
                .contains("不负责判断序号是否")
                .contains("即使请求序号大于集合当前项数，也必须")
                .contains("后置 PolicyGate");
    }

    @Test
    void outOfRangeOrdinalIsStillStructuredForAuthoritativePolicyValidation() {
        AtomicInteger calls = new AtomicInteger();
        StubGateway gateway = new StubGateway(calls, """
                {"originalQuery":"第四张呢","standaloneQuery":"查询账户列表中第四个账户的余额",
                 "eventType":"SUPPLEMENT","resolutions":[{"mention":"第四张",
                 "contextRef":"fact:accounts","sourceTurnRef":"turn:s#1",
                 "resolution":"账户列表中第四个账户","resolutionType":"ORDINAL_REFERENCE"}],
                 "usedContextRefs":["fact:accounts"],"unusedContextRefs":[],
                 "slotUpdates":{"accountOrdinal":4},"invalidatedContextRefs":[],
                 "confidence":0.98,"reasonCode":"MODEL_ORDINAL_REFERENCE","stateVersion":3}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContextualQueryRewriter(gateway, new ModelGatewayProperties(),
                assets, new ContractValidator(), new SimpleMeterRegistry());
        IntentContext context = accountListContext(3, 3);

        ContextualQuery proposed = model.rewrite("第四张呢", context);
        ContextualQuery gated = new ContextRewritePolicyGate()
                .apply("第四张呢", context, proposed);

        assertThat(proposed.usedContextRefs()).containsExactly("fact:accounts");
        assertThat(proposed.slotUpdates()).containsEntry("accountOrdinal", 4);
        assertThat(gated.rewriteOutcome())
                .isEqualTo(ContextualQuery.RewriteOutcome.UNRESOLVED_REFERENCE);
        assertThat(gated.usedContextRefs()).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    void invalidJsonFallsBackToIdentity() {
        AtomicInteger calls = new AtomicInteger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContextualQueryRewriter(
                new StubGateway(calls, "not-json"), new ModelGatewayProperties(),
                assets, new ContractValidator(), meters);

        ContextualQuery result = model.rewrite("它呢", context(3));

        assertThat(result.standaloneQuery()).isEqualTo("它呢");
        assertThat(result.usedContextRefs()).isEmpty();
        assertThat(calls).hasValue(1);
        assertThat(meters.get(AgentMetrics.CONTEXT_REWRITE)
                .tag(AgentMetrics.TAG_OUTCOME, "INVALID_SCHEMA").counter().count()).isEqualTo(1);
    }

    @Test
    void unavailableModelFallsBackWithoutASecondKnowledgeGuess() {
        AtomicInteger calls = new AtomicInteger();
        ModelGatewayClient unavailable = new ModelGatewayClient() {
            @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
                return GatewayResult.unavailable("offline", 0);
            }
            @Override public GatewayResult<String> chat(ChatRequest request) {
                calls.incrementAndGet();
                throw new AssertionError("unavailable model must not be called");
            }
            @Override public GatewayResult<List<RerankHit>> rerank(
                    String query, List<String> documents, int topN) {
                return GatewayResult.unavailable("offline", 0);
            }
            @Override public boolean available() { return false; }
        };
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var model = new ModelContextualQueryRewriter(unavailable, new ModelGatewayProperties(),
                assets, new ContractValidator(), meters);

        ContextualQuery result = model.rewrite("它呢", context(3));

        assertThat(result.standaloneQuery()).isEqualTo("它呢");
        assertThat(result.usedContextRefs()).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(meters.get(AgentMetrics.CONTEXT_REWRITE)
                .tag(AgentMetrics.TAG_OUTCOME, "UNAVAILABLE").counter().count()).isEqualTo(1);
    }

    @Test
    void transientUnavailableResultRetriesTheSameReadOnlyInference() {
        AtomicInteger calls = new AtomicInteger();
        ModelGatewayClient transientGateway = new ModelGatewayClient() {
            @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
                return GatewayResult.unavailable("unused", 0);
            }
            @Override public GatewayResult<String> chat(ChatRequest request) {
                if (calls.incrementAndGet() == 1) {
                    return GatewayResult.unavailable("tls-handshake", 1);
                }
                return GatewayResult.ok("""
                        {"originalQuery":"它呢","standaloneQuery":"查询该账户余额",
                         "eventType":"SUPPLEMENT","resolutions":[{"mention":"它",
                         "contextRef":"fact:account","sourceTurnRef":"turn:s#1",
                         "resolution":"上轮账户","resolutionType":"COREFERENCE"}],
                         "usedContextRefs":["fact:account"],"unusedContextRefs":[],
                         "slotUpdates":{},"invalidatedContextRefs":[],"confidence":0.96,
                         "reasonCode":"MODEL_COREFERENCE","stateVersion":3}
                        """, 1);
            }
            @Override public GatewayResult<List<RerankHit>> rerank(
                    String query, List<String> documents, int topN) {
                return GatewayResult.unavailable("unused", 0);
            }
            @Override public boolean available() { return true; }
        };
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.getContextRewrite().setMaxAttempts(2);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContextualQueryRewriter(transientGateway, properties,
                assets, new ContractValidator(), new SimpleMeterRegistry());

        ContextualQuery result = model.rewrite("它呢", context(3));

        assertThat(result.usedContextRefs()).containsExactly("fact:account");
        assertThat(calls).hasValue(2);
    }

    @Test
    void onlyAdmittedKnowledgeIsInjectedAndModelFailureDoesNotTriggerAnotherCall() {
        AtomicInteger calls = new AtomicInteger();
        StubGateway gateway = new StubGateway(calls, "not-json");
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setVersion("context-test-v1");
        skill.setSystem(assets.contextRewriteSkill().getSystem());
        skill.setUser(assets.contextRewriteSkill().getUser());
        skill.setExamples(List.of(
                Map.of("name", "draft-example", "activation", "DRAFT", "content", "draft"),
                Map.ofEntries(
                        Map.entry("name", "admitted-example"),
                        Map.entry("activation", "EVALUATED_GAP"),
                        Map.entry("content", "approved terminology"),
                        Map.entry("baselineModelHealthy", true),
                        Map.entry("baselineModelVersion", "model-baseline-v1"),
                        Map.entry("baselinePromptVersion", "context-rewrite-v6"),
                        Map.entry("failureCategory", ArbitrationSkill.MODEL_SEMANTIC_FAILURE),
                        Map.entry("gapType", ArbitrationSkill.DOMAIN_KNOWLEDGE_GAP),
                        Map.entry("failedEvaluationIds", List.of("baseline-failure")),
                        Map.entry("failedParaphraseCaseIds", List.of("paraphrase-a", "paraphrase-b")),
                        Map.entry("positiveRegressionCaseIds", List.of("positive-regression")),
                        Map.entry("negativeRegressionCaseIds", List.of("negative-regression")))));
        var model = new ModelContextualQueryRewriter(gateway, new ModelGatewayProperties(),
                assets.withContextRewriteSkill(skill), new ContractValidator(),
                new SimpleMeterRegistry());

        ContextualQuery result = model.rewrite("它呢", context(3));

        assertThat(result.standaloneQuery()).isEqualTo("它呢");
        assertThat(calls).hasValue(1);
        assertThat(gateway.captured().get().userPrompt())
                .contains("admitted-example")
                .doesNotContain("draft-example");
    }

    @Test
    void schemaDiagnosticsNameOnlyContractFieldsAndNeverEchoModelValues() {
        assertThat(ModelContextualQueryRewriter.schemaFailureCodes(
                "{\"standaloneQuery\":7,\"secret-user-text\":\"private-value\"}"))
                .contains("MISSING:originalQuery", "TYPE:standaloneQuery", "ADDITIONAL_PROPERTY")
                .allSatisfy(code -> assertThat(code)
                        .doesNotContain("secret-user-text")
                        .doesNotContain("private-value"));
        assertThat(ModelContextualQueryRewriter.schemaFailureCodes("not-json"))
                .containsExactly("INVALID_JSON");
        assertThat(ModelContextualQueryRewriter.schemaFailureCodes("{\"originalQuery\":\"x\""))
                .containsExactly("TRUNCATED_JSON");
    }

    private static IntentContext context(long version) {
        ContextEvidence evidence = new ContextEvidence("fact:account",
                ContextEvidence.Kind.TOOL_FACT, Map.of("accountRef", "opaque-account-ref"),
                "agent.account", "task", "turn:s#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        ContextEvidence history = new ContextEvidence("turn:s#1:utterance",
                ContextEvidence.Kind.USER_TURN, Map.of("text", "上一轮查了账户余额"),
                "agent.account", "task", "turn:s#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        return new IntentContext("lease", "s", "goal", version, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(evidence, history), 0);
    }

    private static IntentContext accountListContext(long version, int accountCount) {
        List<Map<String, Object>> cards = java.util.stream.IntStream
                .rangeClosed(1, accountCount)
                .mapToObj(index -> Map.<String, Object>of("index", index))
                .toList();
        ContextEvidence accounts = new ContextEvidence("fact:accounts",
                ContextEvidence.Kind.TOOL_FACT, Map.of("cards", cards),
                "agent.account", "task", "turn:s#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        ContextEvidence history = new ContextEvidence("turn:s#1:utterance",
                ContextEvidence.Kind.USER_TURN, Map.of("text", "上一轮查询了三张银行卡"),
                "agent.account", "task", "turn:s#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        return new IntentContext("lease", "s", "goal", version, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(accounts, history), 0);
    }

    private record StubGateway(AtomicInteger calls, String output,
                               java.util.concurrent.atomic.AtomicReference<ChatRequest> captured)
            implements ModelGatewayClient {
        private StubGateway(AtomicInteger calls, String output) {
            this(calls, output, new java.util.concurrent.atomic.AtomicReference<>());
        }
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            calls.incrementAndGet();
            captured.set(request);
            return GatewayResult.ok(output, 1);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(
                String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("unused", 0);
        }
        @Override public boolean available() { return true; }
    }
}
