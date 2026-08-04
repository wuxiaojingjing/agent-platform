package com.huawei.finance.intent;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.SubIntent;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates condition references and types exclusively from capability JSON Schemas. */
public final class PlanConditionValidator {

    public record Result(boolean valid, String reason) {
        public static Result ok() { return new Result(true, "OK"); }
        public static Result invalid(String reason) { return new Result(false, reason); }
    }

    public Result validate(IntentPlan plan, SubIntent item, ConditionExpression expression,
                           Map<String, CapabilityCard> cards) {
        if (plan == null || item == null || expression == null) return Result.invalid("CONDITION_MISSING");
        Map<String, SubIntent> steps = new LinkedHashMap<>();
        plan.items().forEach(step -> steps.put(step.stepId(), step));
        return expression(expression, item, steps, cards == null ? Map.of() : cards);
    }

    private Result expression(ConditionExpression expression, SubIntent item,
                              Map<String, SubIntent> steps, Map<String, CapabilityCard> cards) {
        int size = expression.operands().size();
        if (switch (expression.operator()) {
            case NOT, EXISTS -> size != 1;
            case EQ, NE, GT, GTE, LT, LTE -> size != 2;
            case AND, OR -> size < 2;
        }) return Result.invalid("INVALID_OPERAND_COUNT");

        for (ConditionExpression.Operand operand : expression.operands()) {
            Result shape = operandShape(operand);
            if (!shape.valid()) return shape;
            if (operand.source() == ConditionExpression.Source.EXPRESSION) {
                Result nested = expression(operand.expression(), item, steps, cards);
                if (!nested.valid()) return nested;
            }
        }

        if (expression.operator() == ConditionExpression.Operator.AND
                || expression.operator() == ConditionExpression.Operator.OR
                || expression.operator() == ConditionExpression.Operator.NOT) {
            if (expression.operands().stream().anyMatch(op -> op.source() != ConditionExpression.Source.EXPRESSION)) {
                return Result.invalid("LOGICAL_OPERAND_NOT_EXPRESSION");
            }
            return Result.ok();
        }
        if (expression.operator() == ConditionExpression.Operator.EXISTS) return reference(
                expression.operands().getFirst(), item, steps, cards).result();

        Typed left = reference(expression.operands().get(0), item, steps, cards);
        if (!left.result().valid()) return left.result();
        Typed right = reference(expression.operands().get(1), item, steps, cards);
        if (!right.result().valid()) return right.result();
        EnumSet<ValueType> intersection = left.types().isEmpty()
                ? EnumSet.noneOf(ValueType.class) : EnumSet.copyOf(left.types());
        intersection.retainAll(right.types());
        if (intersection.isEmpty()) return Result.invalid("OPERAND_TYPE_MISMATCH");
        if (switch (expression.operator()) {
            case GT, GTE, LT, LTE -> !intersection.contains(ValueType.NUMBER);
            default -> false;
        }) return Result.invalid("ORDERING_REQUIRES_NUMBER");
        return Result.ok();
    }

    private Typed reference(ConditionExpression.Operand operand, SubIntent item,
                            Map<String, SubIntent> steps, Map<String, CapabilityCard> cards) {
        return switch (operand.source()) {
            case STEP_OUTPUT -> stepOutput(operand, item, steps, cards);
            case PARAMETER -> parameter(operand, item, cards);
            case LITERAL -> new Typed(typesOfLiteral(operand.literal()), Result.ok());
            case EXPRESSION -> new Typed(EnumSet.of(ValueType.BOOLEAN),
                    expression(operand.expression(), item, steps, cards));
        };
    }

    private Typed stepOutput(ConditionExpression.Operand operand, SubIntent item,
                             Map<String, SubIntent> steps, Map<String, CapabilityCard> cards) {
        if (!item.dependsOn().contains(operand.stepId())) return invalid("STEP_NOT_IN_DEPENDENCIES");
        SubIntent source = steps.get(operand.stepId());
        if (source == null) return invalid("STEP_NOT_FOUND");
        CapabilityCard card = cards.get(source.capabilityId());
        if (card == null || card.outputSchema().isEmpty()) return invalid("OUTPUT_SCHEMA_MISSING");
        EnumSet<ValueType> types = schemaTypes(card.outputSchema(), operand.pointer());
        return types.isEmpty() ? invalid("OUTPUT_POINTER_NOT_DECLARED") : new Typed(types, Result.ok());
    }

