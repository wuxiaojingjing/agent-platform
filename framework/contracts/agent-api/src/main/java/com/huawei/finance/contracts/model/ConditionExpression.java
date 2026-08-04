package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Objects;

/** A typed, business-neutral condition evaluated over plan inputs and prior step outputs. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConditionExpression(Operator operator, List<Operand> operands) {

    public enum Operator { EQ, NE, GT, GTE, LT, LTE, AND, OR, NOT, EXISTS }

    public enum Source { STEP_OUTPUT, PARAMETER, LITERAL, EXPRESSION }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operand(Source source, String stepId, String pointer, String parameter,
                          Object literal, ConditionExpression expression) {
        public Operand {
            Objects.requireNonNull(source, "condition operand source must not be null");
        }

        public static Operand stepOutput(String stepId, String pointer) {
            return new Operand(Source.STEP_OUTPUT, stepId, pointer, null, null, null);
        }

        public static Operand parameter(String name) {
            return new Operand(Source.PARAMETER, null, null, name, null, null);
        }

        public static Operand literal(Object value) {
            return new Operand(Source.LITERAL, null, null, null, value, null);
        }

        public static Operand expression(ConditionExpression value) {
            return new Operand(Source.EXPRESSION, null, null, null, null, value);
        }
    }

    public ConditionExpression {
        Objects.requireNonNull(operator, "condition operator must not be null");
        operands = operands == null ? List.of() : List.copyOf(operands);
    }
}
