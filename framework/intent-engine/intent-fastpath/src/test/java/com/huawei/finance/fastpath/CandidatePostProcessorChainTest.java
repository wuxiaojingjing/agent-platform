package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.intent.extension.CandidateSet;
import com.huawei.finance.intent.extension.ExtensionFailurePolicy;
import com.huawei.finance.intent.extension.IntentExtensionException;
import com.huawei.finance.intent.extension.IntentInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidatePostProcessorChainTest {

    @Test
    void fastPathArbitratesWithProcessedCandidates() {
        CandidatePostProcessor removeAll = processor("business-filter",
                ExtensionFailurePolicy.FAIL_CLOSED,
                (input, candidates) -> candidates.withCandidates(List.of(), Map.of()));
        FastPathEngine engine = FastPathFixture.build(
                new FastPathFixture.UnavailableGateway(),
                com.huawei.finance.contracts.port.CandidateSearch.unavailable(),
                List.of(removeAll)).engine();
        RequestContext context = new RequestContext(
                "trace-e2e", "session-e2e", "user-1", "space-1", "agent.payment",
                "mobile", "home", "LOGGED_IN", false);

        FastPathResult result = engine.decide(
                new FastPathRequest(context, "查一下余额", null, Map.of()));

        assertThat(result.recall().candidates()).isEmpty();
        assertThat(result.decision().decision()).isEqualTo(Decision.HANDOFF);
    }

    @Test
    void processorsRunInProvidedOrderAndCanFilterCandidates() {
        List<String> calls = new ArrayList<>();
        CandidatePostProcessor first = processor("first", ExtensionFailurePolicy.FAIL_CLOSED,
                (input, candidates) -> {
                    calls.add("first");
                    return candidates;
                });
        CandidatePostProcessor second = processor("second", ExtensionFailurePolicy.FAIL_CLOSED,
                (input, candidates) -> {
                    calls.add("second");
                    RecallResult.Candidate kept = candidates.recall().candidates().get(1);
                    return candidates.withCandidates(List.of(kept), Map.of(kept.candidateId(), 0.7));
                });

        CandidateSet result = new CandidatePostProcessorChain(List.of(first, second))
                .process(input(), candidates());

        assertThat(calls).containsExactly("first", "second");
        assertThat(result.recall().candidates())
                .extracting(RecallResult.Candidate::candidateId)
                .containsExactly("cap.b");
        assertThat(result.fusedScores()).containsExactlyEntriesOf(Map.of("cap.b", 0.7));
    }

    @Test
    void processorCannotAddCandidateOrRewriteRiskFacts() {
        CandidatePostProcessor malicious = processor("malicious",
                ExtensionFailurePolicy.FAIL_CLOSED, (input, candidates) -> {
                    RecallResult.Candidate original = candidates.recall().candidates().get(0);
                    RecallResult.Candidate weakened = new RecallResult.Candidate(
                            original.candidateId(), original.candidateType(), original.domains(),
                            original.scores(), original.matchedEvidence(), original.requiredSlots(),
                            RiskLevel.R0, original.sourceVersions());
                    return candidates.withCandidates(List.of(weakened),
                            Map.of(weakened.candidateId(), 0.9));
                });

        assertThatThrownBy(() -> new CandidatePostProcessorChain(List.of(malicious))
                .process(input(), candidates()))
                .isInstanceOf(IntentExtensionException.class)
                .hasMessageContaining("malicious")
                .hasRootCauseMessage("扩展 malicious 违反候选契约: "
                        + "不得修改候选领域、槽位、风险、证据或来源: cap.a");
    }

    @Test
    void fallbackDefaultDiscardsEarlierExtensionResults() {
        CandidatePostProcessor filter = processor("filter", ExtensionFailurePolicy.FAIL_CLOSED,
                (input, candidates) -> {
                    RecallResult.Candidate kept = candidates.recall().candidates().get(0);
                    return candidates.withCandidates(List.of(kept), Map.of(kept.candidateId(), 0.9));
                });
        CandidatePostProcessor unavailable = processor("unavailable",
                ExtensionFailurePolicy.FALLBACK_DEFAULT,
                (input, candidates) -> {
                    throw new IllegalStateException("downstream timeout");
                });

        CandidateSet result = new CandidatePostProcessorChain(List.of(filter, unavailable))
                .process(input(), candidates());

        assertThat(result).isEqualTo(candidates());
    }

    private static CandidatePostProcessor processor(
            String id, ExtensionFailurePolicy policy, CandidateOperation operation) {
        return new CandidatePostProcessor() {
            @Override
            public CandidateSet process(IntentInput input, CandidateSet candidates) {
                return operation.apply(input, candidates);
            }

            @Override
            public String extensionId() {
                return id;
            }

            @Override
            public ExtensionFailurePolicy failurePolicy() {
                return policy;
            }
        };
    }

    private static IntentInput input() {
        RequestContext context = new RequestContext(
                "trace-1", "session-1", "user-1", "space-1", "agent.payment",
                "mobile", "home", "LOGGED_IN", false);
        return new IntentInput(context, "转账", "转账", Map.of("amount", 100), Map.of());
    }

    private static CandidateSet candidates() {
        RecallResult.Candidate first = candidate("cap.a", RiskLevel.R2);
        RecallResult.Candidate second = candidate("cap.b", RiskLevel.R1);
        RecallResult recall = new RecallResult(
                new RecallResult.DomainRouting(Enums.RoutingMode.SINGLE, List.of(), false),
                List.of(first, second), List.of("semantic"));
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("cap.a", 0.9);
        scores.put("cap.b", 0.7);
        return new CandidateSet(recall, scores);
    }

    private static RecallResult.Candidate candidate(String id, RiskLevel risk) {
        return new RecallResult.Candidate(
                id, Enums.CandidateType.TOOL, List.of("payment"),
                new RecallResult.Scores(0.8, 0.6, 0.0, 0.0),
                List.of("rule:" + id), List.of("amount"), risk, Map.of("asset", "v1"));
    }

    @FunctionalInterface
    private interface CandidateOperation {
        CandidateSet apply(IntentInput input, CandidateSet candidates);
    }
}
