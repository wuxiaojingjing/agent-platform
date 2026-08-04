package com.huawei.finance.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextualQueryRewriterTest {

    @Test
    void secondCardFollowUpConsumesAccountFact() {
        ModelDrivenContextualQueryRewriter rewriter = rewriter((query, context) -> new ContextualQuery(
                query, "查询账户列表中第二个账户的余额", ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("第二张呢", "fact:accounts", "turn:s#0",
                        "账户列表中第二个账户", "ORDINAL_REFERENCE")),
                List.of("fact:accounts"), List.of(), Map.of("accountOrdinal", 2), List.of(), .99,
                "MODEL_REFERENCE", context.stateVersion(), "model", "prompt"));
        ContextualQuery result = rewriter.rewrite("第二张呢", context(
                evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                        Map.of("cards", List.of(Map.of("index", 1), Map.of("index", 2))))));

        assertThat(result.standaloneQuery()).isEqualTo("查询账户列表中第二个账户的余额");
        assertThat(result.eventType()).isEqualTo(ContextualQuery.EventType.SUPPLEMENT);
        assertThat(result.usedContextRefs()).containsExactly("fact:accounts");
        assertThat(result.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.mention()).isEqualTo("第二张呢");
            assertThat(resolution.contextRef()).isEqualTo("fact:accounts");
        });
    }

    @Test
    void correctionInvalidatesOldPayeeWithoutConfirmingExecution() {
        ModelDrivenContextualQueryRewriter rewriter = rewriter((query, context) -> new ContextualQuery(
                query, "将待确认转账的收款人由张三更正为李四", ContextualQuery.EventType.CORRECTION,
                List.of(new ContextualQuery.Resolution(query, "task.confirmed.payee", "turn:s#0",
                        "张三 -> 李四", "CORRECTION")),
                List.of("task.confirmed.payee"), List.of(), Map.of("payee", "李四"),
                List.of("task.confirmed.payee"), .99, "MODEL_CORRECTION", context.stateVersion(),
                "model", "prompt"));
        ContextualQuery result = rewriter.rewrite("不是张三，是李四", context(
                evidence("task.confirmed.payee", ContextEvidence.Kind.CONFIRMED_INPUT,
                        Map.of("name", "payee", "value", "张三"))));

        assertThat(result.eventType()).isEqualTo(ContextualQuery.EventType.CORRECTION);
        assertThat(result.slotUpdates()).containsEntry("payee", "李四");
        assertThat(result.invalidatedContextRefs()).containsExactly("task.confirmed.payee");
        assertThat(result.standaloneQuery()).contains("张三").contains("李四");
    }

    @Test
    void halfBalanceIsOnlyARequeryBasis() {
        ModelDrivenContextualQueryRewriter rewriter = rewriter((query, context) -> new ContextualQuery(
                query, "使用第二个账户引用的最新可用余额一半转给张三",
                ContextualQuery.EventType.NEW_PARALLEL_TASK,
                List.of(
                        new ContextualQuery.Resolution("第二张卡", "fact:accounts", "turn:s#0",
                                "第二个账户", "ORDINAL_REFERENCE"),
                        new ContextualQuery.Resolution("一半", "fact:balance-snapshot", "turn:s#0",
                                "执行前重查后计算", "REQUERY_THEN_HALF")),
                List.of("fact:accounts", "fact:balance-snapshot"), List.of(),
                Map.of("accountOrdinal", 2, "amountBasis", "REQUERY_THEN_HALF"), List.of(), .99,
                "MODEL_REQUERY", context.stateVersion(), "model", "prompt"));
        ContextualQuery result = rewriter.rewrite("用第二张卡转一半给张三", context(
                evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                        Map.of("cards", List.of(
                                Map.of("index", 1, "accountRef", "account-ref-1"),
                                Map.of("index", 2, "accountRef", "account-ref-2")))),
                evidence("fact:balance-snapshot", ContextEvidence.Kind.TOOL_FACT,
                        Map.of("availableBalance", "8000"))));

        assertThat(result.usedContextRefs()).contains("fact:accounts", "fact:balance-snapshot");
        assertThat(result.resolutions()).anySatisfy(resolution ->
                assertThat(resolution.resolutionType()).isEqualTo("REQUERY_THEN_HALF"));
        assertThat(result.standaloneQuery()).doesNotContain("4000");
        assertThat(result.slotUpdates())
                .containsEntry("accountOrdinal", 2)
                .containsEntry("amountBasis", "REQUERY_THEN_HALF")
                .doesNotContainKey("amount");
    }

    @Test
    void unknownModelReferenceIsRejectedByPolicy() {
        ContextEvidence available = evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                Map.of("cards", List.of()));
        IntentContext context = context(available);
        ContextualQuery proposed = new ContextualQuery(
                "它呢", "查询未知账户", ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("它", "fact:missing", null,
                        "missing", "COREFERENCE")),
                List.of("fact:missing"), List.of(), Map.of(), List.of(), .99,
                "MODEL", context.stateVersion(), "model", "prompt");

        ContextualQuery gated = new ContextRewritePolicyGate().apply("它呢", context, proposed);

        assertThat(gated.standaloneQuery()).isEqualTo("它呢");
        assertThat(gated.usedContextRefs()).isEmpty();
        assertThat(gated.rewriteOutcome())
                .isEqualTo(ContextualQuery.RewriteOutcome.UNRESOLVED_REFERENCE);
    }

    @Test
    void modelCannotTurnHistoricalBalanceIntoExecutableAmount() {
        IntentContext context = context(
                evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT, Map.of()),
                evidence("fact:balance-snapshot", ContextEvidence.Kind.TOOL_FACT,
                        Map.of("availableBalance", "8000")));
        ContextualQuery proposed = new ContextualQuery(
                "转一半", "转账", ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("一半", "fact:balance-snapshot", null,
                        "4000", "REQUERY_THEN_HALF")),
                List.of("fact:balance-snapshot"), List.of(), Map.of("amount", "4000"), List.of(),
                .99, "MODEL_DERIVED_AMOUNT", context.stateVersion(), "model", "prompt");

        ContextualQuery gated = new ContextRewritePolicyGate().apply("转一半", context, proposed);

        assertThat(gated.usedContextRefs()).isEmpty();
        assertThat(gated.slotUpdates()).isEmpty();
        assertThat(gated.rewriteOutcome()).isEqualTo(ContextualQuery.RewriteOutcome.NOT_REQUIRED);
    }

    @Test
    void ordinalBoundaryUsesAuthoritativeListSizeRatherThanAQueryPhrase() {
        for (int listSize : List.of(1, 2, 4, 7)) {
            IntentContext context = context(evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                    Map.of("cards", java.util.stream.IntStream.rangeClosed(1, listSize)
                            .mapToObj(index -> Map.<String, Object>of("index", index)).toList())));
            int requested = listSize + 1;
            ContextualQuery proposed = new ContextualQuery(
                    "ordinal", "query account", ContextualQuery.EventType.SUPPLEMENT,
                    List.of(new ContextualQuery.Resolution("ordinal", "fact:accounts", null,
                            "account ordinal", "ORDINAL_REFERENCE")),
                    List.of("fact:accounts"), List.of(), Map.of("accountOrdinal", requested),
                    List.of(), .99, "MODEL_REFERENCE", context.stateVersion(), "model", "prompt");

            ContextualQuery gated = new ContextRewritePolicyGate().apply("ordinal", context, proposed);

            assertThat(gated.rewriteOutcome())
                    .as("list size %s, requested %s", listSize, requested)
                    .isEqualTo(ContextualQuery.RewriteOutcome.UNRESOLVED_REFERENCE);
            assertThat(gated.slotUpdates()).isEmpty();
        }
    }

    @Test
    void expiredContextCannotBeConsumed() {
        IntentContext expired = new IntentContext("lease", "s", "goal", 3, true,
                Instant.now().minusSeconds(1), Map.of(),
                List.of(evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT, Map.of())), 0);

        ModelDrivenContextualQueryRewriter rewriter = rewriter((query, context) -> {
            throw new AssertionError("过期上下文不得调用模型");
        });
        assertThat(rewriter.rewrite("第二张呢", expired).usedContextRefs()).isEmpty();
    }

    @Test
    void lowConfidenceModelOutputFallsBackWithoutASecondKnowledgeGuess() {
        AtomicInteger calls = new AtomicInteger();
        ModelDrivenContextualQueryRewriter rewriter = rewriter((query, context) -> {
            calls.incrementAndGet();
            return new ContextualQuery(query, "猜测后的查询", ContextualQuery.EventType.SUPPLEMENT,
                    List.of(new ContextualQuery.Resolution("它", "fact:accounts", "turn:s#0",
                            "低置信引用", "COREFERENCE")),
                    List.of("fact:accounts"), List.of(), Map.of(), List.of(), .40,
                    "LOW_CONFIDENCE", context.stateVersion(), "model", "baseline-prompt");
        });

        ContextualQuery result = rewriter.rewrite("它呢", context(
                evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT, Map.of())));

        assertThat(result.standaloneQuery()).isEqualTo("它呢");
        assertThat(result.usedContextRefs()).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    void ordinalResolutionWithoutSemanticSlotIsRejected() {
        IntentContext context = context(evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                Map.of("cards", List.of(Map.of("index", 1), Map.of("index", 2)))));
        ContextualQuery proposed = new ContextualQuery(
                "第二张呢", "查询第二张卡余额", ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("第二张呢", "fact:accounts", null,
                        "第二个账户", "ORDINAL_REFERENCE")),
                List.of("fact:accounts"), List.of(), Map.of(), List.of(), .99,
                "MODEL_OMITTED_SLOT", context.stateVersion(), "model", "prompt");

        ContextualQuery gated = new ContextRewritePolicyGate().apply("第二张呢", context, proposed);

        assertThat(gated.usedContextRefs()).isEmpty();
        assertThat(gated.slotUpdates()).isEmpty();
        assertThat(gated.rewriteOutcome())
                .isEqualTo(ContextualQuery.RewriteOutcome.UNRESOLVED_REFERENCE);
    }

    @Test
    void ordinalOutsideAuthoritativeAccountListIsRejected() {
        IntentContext context = context(evidence("fact:accounts", ContextEvidence.Kind.TOOL_FACT,
                Map.of("cards", List.of(Map.of("index", 1), Map.of("index", 2), Map.of("index", 3)))));
        ContextualQuery proposed = new ContextualQuery(
                "第四张呢", "查询第四张卡余额", ContextualQuery.EventType.SUPPLEMENT,
                List.of(new ContextualQuery.Resolution("第四张呢", "fact:accounts", null,
                        "第四个账户", "ORDINAL_REFERENCE")),
                List.of("fact:accounts"), List.of(), Map.of("accountOrdinal", 4), List.of(), .99,
                "MODEL_OUT_OF_RANGE", context.stateVersion(), "model", "prompt");

        ContextualQuery gated = new ContextRewritePolicyGate().apply("第四张呢", context, proposed);

        assertThat(gated.usedContextRefs()).isEmpty();
        assertThat(gated.slotUpdates()).isEmpty();
        assertThat(gated.rewriteOutcome())
                .isEqualTo(ContextualQuery.RewriteOutcome.UNRESOLVED_REFERENCE);
    }

    private static ModelDrivenContextualQueryRewriter rewriter(ContextualQueryModel model) {
        return new ModelDrivenContextualQueryRewriter(model);
    }

    private static IntentContext context(ContextEvidence... evidence) {
        return new IntentContext("lease", "s", "goal", 7, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(evidence), 0);
    }

    private static ContextEvidence evidence(String ref, ContextEvidence.Kind kind,
                                            Map<String, Object> value) {
        return new ContextEvidence(ref, kind, value, "agent.account", "task-1", "turn:s#0",
                Instant.now(), null, ContextEvidence.Sensitivity.SENSITIVE);
    }
}
