package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.registry.asset.ArbitrationSkill;
import com.huawei.finance.contracts.port.CandidateSearch;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型仲裁的三道关与回退（v0.7 §3.3、实施架构 §2.1.1 回退链）。
 *
 * <p>每个用例都在问同一件事：模型以某种方式不靠谱时，系统会不会照单全收。
 */
class ArbitrationFallbackTest {

    private static RequestContext ctx() {
        return new RequestContext("trace-fb", "s-fb", "u-1", "MOBILE_BANK", "home", "", false);
    }

    private static Decision decide(ModelGatewayClient gateway, String query) {
        FastPathFixture.Built fixture = FastPathFixture.build(gateway);
        return fixture.engine()
                .decide(new FastPathRequest(ctx(), query, null, Map.of()))
                .decision()
                .decision();
    }

    @Test
    @DisplayName("网关不可用 → 回退规则仲裁，出口照常给出")
    void unavailableGatewayFallsBackToRules() {
        assertThat(decide(new FastPathFixture.UnavailableGateway(), "查一下余额"))
                .isEqualTo(Decision.EXECUTE_CAPABILITY);
    }

    @Test
    @DisplayName("模型输出不是合法 JSON → 回退规则仲裁")
    void malformedOutputFallsBackToRules() {
        assertThat(decide(new StubGateway("这就是个大白话回答，不是 JSON"), "查一下余额"))
                .isEqualTo(Decision.EXECUTE_CAPABILITY);
    }

    @Test
    @DisplayName("模型输出合 JSON 但枚举越界 → 回退规则仲裁")
    void invalidEnumFallsBackToRules() {
        String json = """
                {"decision":"JUST_DO_IT","candidateIds":["cap.account.balance.query"],
                 "confidence":0.9,"reasonCode":"HIGH_CONFIDENCE","missingSlots":[]}
                """;
        assertThat(decide(new StubGateway(json), "查一下余额")).isEqualTo(Decision.EXECUTE_CAPABILITY);
    }

