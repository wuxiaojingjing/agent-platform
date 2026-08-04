package com.huawei.finance.response;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.TaskResult;
import java.util.Map;

/**
 * 回复编排的输入。
 *
 * @param ctx           请求上下文
 * @param decision      四出口
 * @param capability    出口指向的能力，非执行类出口可为 null
 * @param taskId        任务标识，未建任务为 null
 * @param result        领域 Agent 返回，未执行为 null
 * @param guardrail     护栏结论
 * @param slots         任务参数
 * @param templateKeyOverride 强规则指定的模板，优先于按出口推导
 * @param cancelled     本轮是否为用户取消
 * @param userQuery     用户**原话**。只供合规话题匹配（FP-36），不参与话术渲染。
 *                      刻意不用改写归一后的文本：改写表是为字面检索服务的，
 *                      而合规要看的正是用户原本怎么问（同 §5.4 的分工依据）
 * @param intentPlan    多意图拆解结果，非多意图或切不开时为 null。多意图澄清话术据此
 *                      逐条列出「您要办的几件事」，而不是笼统地请用户逐项办理
 */
public record ResponseContext(
        RequestContext ctx,
        RouteDecision decision,
        CapabilityCard capability,
        String taskId,
        TaskResult result,
        GuardrailCheck guardrail,
        Map<String, Object> slots,
        String templateKeyOverride,
        boolean cancelled,
        String userQuery,
        IntentPlan intentPlan) {

    public ResponseContext {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        guardrail = guardrail == null ? GuardrailCheck.pending() : guardrail;
        userQuery = userQuery == null ? "" : userQuery;
    }
}
