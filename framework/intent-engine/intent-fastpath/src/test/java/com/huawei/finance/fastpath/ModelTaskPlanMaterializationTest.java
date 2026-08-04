package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTaskPlanMaterializationTest {

    @Test
    void taskShapeSubGoalsBecomeTheExecutableConditionalPlan() {
        String output = """
                {"decision":"STATIC_PLAN","taskShape":"CONDITIONAL_PLAN",
                 "candidateIds":["cap.account.balance.query","cap.transfer"],
                 "subGoals":[
                   {"id":"check-balance","candidateIds":["cap.account.balance.query"],
                    "dependsOn":[],"selectionBasis":"NOW"},
                   {"id":"transfer","candidateIds":["cap.transfer"],
                    "dependsOn":["check-balance"],"selectionBasis":"RESULT_RULE",
                    "conditionText":"不足就别转"}],
                 "missingSlots":[],"extractedSlots":{"payee":"张三","amount":"2000"},
                 "confidence":0.97,"reasonCode":"RESULT_RULE"}
                """;
        var built = FastPathFixture.buildWithBm25Hits(new JsonGateway(output),
                List.of("cap.account.balance.query", "cap.transfer"));
        String query = "帮我转两千给张三，先查余额，不足就别转。";
        FastPathResult result = built.engine().decide(new FastPathRequest(
                new RequestContext("trace-model-plan", "session-model-plan", "user-1",
                        "space-1", "agent.mobile-banking-assistant", "MOBILE_BANK", "home", "", false),
                query, null, Map.of()));

        assertThat(result.decision().decision())
                .as("decision=%s reason=%s trace=%s", result.decision().decision(),
                        result.decision().reasonCode(), built.trace().decision)
                .isEqualTo(Decision.STATIC_PLAN);
        assertThat(result.intentPlan()).isNotNull();
        assertThat(result.intentPlan().source()).isEqualTo(IntentPlan.Source.PLANNER);
        assertThat(result.intentPlan().items()).extracting(item -> item.capabilityId())
                .containsExactly("cap.account.balance.query", "cap.transfer");
        assertThat(result.intentPlan().items().get(1).relation())
                .isEqualTo(Enums.IntentRelation.CONDITIONAL);
        assertThat(result.intentPlan().items().get(1).condition()).isEqualTo("不足就别转");
        assertThat(result.intentPlan().items().get(1).stepId()).isEqualTo("transfer");
        assertThat(result.intentPlan().items().get(1).dependsOn()).containsExactly("check-balance");
        assertThat(result.intentPlan().items().get(1).planCondition().state())
                .isEqualTo(com.huawei.finance.contracts.model.PlanCondition.ResolutionState.DEFERRED);
        assertThat(result.slots()).containsEntry("payee", "张三").containsEntry("amount", "2000");
    }

    private record JsonGateway(String output) implements ModelGatewayClient {
        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("test", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok(output, 1);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("test", 0);
        }
        @Override public boolean available() { return true; }
    }
}
