package com.huawei.finance.orchestrator.plan;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.Map;

/** Persisted terminal outcome of one Slow Path step. */
@Api
public record PlanStepRecord(
        String planId,
        int stepIndex,
        String capabilityId,
        String taskId,
        Enums.TaskStatus status,
        Enums.FailureClass failureClass,
        Map<String, Object> facts,
        String reasonCode,
        Instant completedAt) {

    public PlanStepRecord {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
