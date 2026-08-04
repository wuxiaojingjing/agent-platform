package com.huawei.finance.runtime.loop;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.response.RenderedResponse;
import com.huawei.finance.response.ResponseModelContext;
import com.huawei.finance.contracts.model.TaskResultMetadata;
import com.huawei.finance.runtime.AgentResponse;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps Loop states to deterministic response assets; planner output is never shown directly. */
public class LoopResponseBridge {
    public AgentResponse respond(RuntimeEngines engines, RequestContext context,
                                 RouteDecision decision, Outcome outcome,
                                 List<Map<String, Object>> conversationHistory,
                                 String userQuery) {
        Enums.ResponsePhase phase = switch (outcome.state()) {
            case WAITING_USER -> Enums.ResponsePhase.CLARIFY;
            case WAITING_REVIEW -> Enums.ResponsePhase.REVIEW;
            case WAITING_CONFIRMATION -> Enums.ResponsePhase.CONFIRM;
            case COMPLETED, HANDED_OFF -> Enums.ResponsePhase.FINAL;
            default -> Enums.ResponsePhase.ERROR;
        };
        String template = outcome.state() == Status.HANDED_OFF ? "tpl.loop.handoff" : switch (phase) {
            case CLARIFY -> "tpl.loop.clarify";
            case REVIEW -> "tpl.loop.review";
            case CONFIRM -> "tpl.loop.confirm";
            case FINAL -> "tpl.loop.final";
            default -> "tpl.loop.error";
        };
        List<String> actionCodes = switch (phase) {
            case REVIEW -> List.of("REVIEW_ACCEPT", "CANCEL");
            case CONFIRM -> List.of("CONFIRM", "CANCEL");
            case CLARIFY -> List.of("ANSWER", "CANCEL");
            default -> List.of();
        };
        Presentation presentation = presentation(engines, context, outcome);
        Map<String,Object> slots = new LinkedHashMap<>();
        slots.put("summary", presentation.summary());
        slots.put("reason", outcome.reasonCode() == null ? "" : outcome.reasonCode());
        if (!presentation.resultCards().isEmpty()) {
            slots.put("resultCards", presentation.resultCards());
        }
        long comparedProducts = engines.bundle().productCatalog().getProducts().stream()
                .map(product -> product.queryCapabilityId()).distinct()
                .filter(presentation.visibleFacts()::containsKey).count();
        if (presentation.resultCards().size() == 2 && comparedProducts == 2) {
            slots.put("comparisonReady", Boolean.TRUE);
        }
        String sceneCode = "AGENT_LOOP#" + outcome.state();
        ResponsePlan plan = engines.planner().planRuntimeResponse(context, outcome.loopId(),
                context.agentId(), sceneCode, phase, template, slots, actionCodes);
        RenderedResponse rendered = engines.renderer().render(plan, new ResponseModelContext(
                conversationHistory, userQuery, presentation.visibleFacts()));
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, outcome.loopId(),
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(), actions(actionCodes, outcome));
    }

    private static List<ResponseAction> actions(List<String> codes, Outcome outcome) {
        List<ResponseAction> actions = new ArrayList<>();
        for (String code : codes) {
            if ("ANSWER".equals(code)) continue;
            String label = switch (code) { case "REVIEW_ACCEPT" -> "继续"; case "CONFIRM" -> "确认执行";
                case "CANCEL" -> "取消"; default -> code; };
            ResponseAction.Style style = "CANCEL".equals(code) ? ResponseAction.Style.DANGER : ResponseAction.Style.PRIMARY;
            actions.add(new ResponseAction(code, label, outcome.loopId(), outcome.stateVersion(), style));
        }
        return actions;
    }
    private static Presentation presentation(RuntimeEngines engines, RequestContext context,
                                             Outcome outcome) {
        Map<String, Object> visible = new LinkedHashMap<>();
        List<String> results = new ArrayList<>();
        List<Map<String, Object>> resultCards = new ArrayList<>();
        outcome.completedFacts().forEach((sourceId, value) -> {
            if (!(value instanceof Map<?, ?> raw)) return;
            Map<String, Object> facts = new LinkedHashMap<>();
            raw.forEach((key, fact) -> facts.put(String.valueOf(key), fact));
            String capabilityId = displayCapabilityId(engines, sourceId, facts);
            CapabilityCard card = engines.bundle().capability(capabilityId);
            String template = engines.bundle().templateKeyFor(capabilityId, "FINAL");
            if (template != null) {
                ResponsePlan factPlan = ResponsePlan.builder()
                        .traceId(context.traceId()).taskId(outcome.loopId())
                        .sceneCode("AGENT_LOOP_FACT#" + capabilityId)
                        .responsePhase(Enums.ResponsePhase.FINAL).templateKey(template)
                        .renderMode(Enums.RenderMode.TEMPLATE).slots(facts).channel(context.channel())
                        .fallbackTemplateKey("tpl.fallback.generic").build();
                RenderedResponse rendered = engines.renderer().render(factPlan);
                if (!rendered.fellBack() && rendered.text() != null && !rendered.text().isBlank()) {
                    results.add(rendered.text().trim());
                    visible.put(capabilityId, Map.copyOf(facts));
                    resultCards.add(resultCard(engines, capabilityId, facts));
                    return;
                }
            }
            if (card != null && card.name() != null && !card.name().isBlank()) {
                results.add(card.name() + "已检查");
            }
        });

        Observation last = outcome.lastObservation();
        if (last != null && last.displayHints().get("answer") instanceof String answer
                && !answer.isBlank()) {
            results.add(answer.trim());
            visible.put("knowledgeAnswer", answer.trim());
        } else if (last != null && "NAVIGATION".equals(last.sourceType())
                && last.facts().get("menuName") instanceof String menuName && !menuName.isBlank()) {
            String menuResult = "已找到“" + menuName + "”菜单";
            results.add(menuResult);
            visible.put("navigationResult", Map.of("menuName", menuName));
        }

        String summary = !results.isEmpty() ? String.join("；", results)
                : outcome.state() == Status.COMPLETED ? "相关项目已检查完成" : "任务处理中";
        return new Presentation(summary, Map.copyOf(visible), List.copyOf(resultCards));
    }

    private static Map<String, Object> resultCard(RuntimeEngines engines, String capabilityId,
                                                   Map<String, Object> facts) {
        String title = engines.bundle().productCatalog().getProducts().stream()
                .filter(product -> product.queryCapabilityId().equals(capabilityId))
                .map(product -> product.displayName() + " · " + switch (product.productType()) {
                    case "INSURANCE" -> "保险";
                    case "WEALTH" -> "理财";
                    default -> product.productType();
                }).findFirst()
                .orElseGet(() -> {
                    CapabilityCard card = engines.bundle().capability(capabilityId);
                    return card == null || card.name() == null ? "查询结果" : card.name();
                });
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("capabilityId", capabilityId);
        card.put("title", title);
        card.put("fields", Map.copyOf(facts));
        return Map.copyOf(card);
    }

    private static String displayCapabilityId(RuntimeEngines engines, String sourceId,
                                              Map<String, Object> facts) {
        Object target = facts.get(TaskResultMetadata.TARGET_CAPABILITY_ID);
        if (target instanceof String targetId && engines.bundle().capability(targetId) != null) {
            return targetId;
        }
        return sourceId;
    }

    private record Presentation(String summary, Map<String, Object> visibleFacts,
                                List<Map<String, Object>> resultCards) { }
}
