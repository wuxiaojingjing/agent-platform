package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.event.InputEvent;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContextualRewriteConsumptionTest {

    @Test
    void rewriteRecallAndTaskShapeExposeTheSameRawConversationHistory() {
        FastPathFixture.Built fixture = FastPathFixture.buildWithBm25Hits(
                new FastPathFixture.UnavailableGateway(),
                List.of("cap.account.balance.query", "cap.creditcard.bill.query"));
        ContextEvidence history = new ContextEvidence("turn:s#1:utterance",
                ContextEvidence.Kind.USER_TURN, Map.of("text", "查一下余额"),
                "agent.mobile-banking-assistant", null, "turn:s#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        IntentContext context = new IntentContext("lease", "s", "查一下", 7, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(history), 0);
        List<Map<String, Object>> conversationHistory = context.conversationHistory();
        ContextualQuery contextual = ContextualQuery.identity(
                "查一下", context.stateVersion(), context.evidenceRefs());
        RequestContext ctx = new RequestContext("trace-context-modules", "session", "user", "space",
                "agent.entry", "MOBILE", "home", "", false);

        fixture.engine().decide(new FastPathRequest(
                ctx, "查一下", null, Map.of(), contextual, context));

        assertThat(ctx.moduleSteps()).extracting(step -> step.module())
                .contains("intent-rewrite", "intent-recall", "intent-arbitration");
        assertThat(ctx.moduleSteps())
                .filteredOn(step -> List.of("intent-rewrite", "intent-recall", "intent-arbitration")
                        .contains(step.module()))
                .allSatisfy(step -> {
                    assertThat(step.input()).containsEntry("contextStateVersion", 7L);
                    assertThat(step.input()).containsEntry("conversationHistory", conversationHistory);
                    assertThat(step.input()).containsEntry("availableContext", List.of(history));
                });
    }

    @Test
    void eventRecallAndPipelineConsumeStandaloneQueryWhilePreservingOriginal() {
        FastPathFixture.Built fixture = FastPathFixture.build(new FastPathFixture.UnavailableGateway());
        ContextualQuery contextual = new ContextualQuery(
                "第二张呢", "查询账户列表中第二个账户的余额",
                ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("第二张呢", "fact:accounts", "turn:s#1",
                        "账户列表中第二个账户", "ORDINAL_REFERENCE")),
                List.of("fact:accounts"), List.of(), Map.of("accountOrdinal", 2), List.of(), .99,
                "MODEL_ORDINAL_REFERENCE", 2, "context-model", "context-rewrite-v1");
        RequestContext ctx = new RequestContext("trace", "session", "user", "space",
                "agent.entry", "MOBILE", "home", "", false);

        FastPathResult result = fixture.engine().decide(
                new FastPathRequest(ctx, "第二张呢", null, Map.of(), contextual));

        assertThat(result.event().event()).isEqualTo(InputEvent.SUPPLEMENT);
        assertThat(result.rewrite().original()).isEqualTo("第二张呢");
        assertThat(result.rewrite().semanticText()).isEqualTo("查询账户列表中第二个账户的余额");
        assertThat(result.path().pipeline().standaloneQuery())
                .isEqualTo("查询账户列表中第二个账户的余额");
        assertThat(result.path().pipeline().usedContextRefs()).containsExactly("fact:accounts");
        assertThat(result.slots()).containsEntry("accountOrdinal", 2);
    }

    @Test
    void multiTurnStandaloneQueryUsesTheQwen3EnglishInstructionFormatForRecall() {
        AtomicReference<List<String>> embedded = new AtomicReference<>();
        ModelGatewayClient gateway = new ModelGatewayClient() {
            @Override
            public GatewayResult<List<float[]>> embed(List<String> inputs) {
                embedded.set(List.copyOf(inputs));
                List<float[]> vectors = new ArrayList<>(inputs.size());
                inputs.forEach(ignored -> vectors.add(new float[1024]));
                return GatewayResult.ok(vectors, 1);
            }

            @Override
            public GatewayResult<String> chat(ChatRequest request) {
                return GatewayResult.unavailable("not-needed", 0);
            }

            @Override
            public GatewayResult<List<RerankHit>> rerank(
                    String query, List<String> documents, int topN) {
                return GatewayResult.unavailable("not-needed", 0);
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        FastPathFixture.Built fixture = FastPathFixture.buildWithSemanticChannel(gateway);
        ContextualQuery contextual = new ContextualQuery(
                "第二张呢", "查询账户列表中第二个账户的余额",
                ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("第二张", "fact:accounts", "turn:s#1",
                        "账户列表中第二个账户", "ORDINAL_REFERENCE")),
                List.of("fact:accounts"), List.of(), Map.of("accountOrdinal", 2), List.of(), .99,
                "MODEL_ORDINAL_REFERENCE", 2, "context-model", "context-rewrite-v10");
        RequestContext ctx = new RequestContext("trace-qwen3-instruction", "session", "user", "space",
                "agent.entry", "MOBILE", "home", "", false);

        fixture.engine().decide(new FastPathRequest(
                ctx, "第二张呢", null, Map.of(), contextual));

        assertThat(embedded.get()).containsExactly(
                "Instruct: Given a user request in a mobile banking application, retrieve the most "
                        + "relevant banking capability that can fulfill the request\n"
                        + "Query:查询账户列表中第二个账户的余额");
    }

    @Test
    void standaloneQueryCannotIntroduceExecutableSlotsNotPresentInTheOriginalOrModelUpdates() {
        FastPathFixture.Built fixture = FastPathFixture.build(new FastPathFixture.UnavailableGateway());
        ContextualQuery contextual = new ContextualQuery(
                "第二张呢", "查询尾号3344借记卡的余额",
                ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("第二张呢", "fact:accounts", "turn:s#1",
                        "账户列表中第二个账户", "ORDINAL_REFERENCE")),
                List.of("fact:accounts"), List.of(), Map.of("accountOrdinal", 2), List.of(), .99,
                "MODEL_ORDINAL_REFERENCE", 2, "context-model", "context-rewrite-v5");
        RequestContext ctx = new RequestContext("trace", "session", "user", "space",
                "agent.entry", "MOBILE", "home", "", false);

        FastPathResult result = fixture.engine().decide(
                new FastPathRequest(ctx, "第二张呢", null, Map.of(), contextual));

        assertThat(result.slots())
                .containsEntry("accountOrdinal", 2)
                .doesNotContainKeys("amount", "payee", "fromAccount");
        assertThat(result.rewrite().semanticText()).isEqualTo("查询尾号3344借记卡的余额");
    }

    @Test
    void deferredAmountRewriteDoesNotLetTheRefreshStepReplaceTheUsersTransferGoal() {
        FastPathFixture.Built fixture = FastPathFixture.build(new FastPathFixture.UnavailableGateway());
        ContextualQuery contextual = new ContextualQuery(
                "用第二张卡转一半给张三",
                "先重新查询尾号 3344 借记卡的权威可用余额，然后计算其一半金额，再将该金额转账给张三",
                ContextualQuery.EventType.NEW_PARALLEL_TASK,
                List.of(
                        new ContextualQuery.Resolution("第二张卡", "fact:accounts", "turn:s#1",
                                "账户列表中第二个账户", "ORDINAL_REFERENCE"),
                        new ContextualQuery.Resolution("一半", "fact:accounts", "turn:s#1",
                                "执行时按权威余额计算一半", "REQUERY_THEN_HALF")),
                List.of("fact:accounts"), List.of(),
                Map.of("accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF"),
                List.of(), .99, "MODEL_DEFERRED_AMOUNT", 2,
                "context-model", "context-rewrite-v10");
        RequestContext ctx = new RequestContext("trace", "session", "user", "space",
                "agent.entry", "MOBILE", "home", "", false);

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx, "用第二张卡转一半给张三", null, Map.of(), contextual));

        assertThat(result.decision().selectedCandidateId()).isEqualTo("cap.transfer");
        assertThat(result.rewrite().semanticText()).isEqualTo("用第二张卡转一半给张三");
        assertThat(result.path().pipeline().standaloneQuery()).isEqualTo(contextual.standaloneQuery());
        assertThat(result.slots())
                .containsEntry("accountOrdinal", 2)
                .containsEntry("amountBasis", "REQUERY_THEN_HALF")
                .doesNotContainKey("amount");
    }
}
