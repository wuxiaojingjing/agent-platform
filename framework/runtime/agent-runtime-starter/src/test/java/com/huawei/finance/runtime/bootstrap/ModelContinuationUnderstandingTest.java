package com.huawei.finance.runtime.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Context;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.ConfirmationStrength;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Snapshot;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.SwitchMode;
import com.huawei.finance.orchestrator.continuation.ContinuationModelCache;
import com.huawei.finance.registry.asset.ArbitrationSkill;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ModelContinuationUnderstandingTest {

    @Test
    void modelMapsNaturalLanguageCorrectionToWhitelistedSlotUpdate() {
        ModelGatewayClient gateway = new StubGateway("""
                {"event":"CORRECTION","targetRef":"task-1","slotUpdates":{"payee":"李四"},
                 "newGoalSpan":null,"confidence":0.99,"reasonCode":"EXPLICIT_CORRECTION",
                 "confirmationStrength":"NONE"}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContinuationUnderstanding(gateway, new ModelGatewayProperties(), assets,
                new ContractValidator(), ContinuationModelCache.disabled(), new SimpleMeterRegistry());
        Snapshot task = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CORRECTION, Event.CONFIRM, Event.CANCEL),
                Map.of("payee", List.of("张三", "*"), "amount", List.of("1000", "*")),
                "转账", 3, SwitchMode.ALLOW_SWITCH);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "不是张三，是李四", new Context(task, List.of()));

        assertThat(result.event()).isEqualTo(Event.CORRECTION);
        assertThat(result.slotUpdates()).containsExactly(Map.entry("payee", "李四"));
    }

    @Test
    void modelDeclaresExplicitActionSemanticsForNaturalLanguageConfirmation() {
        ModelGatewayClient gateway = new StubGateway("""
                {"event":"CONFIRM","targetRef":"task-1","slotUpdates":{},
                 "newGoalSpan":null,"confidence":0.99,"reasonCode":"EXPLICIT_ACTION",
                 "confirmationStrength":"EXPLICIT_ACTION"}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContinuationUnderstanding(gateway, new ModelGatewayProperties(), assets,
                new ContractValidator(), ContinuationModelCache.disabled(), new SimpleMeterRegistry());
        Snapshot task = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM, Event.CANCEL), Map.of(), "转账", 3,
                SwitchMode.ALLOW_SWITCH);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "请正式提交这笔资金指令", new Context(task, List.of()));

        assertThat(result.event()).isEqualTo(Event.CONFIRM);
        assertThat(result.confirmationStrength()).isEqualTo(ConfirmationStrength.EXPLICIT_ACTION);
    }

    @Test
    void outputWithoutConfirmationStrengthIsRejectedBySchema() {
        ModelGatewayClient gateway = new StubGateway("""
                {"event":"CANCEL","targetRef":"task-1","slotUpdates":{},
                 "newGoalSpan":null,"confidence":0.99,"reasonCode":"MODEL_CANCEL"}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var model = new ModelContinuationUnderstanding(gateway, new ModelGatewayProperties(), assets,
                new ContractValidator(), ContinuationModelCache.disabled(), meters);
        Snapshot task = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CANCEL), Map.of(), "转账", 3, SwitchMode.ALLOW_SWITCH);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "这件事先停下", new Context(task, List.of()));

        assertThat(result.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(result.reasonCode()).isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(meters.get(AgentMetrics.CONTINUATION_MODEL)
                .tag(AgentMetrics.TAG_OUTCOME, "INVALID_SCHEMA").counter().count()).isEqualTo(1);
    }

    @Test
    void promptCarriesRuntimeRefsWithoutRequiringForegroundRefEchoAndCanDeclareAmbiguousResume() {
        StubGateway gateway = new StubGateway("""
                {"event":"UNRESOLVED","targetRef":null,"slotUpdates":{},
                 "newGoalSpan":null,"confidence":0.9,"reasonCode":"AMBIGUOUS_RESUME",
                 "confirmationStrength":"NONE"}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var model = new ModelContinuationUnderstanding(gateway, new ModelGatewayProperties(), assets,
                new ContractValidator(), ContinuationModelCache.disabled(), meters);
        Snapshot first = new Snapshot(RuntimeType.TASK, "task-first", "CLARIFY_PENDING", null,
                List.of(Event.FILL_SLOT), Map.of("cardRef", List.of("*")), "任务一", 2,
                SwitchMode.ALLOW_SWITCH);
        Snapshot second = new Snapshot(RuntimeType.TASK, "task-second", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM), Map.of(), "任务二", 3, SwitchMode.ALLOW_SWITCH);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "回到之前没办完的事", new Context(null, List.of(first, second)));

        assertThat(result.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(result.reasonCode()).isEqualTo("AMBIGUOUS_RESUME");
        assertThat(gateway.captured().get().systemPrompt())
                .contains("作用于当前前台任务时，targetRef 可以逐字复制 foreground.runtimeRef，也可以为 null")
                .contains("RESUME_SUSPENDED 的 targetRef 必须唯一指向 suspended 中用户选择的任务")
                .contains("reasonCode=AMBIGUOUS_RESUME")
                .contains("reasonCode=NEW_GOAL");
        assertThat(gateway.captured().get().userPrompt())
                .contains("task-first", "task-second")
                .contains("knowledgeExamples=[]");
        assertThat(meters.get(AgentMetrics.CONTINUATION_MODEL)
                .tag(AgentMetrics.TAG_OUTCOME, "MODEL_UNRESOLVED").counter().count()).isEqualTo(1);
    }

    @Test
    void promptReceivesExactUserVisibleTranscriptFromSharedTurnSnapshot() {
        StubGateway gateway = new StubGateway("""
                {"event":"REVIEW_ACCEPT","targetRef":"task-1","slotUpdates":{},
                 "newGoalSpan":null,"confidence":0.99,"reasonCode":"ACCEPT_REVIEW",
                 "confirmationStrength":"NONE"}
                """);
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        var model = new ModelContinuationUnderstanding(gateway, new ModelGatewayProperties(), assets,
                new ContractValidator(), ContinuationModelCache.disabled(), new SimpleMeterRegistry());
        Snapshot task = new Snapshot(RuntimeType.TASK, "task-1", "REVIEW_PENDING", null,
                List.of(Event.REVIEW_ACCEPT, Event.CANCEL), Map.of(), "换卡", 3,
                SwitchMode.ALLOW_SWITCH);
        ContextEvidence turn = new ContextEvidence("turn:s#0:utterance",
                ContextEvidence.Kind.USER_TURN,
                Map.of("text", "信用卡", "messages", List.of(
                        Map.of("role", "user", "type", "TEXT", "text", "信用卡"),
                        Map.of("role", "assistant", "type", "TEXT",
                                "text", "请核对换卡信息。",
                                "data", Map.of("actions", List.of(Map.of(
                                        "event", "REVIEW_ACCEPT", "label", "继续")))))),
                "agent-1", "task-1", "turn:s#0", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        IntentContext intentContext = new IntentContext("lease-1", "session-1", "换卡", 4,
                true, Instant.now().plusSeconds(30), Map.of(), List.of(turn), 0);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "继续", new Context(task, List.of()), intentContext);

        assertThat(result.event()).isEqualTo(Event.REVIEW_ACCEPT);
        assertThat(gateway.captured().get().userPrompt())
                .contains("contextStateVersion=4", "请核对换卡信息。", "REVIEW_ACCEPT", "继续");
    }

    @Test
    void unavailableContinuationModelDoesNotAttemptASecondInterpretation() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ModelGatewayClient unavailable = new ModelGatewayClient() {
            @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
                return GatewayResult.unavailable("offline", 0);
            }
            @Override public GatewayResult<String> chat(ChatRequest request) {
                captured.set(request);
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
        var model = new ModelContinuationUnderstanding(unavailable, new ModelGatewayProperties(), assets,
                new ContractValidator(), ContinuationModelCache.disabled(), meters);
        Snapshot task = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM), Map.of(), "转账", 3, SwitchMode.ALLOW_SWITCH);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "继续处理", new Context(task, List.of()));

        assertThat(result.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(result.reasonCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(captured).hasValue(null);
        assertThat(meters.get(AgentMetrics.CONTINUATION_MODEL)
                .tag(AgentMetrics.TAG_OUTCOME, "UNAVAILABLE").counter().count()).isEqualTo(1);
    }

    @Test
    void continuationPromptInjectsOnlyEvaluatedDomainKnowledge() {
        StubGateway gateway = new StubGateway("not-json");
        var assets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setVersion("continuation-test-v1");
        skill.setSystem(assets.continuationSkill().getSystem());
        skill.setUser(assets.continuationSkill().getUser());
        skill.setExamples(List.of(
                Map.of("name", "draft-example", "activation", "DRAFT", "content", "draft"),
                Map.ofEntries(
                        Map.entry("name", "admitted-example"),
                        Map.entry("activation", "EVALUATED_GAP"),
                        Map.entry("content", "approved domain terminology"),
                        Map.entry("baselineModelHealthy", true),
                        Map.entry("baselineModelVersion", "model-baseline-v1"),
                        Map.entry("baselinePromptVersion", "continuation-v5"),
                        Map.entry("failureCategory", ArbitrationSkill.MODEL_SEMANTIC_FAILURE),
                        Map.entry("gapType", ArbitrationSkill.DOMAIN_KNOWLEDGE_GAP),
                        Map.entry("failedEvaluationIds", List.of("baseline-failure")),
                        Map.entry("failedParaphraseCaseIds", List.of("paraphrase-a", "paraphrase-b")),
                        Map.entry("positiveRegressionCaseIds", List.of("positive-regression")),
                        Map.entry("negativeRegressionCaseIds", List.of("negative-regression")))));
        var model = new ModelContinuationUnderstanding(gateway, new ModelGatewayProperties(),
                assets.withContinuationSkill(skill), new ContractValidator(),
                ContinuationModelCache.disabled(), new SimpleMeterRegistry());
        Snapshot task = new Snapshot(RuntimeType.TASK, "task-1", "REVIEW_PENDING", null,
                List.of(Event.REVIEW_ACCEPT), Map.of(), "换卡", 3, SwitchMode.ALLOW_SWITCH);

        var result = model.understand("tenant-1", "agent-1", "session-1",
                "继续当前办理", new Context(task, List.of()));

        assertThat(result.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(gateway.calls()).hasValue(1);
        assertThat(gateway.captured().get().userPrompt())
                .contains("admitted-example")
                .doesNotContain("draft-example");
    }

    private record StubGateway(String output, AtomicReference<ChatRequest> captured,
                               AtomicInteger calls)
            implements ModelGatewayClient {
        private StubGateway(String output) {
            this(output, new AtomicReference<>(), new AtomicInteger());
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
