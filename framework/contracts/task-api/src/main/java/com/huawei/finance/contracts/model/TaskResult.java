package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** 执行层 → 中控/回复编排（v0.7 附录 B {@code TaskResult}），与 {@code UnifiedTask} 成对。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record TaskResult(
        String taskId,
        Enums.TaskStatus status,
        Enums.FailureClass failureClass,
        Map<String, Object> resultPayload,
        String idempotencyKey,
        GuardrailCheck guardrailCheck) {

    public TaskResult {
        resultPayload = resultPayload == null ? Map.of() : Map.copyOf(resultPayload);
        failureClass = failureClass == null ? Enums.FailureClass.NONE : failureClass;
        guardrailCheck = guardrailCheck == null ? GuardrailCheck.passed() : guardrailCheck;
    }

    public boolean success() {
        return status == Enums.TaskStatus.SUCCESS;
    }
}
