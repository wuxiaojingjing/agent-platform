package com.huawei.finance.runtime.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopObservationNormalizerTest {
    private final LoopObservationNormalizer normalizer = new LoopObservationNormalizer();

    @Test
    void backendFreeTextNeverBecomesPlannerFacts() {
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("status", "NOT_ARRIVED", "amount", "100.00",
                        "message", "后端返回的一整段自由文本，不应进入下一轮模型上下文",
                        "nested", Map.of("code", "OK", "detail", "内部诊断详情")),
                null, GuardrailCheck.passed());
        var observation = normalizer.normalizeTask(card(),
                new AgentTaskOutcome("task-1", result, GuardrailCheck.passed()));

        assertThat(observation.facts()).containsEntry("status", "NOT_ARRIVED")
                .containsEntry("amount", "100.00")
                .doesNotContainKeys("message");
        Map<?, ?> nested = (Map<?, ?>) observation.facts().get("nested");
        assertThat(nested.get("code")).isEqualTo("OK");
        assertThat(nested.containsKey("detail")).isFalse();
    }

    @Test
    void declaredOutputSchemaAlsoWhitelistsFactKeys() {
        CapabilityCard card = card(Map.of("type", "object", "properties",
                Map.of("status", Map.of("type", "string"))));
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("status", "OK", "undeclared", true), null, GuardrailCheck.passed());

        assertThat(normalizer.normalizeTask(card,
                new AgentTaskOutcome("task-1", result, GuardrailCheck.passed())).facts())
                .containsOnlyKeys("status");
    }

    @Test
    void schemaDeclaredProductFactsKeepBoundedDisplayValues() {
        CapabilityCard card = card(Map.of("type", "object", "properties", Map.of(
                "returnRate", Map.of("type", "string"),
                "term", Map.of("type", "string"))));
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("returnRate", "业绩比较基准2.6%-3.1%", "term", "180 天",
                        "message", "这段未声明文本不能进入 Planner"),
                null, GuardrailCheck.passed());

        assertThat(normalizer.normalizeTask(card,
                new AgentTaskOutcome("task-1", result, GuardrailCheck.passed())).facts())
                .containsEntry("returnRate", "业绩比较基准2.6%-3.1%")
                .containsEntry("term", "180 天")
                .doesNotContainKey("message");
    }

    private static CapabilityCard card() {
        return card(Map.of());
    }

    private static CapabilityCard card(Map<String, Object> outputSchema) {
        return new CapabilityCard("cap.x", "X", Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.x", List.of("x"), "", List.of(), Map.of(), outputSchema, List.of(), List.of(),
                RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "owner", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false, ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
    }
}
