package com.huawei.finance.runtime;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.context.TaskContextModels.PendingSwitch;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.PendingSwitchView;
import com.huawei.finance.response.RenderedResponse;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import java.util.List;
import java.util.Map;

public class SwitchResponseBridge {
    public AgentResponse review(RuntimeEngines engines, RequestContext context, PendingSwitch pending) {
        return review(engines, context, pending.switchId(), pending.version());
    }

    public AgentResponse review(RuntimeEngines engines, RequestContext context, PendingSwitchView pending) {
        return review(engines, context, pending.switchId(), pending.version());
    }

    private AgentResponse review(RuntimeEngines engines, RequestContext context, String switchId, long version) {
        RouteDecision decision = RouteDecision.builder().decision(Decision.CLARIFY)
                .taskShape(TaskShape.AMBIGUOUS_GOAL).confidence(1).reasonCode(ReasonCode.SWITCH_REQUIRED)
                .configVersion(engines.bundle().assetVersion()).build();
        ResponsePlan plan = ResponsePlan.builder().traceId(context.traceId())
                .sceneCode("TASK_SWITCH#REVIEW").responsePhase(Enums.ResponsePhase.SWITCH_REVIEW)
                .templateKey("tpl.switch.review").templateVersion("1.0.0").slots(Map.of())
                .cardComponents(List.of(ResponseComponent.REVIEW_SUMMARY))
                .actionCodes(List.of("SWITCH_ACCEPT", "SWITCH_REJECT"))
                .channel(context.channel()).fallbackTemplateKey("tpl.fallback.generic").build();
        RenderedResponse rendered = engines.renderer().render(plan);
        List<ResponseAction> actions = List.of(
                new ResponseAction("SWITCH_ACCEPT", "切换", switchId, version, ResponseAction.Style.PRIMARY),
                new ResponseAction("SWITCH_REJECT", "继续当前任务", switchId, version, ResponseAction.Style.SECONDARY));
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(), actions);
    }

    public AgentResponse suspendedLimit(RuntimeEngines engines, RequestContext context, PendingSwitchView pending) {
        RouteDecision decision = RouteDecision.builder().decision(Decision.CLARIFY)
                .taskShape(TaskShape.AMBIGUOUS_GOAL).confidence(1).reasonCode(ReasonCode.SWITCH_REQUIRED)
                .configVersion(engines.bundle().assetVersion()).build();
        ResponsePlan plan = ResponsePlan.builder().traceId(context.traceId())
                .sceneCode("TASK_SWITCH#SUSPENDED_LIMIT").responsePhase(Enums.ResponsePhase.SWITCH_REVIEW)
                .templateKey("tpl.switch.suspended-limit").templateVersion("1.0.0").slots(Map.of())
                .cardComponents(List.of(ResponseComponent.REVIEW_SUMMARY))
                .actionCodes(List.of("SWITCH_REJECT"))
                .channel(context.channel()).fallbackTemplateKey("tpl.fallback.generic").build();
        RenderedResponse rendered = engines.renderer().render(plan);
        ResponseAction reject = new ResponseAction("SWITCH_REJECT", "继续当前任务",
                pending.switchId(), pending.version(), ResponseAction.Style.PRIMARY);
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(), List.of(reject));
    }

    public AgentResponse rejected(RuntimeEngines engines, RequestContext context) {
        RouteDecision decision = RouteDecision.builder().decision(Decision.RESUME_TASK)
                .taskShape(TaskShape.SINGLE_ACTION).confidence(1).reasonCode(ReasonCode.CONTINUATION)
                .configVersion(engines.bundle().assetVersion()).build();
        ResponsePlan plan = ResponsePlan.builder().traceId(context.traceId()).sceneCode("TASK_SWITCH#REJECTED")
                .responsePhase(Enums.ResponsePhase.ACK).templateKey("tpl.switch.rejected").templateVersion("1.0.0")
                .slots(Map.of()).channel(context.channel()).fallbackTemplateKey("tpl.fallback.generic").build();
        RenderedResponse rendered = engines.renderer().render(plan);
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(), List.of());
    }
}
