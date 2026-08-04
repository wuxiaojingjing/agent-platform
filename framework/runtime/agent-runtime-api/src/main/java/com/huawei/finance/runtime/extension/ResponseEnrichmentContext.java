package com.huawei.finance.runtime.extension;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.stability.Api;
import java.util.Map;
import java.util.Objects;

/**
 * 回复增强阶段的只读事实快照。
 *
 * <p>扩展只返回展示槽位，不能把任务状态、护栏结论或执行结果写回 Runtime。
 */
@Api
public record ResponseEnrichmentContext(
        RequestContext context,
        RouteDecision decision,
        IntentPlan intentPlan,
        String taskId,
        TaskResult taskResult,
        GuardrailCheck guardrail,
        Map<String, Object> renderSlots) {

    public ResponseEnrichmentContext {
        context = Objects.requireNonNull(context, "context");
        decision = Objects.requireNonNull(decision, "decision");
        renderSlots = renderSlots == null ? Map.of() : Map.copyOf(renderSlots);
    }

    public ResponseEnrichmentContext withRenderSlots(Map<String, Object> slots) {
        return new ResponseEnrichmentContext(
                context, decision, intentPlan, taskId, taskResult, guardrail, slots);
    }
}
