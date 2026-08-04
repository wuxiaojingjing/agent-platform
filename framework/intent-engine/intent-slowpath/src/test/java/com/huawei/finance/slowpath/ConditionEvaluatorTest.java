package com.huawei.finance.slowpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.ConditionExpression.Operand;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.PlanCondition;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.ConditionEvaluator.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {
    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    @Test
    void noConditionProceedsAndDeferredConditionWaits() {
        SubIntent plain = new SubIntent(1, "next", "cap.next", "next",
                Enums.IntentRelation.SEQUENTIAL, null,
                PlanResolution.locked("cap.next", "test"));
        SubIntent deferred = new SubIntent(1, "next", "cap.next", "next",
                Enums.IntentRelation.CONDITIONAL, "user condition",
                PlanResolution.locked("cap.next", "test"));

        assertThat(evaluator.explainPlan(plain, Map.of(), Map.of()).verdict()).isEqualTo(Verdict.PROCEED);
        assertThat(evaluator.explainPlan(deferred, Map.of(), Map.of()).verdict()).isEqualTo(Verdict.UNDECIDED);
    }

    @Test
    void evaluatesNumericComparisonFromStepOutputAndParameter() {
        ConditionExpression expression = new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                Operand.stepOutput("step-1", "/value"), Operand.parameter("threshold")));

        assertThat(evaluator.evaluate("condition", expression,
                Map.of("step-1", Map.of("value", "1,200.00")),
                Map.of("threshold", "1000")).verdict()).isEqualTo(Verdict.PROCEED);
        assertThat(evaluator.evaluate("condition", expression,
                Map.of("step-1", Map.of("value", "800")),
                Map.of("threshold", "1000")).verdict()).isEqualTo(Verdict.STOP);
    }

    @Test
    void missingOrUnparseableValuesAreUndecided() {
        ConditionExpression expression = new ConditionExpression(ConditionExpression.Operator.GT, List.of(
                Operand.stepOutput("step-1", "/value"), Operand.parameter("threshold")));

        assertThat(evaluator.evaluate("condition", expression,
                Map.of("step-1", Map.of()), Map.of("threshold", 10)).verdict())
                .isEqualTo(Verdict.UNDECIDED);
        assertThat(evaluator.evaluate("condition", expression,
                Map.of("step-1", Map.of("value", "unknown")), Map.of("threshold", 10)).verdict())
                .isEqualTo(Verdict.UNDECIDED);
    }

    @Test
    void evaluatesBooleanAndNestedExpressions() {
        ConditionExpression equalsFalse = new ConditionExpression(ConditionExpression.Operator.EQ, List.of(
                Operand.stepOutput("step-1", "/flag"), Operand.literal(false)));
        ConditionExpression exists = new ConditionExpression(ConditionExpression.Operator.EXISTS, List.of(
                Operand.stepOutput("step-1", "/flag")));
        ConditionExpression combined = new ConditionExpression(ConditionExpression.Operator.AND, List.of(
                Operand.expression(equalsFalse), Operand.expression(exists)));

        assertThat(evaluator.evaluate("condition", combined,
                Map.of("step-1", Map.of("flag", false)), Map.of()).verdict())
                .isEqualTo(Verdict.PROCEED);
        assertThat(evaluator.evaluate("condition", combined,
                Map.of("step-1", Map.of("flag", true)), Map.of()).verdict())
                .isEqualTo(Verdict.STOP);
    }

    @Test
    void structuredConditionOnSubIntentUsesExplicitStepId() {
        ConditionExpression expression = new ConditionExpression(ConditionExpression.Operator.EQ, List.of(
                Operand.stepOutput("query", "/state"), Operand.literal("READY")));
        SubIntent item = new SubIntent(1, "next", "cap.next", "next",
                Enums.IntentRelation.CONDITIONAL, "when ready",
                PlanResolution.locked("cap.next", "test"), "action", List.of("query"),
                PlanCondition.structured("when ready", expression));

        assertThat(evaluator.explainPlan(item,
                Map.of("query", Map.of("state", "READY")), Map.of()).verdict())
                .isEqualTo(Verdict.PROCEED);
    }
}
