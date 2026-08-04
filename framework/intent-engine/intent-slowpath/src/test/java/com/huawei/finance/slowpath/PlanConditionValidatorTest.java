package com.huawei.finance.slowpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.ConditionExpression.Operand;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanCondition;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.intent.PlanConditionValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanConditionValidatorTest {
    private final PlanConditionValidator validator = new PlanConditionValidator();

    @Test
    void acceptsDeclaredDependencyPointerAndCompatibleParameter() {
        Fixture fixture = fixture();
        var result = validator.validate(fixture.plan(), fixture.item(), fixture.expression(), fixture.cards());
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsUnknownPointerDependencyAndTypeMismatch() {
        Fixture fixture = fixture();
        ConditionExpression unknown = new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                Operand.stepOutput("query", "/unknown"), Operand.parameter("threshold")));
        ConditionExpression wrongStep = new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                Operand.stepOutput("other", "/value"), Operand.parameter("threshold")));
        ConditionExpression mismatch = new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                Operand.stepOutput("query", "/flag"), Operand.parameter("threshold")));

        assertThat(validator.validate(fixture.plan(), fixture.item(), unknown, fixture.cards()).reason())
                .isEqualTo("OUTPUT_POINTER_NOT_DECLARED");
        assertThat(validator.validate(fixture.plan(), fixture.item(), wrongStep, fixture.cards()).reason())
                .isEqualTo("STEP_NOT_IN_DEPENDENCIES");
        assertThat(validator.validate(fixture.plan(), fixture.item(), mismatch, fixture.cards()).reason())
                .isEqualTo("OPERAND_TYPE_MISMATCH");
    }

    private static Fixture fixture() {
        ConditionExpression expression = new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                Operand.stepOutput("query", "/value"), Operand.parameter("threshold")));
        SubIntent query = new SubIntent(0, "query", "cap.query", "query",
                Enums.IntentRelation.PARALLEL, null, PlanResolution.locked("cap.query", "test"),
                "query", List.of(), null);
        SubIntent action = new SubIntent(1, "action", "cap.action", "action",
                Enums.IntentRelation.CONDITIONAL, "condition", PlanResolution.locked("cap.action", "test"),
                "action", List.of("query"), PlanCondition.structured("condition", expression));
        IntentPlan plan = new IntentPlan("goal", List.of(query, action), IntentPlan.Source.PLANNER);
        CapabilityCard queryCard = card("cap.query", Map.of(), Map.of(
                "type", "object", "properties", Map.of(
                        "value", Map.of("type", "string", "x-value-type", "decimal"),
                        "flag", Map.of("type", "boolean"))));
        CapabilityCard actionCard = card("cap.action", Map.of(
                "type", "object", "properties", Map.of(
                        "threshold", Map.of("type", "string", "x-value-type", "decimal"))), Map.of());
        return new Fixture(plan, action, expression,
                Map.of("cap.query", queryCard, "cap.action", actionCard));
    }

    private static CapabilityCard card(String id, Map<String, Object> input, Map<String, Object> output) {
        return new CapabilityCard(id, id, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.test", List.of("test"), id, List.of(), input, output, List.of(), List.of(),
                RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "test", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false);
    }

    private record Fixture(IntentPlan plan, SubIntent item, ConditionExpression expression,
                           Map<String, CapabilityCard> cards) { }
}