    private Typed parameter(ConditionExpression.Operand operand, SubIntent item,
                            Map<String, CapabilityCard> cards) {
        CapabilityCard card = cards.get(item.capabilityId());
        if (card == null || card.inputSchema().isEmpty()) return invalid("INPUT_SCHEMA_MISSING");
        EnumSet<ValueType> types = schemaTypes(card.inputSchema(), "/" + operand.parameter());
        return types.isEmpty() ? invalid("PARAMETER_NOT_DECLARED") : new Typed(types, Result.ok());
    }

    private static Result operandShape(ConditionExpression.Operand operand) {
        if (operand == null || operand.source() == null) return Result.invalid("OPERAND_SOURCE_MISSING");
        return switch (operand.source()) {
            case STEP_OUTPUT -> present(operand.stepId()) && validPointer(operand.pointer())
                    ? Result.ok() : Result.invalid("STEP_REFERENCE_INVALID");
            case PARAMETER -> present(operand.parameter())
                    ? Result.ok() : Result.invalid("PARAMETER_REFERENCE_INVALID");
            case LITERAL -> Result.ok();
            case EXPRESSION -> operand.expression() != null
                    ? Result.ok() : Result.invalid("NESTED_EXPRESSION_MISSING");
        };
    }

    private static EnumSet<ValueType> schemaTypes(Map<String, Object> schema, String pointer) {
        if (!validPointer(pointer)) return EnumSet.noneOf(ValueType.class);
        Object current = schema;
        for (String token : pointer.substring(1).split("/", -1)) {
            if (!(current instanceof Map<?, ?> map)) return EnumSet.noneOf(ValueType.class);
            Object properties = map.get("properties");
            if (!(properties instanceof Map<?, ?> propertyMap)) return EnumSet.noneOf(ValueType.class);
            String key = token.replace("~1", "/").replace("~0", "~");
            current = propertyMap.get(key);
            if (current == null) return EnumSet.noneOf(ValueType.class);
        }
        return typesOfSchema(current);
    }

    private static EnumSet<ValueType> typesOfSchema(Object raw) {
        if (!(raw instanceof Map<?, ?> schema)) return EnumSet.noneOf(ValueType.class);
        if ("decimal".equals(schema.get("x-value-type"))) return EnumSet.of(ValueType.NUMBER);
        EnumSet<ValueType> types = EnumSet.noneOf(ValueType.class);
        addType(types, schema.get("type"));
        Object anyOf = schema.get("anyOf");
        if (anyOf instanceof List<?> options) options.forEach(option -> types.addAll(typesOfSchema(option)));
        return types;
    }

    private static void addType(EnumSet<ValueType> types, Object raw) {
        if (raw instanceof List<?> values) values.forEach(value -> addType(types, value));
        if (!(raw instanceof String value)) return;
        switch (value) {
            case "number", "integer" -> types.add(ValueType.NUMBER);
            case "boolean" -> types.add(ValueType.BOOLEAN);
            case "string" -> types.add(ValueType.STRING);
            case "object" -> types.add(ValueType.OBJECT);
            case "array" -> types.add(ValueType.ARRAY);
            case "null" -> types.add(ValueType.NULL);
            default -> { }
        }
    }

    private static EnumSet<ValueType> typesOfLiteral(Object value) {
        if (value == null) return EnumSet.of(ValueType.NULL);
        if (value instanceof Number) return EnumSet.of(ValueType.NUMBER);
        if (value instanceof Boolean) return EnumSet.of(ValueType.BOOLEAN);
        if (value instanceof String text) {
            try { new java.math.BigDecimal(text); return EnumSet.of(ValueType.STRING, ValueType.NUMBER); }
            catch (NumberFormatException ignored) { return EnumSet.of(ValueType.STRING); }
        }
        if (value instanceof Map<?, ?>) return EnumSet.of(ValueType.OBJECT);
        if (value instanceof List<?>) return EnumSet.of(ValueType.ARRAY);
        return EnumSet.noneOf(ValueType.class);
    }

    private static Typed invalid(String reason) {
        return new Typed(EnumSet.noneOf(ValueType.class), Result.invalid(reason));
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static boolean validPointer(String value) { return value != null && value.startsWith("/"); }

    private enum ValueType { NUMBER, BOOLEAN, STRING, OBJECT, ARRAY, NULL }
    private record Typed(EnumSet<ValueType> types, Result result) { }
}
