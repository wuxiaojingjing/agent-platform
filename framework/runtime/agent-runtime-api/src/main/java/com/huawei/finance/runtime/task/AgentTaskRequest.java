package com.huawei.finance.runtime.task;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 业务计划通过本 Agent 中控执行一个子任务时使用的稳定请求。 */
@Api
public record AgentTaskRequest(
        RequestContext context,
        RouteDecision decision,
        CapabilityCard capability,
        Map<String, Object> parameters,
        String goal,
        boolean confirmed,
        List<String> expectedAnswers,
        ContextLease lease,
        Enums.TaskSource source,
        Enums.InvocationOrigin invocationOrigin,
        String sourceInvocationId) {

    public AgentTaskRequest {
        context = Objects.requireNonNull(context, "context");
        decision = Objects.requireNonNull(decision, "decision");
        capability = Objects.requireNonNull(capability, "capability");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        expectedAnswers = expectedAnswers == null ? List.of() : List.copyOf(expectedAnswers);
        lease = Objects.requireNonNull(lease, "lease");
        source = source == null ? Enums.TaskSource.FAST_PATH : source;
        invocationOrigin = invocationOrigin == null
                ? Enums.InvocationOrigin.LOCAL : invocationOrigin;
    }

    public AgentTaskRequest(
            RequestContext context, RouteDecision decision, CapabilityCard capability,
            Map<String, Object> parameters, String goal, boolean confirmed,
            List<String> expectedAnswers, ContextLease lease, Enums.TaskSource source,
            String sourceInvocationId) {
        this(context, decision, capability, parameters, goal, confirmed, expectedAnswers, lease,
                source, Enums.InvocationOrigin.LOCAL, sourceInvocationId);
    }

    public AgentTaskRequest(
            RequestContext context, RouteDecision decision, CapabilityCard capability,
            Map<String, Object> parameters, String goal, boolean confirmed,
            List<String> expectedAnswers, ContextLease lease) {
        this(context, decision, capability, parameters, goal, confirmed, expectedAnswers, lease,
                Enums.TaskSource.FAST_PATH, Enums.InvocationOrigin.LOCAL, null);
    }
}
