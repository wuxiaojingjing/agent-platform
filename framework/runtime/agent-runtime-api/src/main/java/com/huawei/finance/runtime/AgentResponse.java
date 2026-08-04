package com.huawei.finance.runtime;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.model.ResponseAction;
import com.huawei.finance.stability.Api;
import java.util.List;

/**
 * {@link AgentRuntime} 一轮处理结果。渠道适配层可再映射为产品 DTO。
 */
@Api
public record AgentResponse(
        String traceId,
        String text,
        RouteDecision decision,
        ResponsePlan plan,
        String taskId,
        String usedTemplate,
        boolean fellBack,
        List<String> degradedChannels,
        List<ResponseAction> actions) {

    public AgentResponse {
        degradedChannels = degradedChannels == null ? List.of() : List.copyOf(degradedChannels);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public AgentResponse(String traceId, String text, RouteDecision decision, ResponsePlan plan,
                         String taskId, String usedTemplate, boolean fellBack,
                         List<String> degradedChannels) {
        this(traceId, text, decision, plan, taskId, usedTemplate, fellBack, degradedChannels, List.of());
    }
}
