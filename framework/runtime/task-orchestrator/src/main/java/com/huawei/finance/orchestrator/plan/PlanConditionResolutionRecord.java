package com.huawei.finance.orchestrator.plan;

import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.stability.Api;
import java.time.Instant;

/** Durable compilation result for one deferred Static Plan condition and fact snapshot. */
@Api
public record PlanConditionResolutionRecord(
        String planId,
        int stepIndex,
        String sourceText,
        ConditionExpression expression,
        Outcome outcome,
        String factDigest,
        String modelVersion,
        String promptVersion,
        Instant createdAt) {

    public enum Outcome { RESOLVED, INVALID, UNRESOLVED }
}
