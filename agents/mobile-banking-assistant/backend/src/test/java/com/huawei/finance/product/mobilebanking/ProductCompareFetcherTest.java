package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.intent.ComparePlans;
import com.huawei.finance.runtime.task.AgentTaskExecutor;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import com.huawei.finance.runtime.task.AgentTaskRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductCompareFetcherTest {
    private static final String INSURANCE = "cap.insurance.product.query";
    private static final String WEALTH = "cap.wealth-product.product.query";

    @Test
    void everyCompareLegRunsThroughTaskOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        Map<String, CapabilityCard> cards = Map.of(
                INSURANCE, card(INSURANCE, RiskLevel.R0),
                WEALTH, card(WEALTH, RiskLevel.R0));
        ProductCompareFetcher fetcher = new ProductCompareFetcher(orchestrator);
        ContextLease lease = lease();

        Map<String, Object> result = fetcher.fetch(
                context(), plan(), cards::get, lease);

        assertThat(result)
                .containsEntry("leftName", "产品A")
                .containsEntry("rightName", "产品B")
                .containsEntry("compareReady", true);
        assertThat(orchestrator.requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.lease()).isSameAs(lease);
            assertThat(request.capability().riskLevel()).isEqualTo(RiskLevel.R0);
            assertThat(request.decision().decision())
                    .isEqualTo(com.huawei.finance.contracts.model.Decision.EXECUTE_CAPABILITY);
        });
    }

    @Test
    void compareRejectsAnyNonReadOnlyCapabilityBeforeOrchestration() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        Map<String, CapabilityCard> cards = Map.of(
                INSURANCE, card(INSURANCE, RiskLevel.R0),
                WEALTH, card(WEALTH, RiskLevel.R2));

        Map<String, Object> result = new ProductCompareFetcher(orchestrator)
                .fetch(context(), plan(), cards::get, lease());

        assertThat(result).isEmpty();
        assertThat(orchestrator.requests).hasSize(1);
    }

    private static CapabilityCard card(String capabilityId, RiskLevel risk) {
        return new CapabilityCard(
                capabilityId, capabilityId, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.product", List.of("product"), "", List.of(), Map.of(), Map.of(),
                List.of(), List.of(), risk, 1000, Enums.Idempotency.SUPPORTED,
                "test", "v1", Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN);
    }

    private static RequestContext context() {
        return new RequestContext(
                "trace-compare", "session-compare", "user-1", "space-1", "agent.entry",
                "mobile", "home", "LOGGED_IN", false);
    }

    private static ContextLease lease() {
        return new ContextLease(
                "lease-1", "session-compare", "对比产品A和产品B", Map.of(), List.of(),
                List.of(), 100, 10, List.of(), true, 1L, Instant.now().plusSeconds(30));
    }

    private static IntentPlan plan() {
        return new IntentPlan("对比产品A和产品B", List.of(
                new SubIntent(0, "产品A", INSURANCE, "保险产品A",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked(INSURANCE, ComparePlans.EVIDENCE_PREFIX + "product-a")),
                new SubIntent(1, "产品B", WEALTH, "理财产品B",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked(WEALTH, ComparePlans.EVIDENCE_PREFIX + "product-b"))),
                IntentPlan.Source.RULE);
    }

    private static final class RecordingOrchestrator implements AgentTaskExecutor {
        private final List<AgentTaskRequest> requests = new ArrayList<>();

        @Override
        public AgentTaskOutcome execute(AgentTaskRequest request) {
            requests.add(request);
            String capabilityId = request.capability().capabilityId();
            Map<String, Object> payload = capabilityId.equals(INSURANCE)
                    ? Map.of("name", "产品A", "domain", "保险")
                    : Map.of("name", "产品B", "domain", "理财");
            TaskResult result = new TaskResult(
                    "task-" + capabilityId, Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    payload, "key-" + capabilityId, GuardrailCheck.passed());
            return new AgentTaskOutcome(result.taskId(), result, result.guardrailCheck());
        }
    }
}
