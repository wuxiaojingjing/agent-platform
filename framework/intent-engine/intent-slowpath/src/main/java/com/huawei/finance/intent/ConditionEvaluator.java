package com.huawei.finance.intent;

import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.PlanCondition;
import com.huawei.finance.contracts.model.SubIntent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministically evaluates business-neutral expressions over typed plan facts. */
public class ConditionEvaluator {

    public enum Verdict { PROCEED, STOP, UNDECIDED }

    public record Evaluation(Verdict verdict, String condition,
                             Map<String, Object> resolvedValues, String reason) {
        public Evaluation {
            resolvedValues = resolvedValues == null ? Map.of() : Map.copyOf(resolvedValues);
        }
    }

    /** Compatibility entry point for callers that only have one predecessor payload. */
    public Verdict evaluate(SubIntent item, Map<String, Object> previousFacts,
                            Map<String, Object> parameters) {
        return explain(item, previousFacts, parameters).verdict();
    }

    /** Compatibility entry point for callers that only have one predecessor payload. */
    public Evaluation explain(SubIntent item, Map<String, Object> previousFacts,
                              Map<String, Object> parameters) {
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        if (item != null && !item.dependsOn().isEmpty()) {
            facts.put(item.dependsOn().getLast(), previousFacts == null ? Map.of() : previousFacts);
        }
        return explainPlan(item, facts, parameters);
    }

    public Evaluation explainPlan(SubIntent item, Map<String, Map<String, Object>> stepFacts,
                                  Map<String, Object> parameters) {
        if (item == null || item.planCondition() == null) {
            return proceed(null, Map.of(), "no-condition");
        }
        PlanCondition condition = item.planCondition();
        if (condition.expression() == null) {
            return undecided(condition.originalText(), "deferred-condition");
        }
        return evaluate(condition.originalText(), condition.expression(), stepFacts, parameters);
    }

    public Evaluation evaluate(String originalText, ConditionExpression expression,
                               Map<String, Map<String, Object>> stepFacts,
                               Map<String, Object> parameters) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            Object result = expression(expression, safe(stepFacts), safe(parameters), values, "root");
            if (!(result instanceof Boolean verdict)) {
                return undecided(originalText, "condition-result-not-boolean");
            }
            return verdict
                    ? proceed(originalText, values, "expression-true")
                    : new Evaluation(Verdict.STOP, originalText, values, "expression-false");
        } catch (UnresolvedValue unresolved) {
            return new Evaluation(Verdict.UNDECIDED, originalText, values, unresolved.getMessage());
        } catch (RuntimeException invalid) {
            return new Evaluation(Verdict.UNDECIDED, originalText, values, "expression-invalid");
        }
    }

    private static Object expression(ConditionExpression expression,
                                     Map<String, Map<String, Object>> facts,
                                     Map<String, Object> parameters,
                                     Map<String, Object> observed, String path) {
        List<ConditionExpression.Operand> operands = expression.operands();
        return switch (expression.operator()) {
            case NOT -> !booleanValue(resolve(operands.getFirst(), facts, parameters, observed, path + ".0"));
            case AND -> operands.stream().allMatch(operand -> booleanValue(
                    resolve(operand, facts, parameters, observed, path)));
            case OR -> operands.stream().anyMatch(operand -> booleanValue(
                    resolve(operand, facts, parameters, observed, path)));
            case EXISTS -> resolveOrMissing(operands.getFirst(), facts, parameters, observed, path + ".0")
                    != Missing.INSTANCE;
            case EQ -> equal(resolve(operands.get(0), facts, parameters, observed, path + ".0"),
                    resolve(operands.get(1), facts, parameters, observed, path + ".1"));
            case NE -> !equal(resolve(operands.get(0), facts, parameters, observed, path + ".0"),
                    resolve(operands.get(1), facts, parameters, observed, path + ".1"));
            case GT, GTE, LT, LTE -> compare(expression.operator(),
                    resolve(operands.get(0), facts, parameters, observed, path + ".0"),
                    resolve(operands.get(1), facts, parameters, observed, path + ".1"));
        };
    }

    private static Object resolve(ConditionExpression.Operand operand,
                                  Map<String, Map<String, Object>> facts,
                                  Map<String, Object> parameters,
                                  Map<String, Object> observed, String path) {
        Object value = resolveOrMissing(operand, facts, parameters, observed, path);
        if (value == Missing.INSTANCE) throw new UnresolvedValue("condition-value-missing:" + path);
        return value;
    }

    private static Object resolveOrMissing(ConditionExpression.Operand operand,
                                           Map<String, Map<String, Object>> facts,
                                           Map<String, Object> parameters,
                                           Map<String, Object> observed, String path) {
        Object value = switch (operand.source()) {
            case STEP_OUTPUT -> pointer(facts.get(operand.stepId()), operand.pointer());
            case PARAMETER -> parameters.containsKey(operand.parameter())
                    ? parameters.get(operand.parameter()) : Missing.INSTANCE;
            case LITERAL -> operand.literal();
            case EXPRESSION -> expression(operand.expression(), facts, parameters, observed, path);
        };
        observed.put(path, value == Missing.INSTANCE ? "<missing>" : value);
        return value;
    }

    private static Object pointer(Map<String, Object> source, String pointer) {
        if (source == null || pointer == null || !pointer.startsWith("/")) return Missing.INSTANCE;
        Object current = source;
        for (String token : pointer.substring(1).split("/", -1)) {
            if (!(current instanceof Map<?, ?> map)) return Missing.INSTANCE;
            String key = token.replace("~1", "/").replace("~0", "~");
            if (!map.containsKey(key)) return Missing.INSTANCE;
            current = map.get(key);
        }
        return current;
    }

    private static boolean equal(Object left, Object right) {
        BigDecimal leftNumber = decimal(left);
        BigDecimal rightNumber = decimal(right);
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber) == 0;
        return Objects.equals(left, right);
    }

    private static boolean compare(ConditionExpression.Operator operator, Object left, Object right) {
        BigDecimal leftNumber = decimal(left);
        BigDecimal rightNumber = decimal(right);
        if (leftNumber == null || rightNumber == null) throw new UnresolvedValue("condition-values-not-numeric");
        int comparison = leftNumber.compareTo(rightNumber);
        return switch (operator) {
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            default -> throw new IllegalArgumentException("not an ordering operator");
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (!(value instanceof String text)) return null;
        try {
            return new BigDecimal(text.replaceAll("[,，\\s]", ""));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        throw new UnresolvedValue("condition-value-not-boolean");
    }

    private static Evaluation proceed(String condition, Map<String, Object> values, String reason) {
        return new Evaluation(Verdict.PROCEED, condition, values, reason);
    }

    private static Evaluation undecided(String condition, String reason) {
        return new Evaluation(Verdict.UNDECIDED, condition, Map.of(), reason);
    }

    private static <K, V> Map<K, V> safe(Map<K, V> value) {
        return value == null ? Map.of() : value;
    }

    private enum Missing { INSTANCE }

    private static final class UnresolvedValue extends RuntimeException {
        private UnresolvedValue(String message) { super(message); }
    }
}
