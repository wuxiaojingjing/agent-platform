package com.huawei.finance.orchestrator;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.model.Enums;
import java.util.List;
import java.util.Map;

/**
 * 中控入参。
 *
 * <p>能力卡由调用方传入而不是中控自己查：中控不依赖能力注册中心，
 * 依赖反过来就成了「事务边界要等检索组件就绪才能工作」。
 *
 * @param ctx        请求上下文
 * @param decision   快路径出口
 * @param capability 出口指向的能力卡，非执行类出口可为 null
 * @param slots      参数
 * @param goal       用户原始诉求
 * @param confirmed  本轮是否携带用户对高危动作的显式确认
 * @param expectedAnswers 澄清出口下给用户的候选答案。存进任务是为了下一轮事件分类能认出
 *                        「用户回的正是刚才给的选项」，从而走续轮短路而不是重新召回
 * @param lease           本轮上下文租约（FP-28）。不允许为 null——若允许，
 *                        「上下文不可信就不执行」这条会以「这个调用点没传租约所以不检查」
 *                        的形式被逐个绕过，而绕过是静默的
 */
public record OrchestrationRequest(
        RequestContext ctx,
        RouteDecision decision,
        CapabilityCard capability,
        Map<String, Object> slots,
        String goal,
        boolean confirmed,
        List<String> expectedAnswers,
        ContextLease lease,
        Enums.TaskSource source,
        Enums.InvocationOrigin invocationOrigin,
        String sourceInvocationId,
        IntentContext intentContext) {

    public OrchestrationRequest {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        expectedAnswers = expectedAnswers == null ? List.of() : List.copyOf(expectedAnswers);
        source = source == null ? Enums.TaskSource.FAST_PATH : source;
        invocationOrigin = invocationOrigin == null
                ? Enums.InvocationOrigin.LOCAL : invocationOrigin;

        if (lease == null) {
            throw new IllegalArgumentException(
                    "缺少上下文租约。没有租约就无法判断本轮能否执行有副作用的操作（FP-28）；"
                            + "确实没有上下文的调用方应显式传 ContextLease.degraded(...)");
        }
    }

    public OrchestrationRequest(
            RequestContext ctx, RouteDecision decision, CapabilityCard capability,
            Map<String, Object> slots, String goal, boolean confirmed,
            List<String> expectedAnswers, ContextLease lease, Enums.TaskSource source,
            Enums.InvocationOrigin invocationOrigin, String sourceInvocationId) {
        this(ctx, decision, capability, slots, goal, confirmed, expectedAnswers, lease,
                source, invocationOrigin, sourceInvocationId, null);
    }

    public OrchestrationRequest(
            RequestContext ctx, RouteDecision decision, CapabilityCard capability,
            Map<String, Object> slots, String goal, boolean confirmed,
            List<String> expectedAnswers, ContextLease lease, Enums.TaskSource source,
            String sourceInvocationId) {
        this(ctx, decision, capability, slots, goal, confirmed, expectedAnswers, lease,
                source, Enums.InvocationOrigin.LOCAL, sourceInvocationId, null);
    }

    public OrchestrationRequest(
            RequestContext ctx, RouteDecision decision, CapabilityCard capability,
            Map<String, Object> slots, String goal, boolean confirmed,
            List<String> expectedAnswers, ContextLease lease) {
        this(ctx, decision, capability, slots, goal, confirmed, expectedAnswers, lease,
                Enums.TaskSource.FAST_PATH, Enums.InvocationOrigin.LOCAL, null, null);
    }
}