    @Test
    @DisplayName("模型选了候选之外的能力 → 回退规则仲裁，不得放行")
    void hallucinatedCapabilityFallsBackToRules() {
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.account.close"],
                 "confidence":0.99,"reasonCode":"HIGH_CONFIDENCE","missingSlots":[]}
                """;
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(new StubGateway(json),
                List.of("cap.account.balance.query", "cap.transfer"));

        FastPathResult result = fixture.engine()
                .decide(new FastPathRequest(ctx(), "查一下余额", null, Map.of()));

        // 回退后选中的必须是真实召回到的能力，而不是模型编出来的那个
        assertThat(result.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(result.decision().selectedCandidateId()).isEqualTo("cap.account.balance.query");
    }

    @Test
    @DisplayName("模型把 TOOL 作为 DELEGATE_GOAL 目标 -> 拒绝非法组合并回退规则")
    void delegateGoalCannotTargetAToolCapability() {
        String json = """
                {"decision":"DELEGATE_GOAL","taskShape":"OPEN_ENDED_DIAGNOSIS",
                 "candidateIds":["cap.payroll.arrival.query"],"subGoals":[],
                 "missingSlots":[],"extractedSlots":{},"confidence":0.95,
                 "reasonCode":"AFTER_OBSERVATION"}
                """;
        FastPathFixture.Built fixture = FastPathFixture.build(new StubGateway(json));

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "工资没到账，帮我排查原因", null, Map.of()));

        assertThat(result.decision().decision()).isNotEqualTo(Decision.DELEGATE_GOAL);
        assertThat(result.decision().modelVersion()).isEqualTo("none");
    }

    @Test
    @DisplayName("模型判直出但规则检出完整条件计划 → fail-safe 收紧为 STATIC_PLAN")
    void multiTaskOverridesModelFastExecute() {
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.account.balance.query"],
                 "confidence":0.95,"reasonCode":"HIGH_CONFIDENCE","missingSlots":[]}
                """;
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(new StubGateway(json),
                List.of("cap.account.balance.query", "cap.transfer"));

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "查余额，再给老徐转 1000；不足就别转", null, Map.of()));

        assertThat(result.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);
        assertThat(result.intentPlan()).isNotNull();
        assertThat(result.intentPlan().fullyResolved()).isTrue();
    }

    @Test
    @DisplayName("START_LOOP 的 SelectionBasis 不是 AFTER_OBSERVATION → 回退规则，不放行模型结论")
    void loopWithoutObservationBasisFallsBackToRules() {
        String json = """
                {"decision":"START_LOOP","taskShape":"OPEN_ENDED_DIAGNOSIS",
                 "candidateIds":["cap.payroll.arrival.query"],
                 "subGoals":[{"id":"diagnose","candidateIds":["cap.payroll.arrival.query"],
                   "dependsOn":[],"selectionBasis":"NOW"}],
                 "confidence":0.96,"reasonCode":"AFTER_OBSERVATION","missingSlots":[],"extractedSlots":{}}
                """;
        FastPathFixture.Built fixture = FastPathFixture.build(new StubGateway(json));

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "工资没到账，帮我排查原因", null, Map.of()));

        assertThat(result.decision().decision()).isNotEqualTo(Decision.START_LOOP);
        assertThat(result.decision().modelVersion()).isEqualTo("none");
    }

    @Test
    @DisplayName("空步骤的伪 Static Plan 回退规则仲裁，并保留低置信澄清候选")
    void emptyStaticPlanFallsBackAndKeepsClarifyCandidates() {
        String json = """
                {"decision":"STATIC_PLAN","taskShape":"SINGLE_ACTION","candidateIds":[],
                 "subGoals":[],"missingSlots":[],"extractedSlots":{},"confidence":0.9,
                 "reasonCode":"CROSS_DOMAIN"}
                """;
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(new StubGateway(json),
                List.of("cap.payroll.status.query", "cap.account.card.status.query",
                        "cap.payroll.arrival.query"));

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "查询处理结果", null, Map.of()));

        assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.CROSS_DOMAIN);
        assertThat(result.decision().modelVersion()).isEqualTo("none");
        assertThat(result.decision().candidateIds()).containsExactly(
                "cap.payroll.status.query", "cap.account.card.status.query",
                "cap.payroll.arrival.query");
    }

    @Test
    @DisplayName("跨域规则与模型单能力出口冲突时，回退规则并保留澄清候选")
    void crossDomainModelConflictKeepsClarifyCandidates() {
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                 "candidateIds":["cap.account.balance.query"],"subGoals":[],"missingSlots":[],
                 "extractedSlots":{},"confidence":0.88,"reasonCode":"LOW_MARGIN"}
                """;
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(new StubGateway(json),
                List.of("cap.account.balance.query", "cap.creditcard.bill.query"), true);

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "查询处理结果", null, Map.of()));

        assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.CROSS_DOMAIN);
        assertThat(result.decision().modelVersion()).isEqualTo("none");
        assertThat(result.decision().candidateIds())
                .hasSize(3)
                .contains("cap.account.balance.query", "cap.creditcard.bill.query");
    }

    @Test
    @DisplayName("旧式 subGoals 字段不符合新契约，必须回退而不是执行伪 Workflow")
    void legacySubGoalShapeCannotStartWorkflow() {
        String json = """
                {"decision":"START_WORKFLOW","taskShape":"FIXED_MULTI_STEP",
                 "candidateIds":["cap.account.balance.query","cap.transfer"],
                 "subGoals":[
                   {"capabilityId":"cap.account.balance.query","params":{}},
                   {"capabilityId":"cap.transfer","params":{"amountBasis":"REQUERY_THEN_HALF"}}],
                 "missingSlots":[],"extractedSlots":{},"confidence":0.96,"reasonCode":"HIGH_CONFIDENCE"}
                """;
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(new StubGateway(json),
                List.of("cap.account.balance.query", "cap.transfer"));

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "用第二张卡转一半给张三", null, Map.of()));

        assertThat(result.decision().decision()).isNotEqualTo(Decision.START_WORKFLOW);
        assertThat(result.decision().modelVersion()).isEqualTo("none");
    }

    @Test
    @DisplayName("上下文已解析的账户依赖不是第二目标，模型也不得把比例依据改成具体金额")
    void resolvedContextDependencyKeepsThePrimaryActionAndDeferredAmount() {
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                 "candidateIds":["cap.transfer"],"subGoals":[],"missingSlots":[],
                 "extractedSlots":{"payee":"张三","amount":"1000"},"confidence":0.99,
                 "reasonCode":"CONFIRMATION_REQUIRED"}
                """;
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(new StubGateway(json),
                List.of("cap.account.balance.query", "cap.transfer"));
        ContextualQuery contextual = new ContextualQuery(
                "用第二张卡转一半给张三",
                "先重新查询第二个账户的权威余额，计算一半后转账给张三",
                ContextualQuery.EventType.NEW_PARALLEL_TASK,
                List.of(
                        new ContextualQuery.Resolution("第二张卡", "fact:accounts", null,
                                "第二个账户", "ORDINAL_REFERENCE"),
                        new ContextualQuery.Resolution("一半", "fact:accounts", null,
                                "执行时按权威余额求值", "REQUERY_THEN_HALF")),
                List.of("fact:accounts"), List.of(),
                Map.of("accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF"),
                List.of(), .99, "MODEL_CONTEXT_DEPENDENCY", 2,
                "context-model", "context-rewrite-v10");

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx(), "用第二张卡转一半给张三", null, Map.of(), contextual));

        assertThat(result.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(result.decision().selectedCandidateId()).isEqualTo("cap.transfer");
        assertThat(result.slots())
                .containsEntry("amountBasis", "REQUERY_THEN_HALF")
                .doesNotContainKey("amount");
    }

    @Test
    @DisplayName("TaskShape Prompt 固化完整 subGoals 与真实 Workflow 边界")
    void promptDefinesCurrentTaskShapeContract() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                 "candidateIds":["cap.account.balance.query"],"subGoals":[],
                 "missingSlots":[],"extractedSlots":{},"confidence":0.96,
                 "reasonCode":"HIGH_CONFIDENCE"}
                """;
        ModelGatewayClient gateway = new CapturingGateway(json, captured);
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(gateway,
                List.of("cap.account.balance.query", "cap.creditcard.bill.query"));

        ContextEvidence history = new ContextEvidence("turn:s-fb#1:utterance",
                ContextEvidence.Kind.USER_TURN, Map.of("text", "刚才查过余额"),
                "agent.mobile-banking-assistant", null, "turn:s-fb#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        IntentContext intentContext = new IntentContext("lease", "s-fb", "查一下", 9, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(history), 0);
        ContextualQuery contextual = ContextualQuery.identity(
                "查一下", intentContext.stateVersion(), intentContext.evidenceRefs());

        fixture.engine().decide(new FastPathRequest(
                ctx(), "查一下", null, Map.of(), contextual, intentContext));
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().promptVersion()).isEqualTo("route-shape-v7");
        assertThat(captured.get().systemPrompt())
                .contains("START_WORKFLOW 只能选择候选中明确标记为 WORKFLOW 的真实 ID")
                .contains("参数解析依赖不构成")
                .contains("amountBasis=REQUERY_THEN_HALF 已满足 amount")
                .contains("OPEN_ENDED_DIAGNOSIS 在后续目标尚不可知时 subGoals 必须为 []")
                .contains("工资没到账，帮我排查原因")
                .contains("\"id\":\"goal-1\"")
                .contains("\"candidateIds\"")
                .contains("\"dependsOn\"")
                .contains("\"selectionBasis\"");
        assertThat(captured.get().userPrompt())
                .contains("评测准入补强：[]")
                .contains("cap.account.balance.query | 资产类型 TOOL")
                .contains("| 名称 查询账户余额")
                .contains("| Loop权限 AUTO_READ_ONLY")
                .contains("上下文改写结果：{")
                .contains("原始对话历史：[")
                .contains("刚才查过余额")
                .contains("turn:s-fb#1:utterance");
    }

    @Test
    @DisplayName("TaskShape 模型只注入评测准入知识，调用次数不作为契约")
    void taskShapePromptInjectsOnlyEvaluatedKnowledge() {
        var assets = FastPathFixture.assets();
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setVersion("route-shape-knowledge-test-v1");
        skill.setSystem(assets.arbitrationSkill().getSystem());
        skill.setUser(assets.arbitrationSkill().getUser());
        skill.setExamples(List.of(
                Map.of("name", "draft-example", "activation", "DRAFT", "content", "draft"),
                Map.ofEntries(
                        Map.entry("name", "admitted-example"),
                        Map.entry("activation", "EVALUATED_GAP"),
                        Map.entry("content", "approved domain terminology"),
                        Map.entry("baselineModelHealthy", true),
                        Map.entry("baselineModelVersion", "model-baseline-v1"),
                        Map.entry("baselinePromptVersion", "route-shape-v2"),
                        Map.entry("failureCategory", ArbitrationSkill.MODEL_SEMANTIC_FAILURE),
                        Map.entry("gapType", ArbitrationSkill.DOMAIN_KNOWLEDGE_GAP),
                        Map.entry("failedEvaluationIds", List.of("baseline-failure")),
                        Map.entry("failedParaphraseCaseIds", List.of("paraphrase-a", "paraphrase-b")),
                        Map.entry("positiveRegressionCaseIds", List.of("positive-regression")),
                        Map.entry("negativeRegressionCaseIds", List.of("negative-regression")))));
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ModelGatewayClient gateway = new CountingCapturingGateway("not-json", captured, calls);
        FastPathFixture.Built fixture = FastPathFixture.build(
                assets.withArbitrationSkill(skill), gateway, CandidateSearch.unavailable());

        fixture.engine().decide(new FastPathRequest(
                ctx(), "工资没到账，帮我排查原因", null, Map.of()));

        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().userPrompt())
                .contains("admitted-example")
                .doesNotContain("draft-example");
    }

    /** 网关可用、按脚本返回固定文本的替身。 */
    private record StubGateway(String chatResponse) implements ModelGatewayClient {

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            // 语义通道保持不可用：本用例关注的是仲裁，不是召回
            return GatewayResult.unavailable("stub", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok(chatResponse, 1);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("stub", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private record CapturingGateway(String chatResponse, AtomicReference<ChatRequest> captured)
            implements ModelGatewayClient {
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("stub", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            captured.set(request);
            return GatewayResult.ok(chatResponse, 1);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(
                String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("stub", 0);
        }
        @Override public boolean available() { return true; }
    }

    private record CountingCapturingGateway(
            String chatResponse, AtomicReference<ChatRequest> captured, AtomicInteger calls)
            implements ModelGatewayClient {
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("stub", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            calls.incrementAndGet();
            captured.set(request);
            return GatewayResult.ok(chatResponse, 1);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(
                String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("stub", 0);
        }
        @Override public boolean available() { return true; }
    }
}
