package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;

/** User condition plus its optional executable, business-neutral representation. */
@Api
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanCondition(String originalText, ConditionExpression expression, ResolutionState state) {

    public enum ResolutionState { STRUCTURED, DEFERRED }

    public PlanCondition {
        if (originalText == null || originalText.isBlank()) {
            throw new IllegalArgumentException("plan condition must preserve the user's original text");
        }
        state = state == null
                ? (expression == null ? ResolutionState.DEFERRED : ResolutionState.STRUCTURED)
                : state;
        if (state == ResolutionState.STRUCTURED && expression == null) {
            throw new IllegalArgumentException("STRUCTURED condition requires an expression");
        }
        if (state == ResolutionState.DEFERRED && expression != null) {
            state = ResolutionState.STRUCTURED;
        }
    }

    public static PlanCondition deferred(String originalText) {
        return new PlanCondition(originalText, null, ResolutionState.DEFERRED);
    }

    public static PlanCondition structured(String originalText, ConditionExpression expression) {
        return new PlanCondition(originalText, expression, ResolutionState.STRUCTURED);
    }
}
