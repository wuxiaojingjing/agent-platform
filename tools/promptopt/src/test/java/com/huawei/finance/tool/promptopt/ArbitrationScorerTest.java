package com.huawei.finance.agent.promptopt;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArbitrationScorerTest {

    @Test
    void scoresMergedTaskShapeContractAndCandidateIds() {
        String output = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                 "candidateIds":["cap.transfer"],"subGoals":[],"missingSlots":[],
                 "extractedSlots":{"payee":"张三","amount":"1000"},
                 "confidence":0.98,"reasonCode":"CONFIRMATION_REQUIRED"}
                """;
        ArbitrationScorer scorer = new ArbitrationScorer(new StubGateway(output),
                new ModelGatewayProperties(), new ContractValidator(), java.util.Set.of("cap.transfer"));
        Trajectory trajectory = new Trajectory("transfer", "给张三转1000",
                "候选 cap.transfer", "assets-v1",
                new Trajectory.Truth("EXECUTE_CAPABILITY", "CONFIRMATION_REQUIRED",
                        "cap.transfer", true, List.of(), Map.of("payee", "张三", "amount", "1000")));

        ArbitrationScorer.Score score = scorer.score("system", List.of(trajectory));

        assertThat(score.passed()).isEqualTo(1);
        assertThat(score.invalidJson()).isZero();
        assertThat(score.outOfScope()).isZero();
        assertThat(score.r2WithoutConfirmation()).isZero();
    }

    @Test
    void rejectsLegacySelectedCandidateIdsField() {
        String legacy = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                 "selectedCandidateIds":["cap.transfer"],"confidence":0.98,
                 "reasonCode":"CONFIRMATION_REQUIRED"}
                """;
        ArbitrationScorer scorer = new ArbitrationScorer(new StubGateway(legacy),
                new ModelGatewayProperties(), new ContractValidator(), java.util.Set.of("cap.transfer"));
        Trajectory trajectory = new Trajectory("legacy", "给张三转1000",
                "候选 cap.transfer", "assets-v1",
                new Trajectory.Truth("EXECUTE_CAPABILITY", "CONFIRMATION_REQUIRED",
                        "cap.transfer", true, List.of(), Map.of()));

        ArbitrationScorer.Score score = scorer.score("system", List.of(trajectory));

        assertThat(score.invalidJson()).isEqualTo(1);
        assertThat(score.passed()).isZero();
    }

    private record StubGateway(String output) implements ModelGatewayClient {
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("unused", 0);
        }

        @Override public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok(output, 1);
        }

        @Override public GatewayResult<List<RerankHit>> rerank(
                String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("unused", 0);
        }

        @Override public boolean available() {
            return true;
        }
    }
}
