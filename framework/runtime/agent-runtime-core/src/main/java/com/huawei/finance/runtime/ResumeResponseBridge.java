package com.huawei.finance.runtime;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.registry.asset.ClarifyConfig;
import com.huawei.finance.registry.asset.TemplateDef;
import com.huawei.finance.response.RenderedResponse;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResumeResponseBridge {
    public AgentResponse restored(RuntimeEngines engines, RequestContext context,
                                  ContinuationContracts.Snapshot snapshot) {
        RuntimeType type = snapshot.runtimeType();
        String ref = snapshot.runtimeRef();
        Decision exit = type == RuntimeType.AGENT_LOOP ? Decision.RESUME_LOOP : Decision.RESUME_TASK;
        RouteDecision decision = RouteDecision.builder().decision(exit)
                .target(new RouteTarget(targetType(type), ref))
                .confidence(1).reasonCode(ReasonCode.RESUME_REQUIRED).configVersion(engines.bundle().assetVersion()).build();

        ResumeView view = view(engines, snapshot);
        TemplateDef template = engines.bundle().templates().get(view.templateKey());
        ResponsePlan plan = ResponsePlan.builder().traceId(context.traceId()).taskId(ref)
                .sceneCode(type.name() + "#RESUMED_" + view.phase().name())
                .responsePhase(view.phase()).templateKey(view.templateKey())
                .templateVersion(template == null ? "1.0.0" : template.version())
                .slots(view.slots()).cardComponents(view.components()).actionCodes(view.actionCodes())
                .channel(context.channel())
                .fallbackTemplateKey(template == null || !template.hasFallback()
                        ? "tpl.fallback.generic" : template.fallbackKey())
                .build();
        RenderedResponse rendered = engines.renderer().render(plan);
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, ref,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(),
                actions(view.actionCodes(), ref, snapshot.stateVersion()));
    }

    private static ResumeView view(RuntimeEngines engines, ContinuationContracts.Snapshot snapshot) {
        ContinuationContracts.PendingInteraction pending = snapshot.pendingInteraction();
        if (pending != null && pending.expectedSlot() != null) {
            ClarifyConfig.SlotClarify configured = engines.bundle().clarify().getSlots()
                    .get(pending.expectedSlot());
            String question = configured == null
                    ? "请继续补充办理所需信息。" : configured.getQuestion();
            List<String> options = pending.expectedAnswers().isEmpty() && configured != null
                    ? configured.getOptions() : pending.expectedAnswers();
            Map<String, Object> slots = new LinkedHashMap<>();
            slots.put("question", question);
            slots.put("options", options);
            return new ResumeView(Enums.ResponsePhase.CLARIFY, "tpl.clarify.slot", Map.copyOf(slots),
                    options.isEmpty() ? List.of() : List.of(ResponseComponent.CHOICE_LIST), List.of("ANSWER"));
        }

        if (pending != null && isReview(pending)) {
            Enums.ResponsePhase phase = "REVIEW".equals(pending.type())
                    || "WAITING_REVIEW".equals(pending.type())
                    ? Enums.ResponsePhase.REVIEW : Enums.ResponsePhase.CONFIRM;
            Map<String, Object> slots = visibleFacts(snapshot);
            String templateKey = runtimeTemplate(engines, snapshot, phase);
            List<String> actionCodes = snapshot.allowedEvents().stream()
                    .map(Enum::name)
                    .filter(code -> code.equals("REVIEW_ACCEPT") || code.equals("CONFIRM")
                            || code.equals("CANCEL"))
                    .toList();
            return new ResumeView(phase, templateKey, slots,
                    List.of(ResponseComponent.REVIEW_SUMMARY), actionCodes);
        }

        return new ResumeView(Enums.ResponsePhase.ACK, "tpl.resume.restored", Map.of(),
                List.of(), List.of());
    }

    private static boolean isReview(ContinuationContracts.PendingInteraction pending) {
        return switch (pending.type()) {
            case "REVIEW", "CONFIRM", "WAITING_REVIEW", "WAITING_CONFIRMATION" -> true;
            default -> false;
        };
    }

    private static String runtimeTemplate(RuntimeEngines engines,
                                          ContinuationContracts.Snapshot snapshot,
                                          Enums.ResponsePhase phase) {
        if (snapshot.runtimeType() == RuntimeType.AGENT_LOOP) {
            return phase == Enums.ResponsePhase.REVIEW ? "tpl.loop.review" : "tpl.loop.confirm";
        }
        String configured = engines.bundle().templateKeyFor(snapshot.displaySummary(), phase.name());
        if (configured != null) return configured;
        return phase == Enums.ResponsePhase.REVIEW ? "tpl.resume.review" : "tpl.resume.confirm";
    }

    private static Map<String, Object> visibleFacts(ContinuationContracts.Snapshot snapshot) {
        Map<String, Object> slots = new LinkedHashMap<>();
        snapshot.confirmedFacts().forEach((key, value) -> {
            if (!key.startsWith("__context.")) slots.put(key, value);
        });
        slots.putIfAbsent("summary", snapshot.goal() == null || snapshot.goal().isBlank()
                ? snapshot.displaySummary() : snapshot.goal());
        slots.putIfAbsent("reason", "任务已恢复，请核对后继续。 ");
        if (slots.containsKey("amount") || slots.containsKey("availableBalance")
                || slots.containsKey("billAmount")) {
            slots.putIfAbsent("currency", "¥");
        }
        return Map.copyOf(slots);
    }

    private static RouteTarget.Type targetType(RuntimeType type) {
        return switch (type) {
            case AGENT_LOOP -> RouteTarget.Type.LOOP;
            case WORKFLOW -> RouteTarget.Type.WORKFLOW;
            default -> RouteTarget.Type.TASK;
        };
    }

    private static List<ResponseAction> actions(List<String> codes, String ref, long version) {
        return codes.stream().map(code -> new ResponseAction(code, switch (code) {
            case "REVIEW_ACCEPT" -> "继续";
            case "CONFIRM" -> "确认执行";
            default -> "取消";
        }, ref, version, "CANCEL".equals(code)
                ? ResponseAction.Style.DANGER : ResponseAction.Style.PRIMARY)).toList();
    }

    private record ResumeView(Enums.ResponsePhase phase, String templateKey, Map<String, Object> slots,
                              List<String> components, List<String> actionCodes) {}
}
