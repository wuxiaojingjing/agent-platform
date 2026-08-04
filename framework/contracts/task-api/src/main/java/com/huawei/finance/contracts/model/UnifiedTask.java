package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 中控 → 领域 Agent（v0.7 附录 B {@code UnifiedTask}）。领域 Agent 不感知来自哪条路径。
 *
 * <p>{@code idempotencyKey} 只在护栏通过之后才允许非空（实施架构 §8.4）。这不是编码风格问题：
 * 幂等键就是可执行凭据，护栏未过就发凭据等于给了一张可以重放的执行许可。
 * 构造期用断言把这条规则钉死，避免依赖调用方自觉。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record UnifiedTask(
        String taskId,
        String traceId,
        Enums.TaskSource source,
        Enums.InvocationOrigin invocationOrigin,
        String goal,
        String capabilityId,
        Map<String, Object> parameters,
        RiskLevel riskLevel,
        Map<String, Object> confirmation,
        GuardrailCheck guardrailCheck,
        String idempotencyKey,
        List<String> contextRefs,
        Instant deadline,
        SubtaskContextEnvelope subtaskContext) {

    public UnifiedTask {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        confirmation = confirmation == null ? Map.of() : Map.copyOf(confirmation);
        contextRefs = contextRefs == null ? List.of() : List.copyOf(contextRefs);
        guardrailCheck = guardrailCheck == null ? GuardrailCheck.pending() : guardrailCheck;
        riskLevel = riskLevel == null ? RiskLevel.R0 : riskLevel;
        source = source == null ? Enums.TaskSource.FAST_PATH : source;
        invocationOrigin = invocationOrigin == null ? Enums.InvocationOrigin.LOCAL : invocationOrigin;

        if (idempotencyKey != null && !idempotencyKey.isBlank() && !guardrailCheck.isPassed()) {
            throw new IllegalArgumentException(
                    "幂等键必须在护栏通过之后才生成（实施架构 §8.4），当前护栏状态："
                            + guardrailCheck.status());
        }
    }

    public UnifiedTask(
            String taskId, String traceId, Enums.TaskSource source,
            Enums.InvocationOrigin invocationOrigin, String goal, String capabilityId,
            Map<String, Object> parameters, RiskLevel riskLevel,
            Map<String, Object> confirmation, GuardrailCheck guardrailCheck,
            String idempotencyKey, List<String> contextRefs, Instant deadline) {
        this(taskId, traceId, source, invocationOrigin, goal, capabilityId, parameters,
                riskLevel, confirmation, guardrailCheck, idempotencyKey, contextRefs,
                deadline, null);
    }

    public UnifiedTask(
            String taskId, String traceId, Enums.TaskSource source, String goal,
            String capabilityId, Map<String, Object> parameters, RiskLevel riskLevel,
            Map<String, Object> confirmation, GuardrailCheck guardrailCheck,
            String idempotencyKey, List<String> contextRefs, Instant deadline) {
        this(taskId, traceId, source, Enums.InvocationOrigin.LOCAL, goal, capabilityId,
                parameters, riskLevel, confirmation, guardrailCheck, idempotencyKey,
                contextRefs, deadline, null);
    }

    public boolean executable() {
        return guardrailCheck.isPassed() && idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
