package com.huawei.finance.response;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.model.ResponseComponent;
import com.huawei.finance.contracts.model.StandardAnswer;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.TaskResultMetadata;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.ClarifyConfig;
import com.huawei.finance.registry.asset.TemplateDef;
import com.huawei.finance.registry.asset.ResponsePolicy;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 回复编排（v0.7 §3.7）。
 *
 * <p>把完整路由出口翻译成「说哪一句话、带哪些变量」。翻译规则集中在这里，
 * 是为了让「STATIC_PLAN 怎么说」这类映射有唯一出处——散到各处必然演化出多套说法。
 *
 * <p>固定多步骤由 Static Plan 承接；无法形成安全静态计划的任务才进入澄清、Loop 或人工出口。
 */
public class ResponsePlanner {

    private final AssetBundle bundle;
    private final ContractValidator validator;
    private final MeterRegistry meterRegistry;

    private final String defaultCurrency;

    public ResponsePlanner(AssetBundle bundle, ContractValidator validator, ResponseProperties props,
                          MeterRegistry meterRegistry) {
        this.bundle = bundle;
        this.validator = validator;
        this.meterRegistry = meterRegistry;
        this.defaultCurrency = props.getDefaultCurrency();
    }

    public ResponsePlan plan(ResponseContext context) {
        RouteDecision decision = context.decision();

        Draft draft = switch (decision.decision()) {
            case EXECUTE_CAPABILITY, START_WORKFLOW, DELEGATE_GOAL, RESUME_TASK -> fastExecute(context);
            case CLARIFY -> clarify(context);
            case STATIC_PLAN -> staticPlan(context);
            case START_LOOP, RESUME_LOOP -> slowPathDegraded(context);
            case REJECT, HANDOFF, CANCEL -> reject(context);
            case DIRECT_KNOWLEDGE -> direct(context);
            case NAVIGATION -> navigation(context);
        };

        // 强规则指定的模板优先。规则作者写下 templateKey 时，
        // 就是在说「这个场景的措辞我要自己定」，不该被出口推导覆盖。
        // 变量随之改用任务槽位——强规则的静态变量（未开放能力的名称、替代入口）就在里面
        boolean overridden = context.templateKeyOverride() != null && !context.templateKeyOverride().isBlank();
        String templateKey = overridden ? context.templateKeyOverride() : draft.templateKey();
        Map<String, Object> slots = overridden
                ? new LinkedHashMap<>(context.slots())
                : new LinkedHashMap<>(draft.slots());
        addResultCards(context, draft.phase(), slots);

        TemplateDef def = bundle.templates().get(templateKey);

        String sceneCode = sceneCode(context);
        String responseAgent = responseAgent(context);
        ResponsePolicy.Resolved responsePolicy = bundle.responsePolicy().resolve(
                context.ctx().spaceId(), responseAgent, sceneCode, draft.phase());
        List<String> riskNotices = riskNotices(context);

        ResponsePlan plan = ResponsePlan.builder()
                .traceId(context.ctx().traceId())
                .taskId(context.taskId())
                .sceneCode(sceneCode)
                .responsePhase(draft.phase())
                .templateKey(templateKey)
                .templateVersion(def == null ? "unknown" : def.version())
                .renderMode(responsePolicy.mode())
                .responseModel(responsePolicy.model())
                .approvedTemplateKeys(responsePolicy.templateSet())
                .responseTemperature(responsePolicy.temperature())
                .responseMaxTokens(responsePolicy.maxTokens())
                .responsePolicyVersion(responsePolicy.policyVersion())
                .responsePromptVersion(responsePolicy.promptVersion())
                .slots(slots)
                .cardComponents(components(templateKey, draft.phase(), slots, riskNotices,
                        context.intentPlan() != null))
                .actionCodes(draft.actionCodes())
                .riskNoticeCodes(riskNotices)
                .channel(context.ctx().channel())
                .fallbackTemplateKey(def == null ? "tpl.fallback.generic" : def.fallbackKey())
                .build();

        validator.validate(SchemaRef.RESPONSE_PLAN, plan).orThrow("ResponsePlan");
        return plan;
    }

    /**
     * Plans text realization for a Runtime that already owns its state-to-phase mapping.
     * The Runtime supplies only deterministic presentation facts; response policy resolution
     * remains centralized here so Loop/Workflow bridges cannot bypass configured render modes.
     */
    public ResponsePlan planRuntimeResponse(com.huawei.finance.common.context.RequestContext context,
                                            String taskId, String responseAgent, String sceneCode,
                                            Enums.ResponsePhase phase, String templateKey,
                                            Map<String, Object> slots, List<String> actionCodes) {
        TemplateDef def = bundle.templates().get(templateKey);
        ResponsePolicy.Resolved responsePolicy = bundle.responsePolicy().resolve(
                context.spaceId(), responseAgent, sceneCode, phase);
        ResponsePlan plan = ResponsePlan.builder()
                .traceId(context.traceId())
                .taskId(taskId)
                .sceneCode(sceneCode)
                .responsePhase(phase)
                .templateKey(templateKey)
                .templateVersion(def == null ? "unknown" : def.version())
                .renderMode(responsePolicy.mode())
                .responseModel(responsePolicy.model())
                .approvedTemplateKeys(responsePolicy.templateSet())
                .responseTemperature(responsePolicy.temperature())
                .responseMaxTokens(responsePolicy.maxTokens())
                .responsePolicyVersion(responsePolicy.policyVersion())
                .responsePromptVersion(responsePolicy.promptVersion())
                .slots(slots)
                .cardComponents(components(templateKey, phase, slots, List.of(), false))
                .actionCodes(actionCodes)
                .channel(context.channel())
                .fallbackTemplateKey(def == null ? "tpl.fallback.generic" : def.fallbackKey())
                .build();
        validator.validate(SchemaRef.RESPONSE_PLAN, plan).orThrow("ResponsePlan");
        return plan;
    }

    private static List<String> components(String templateKey, Enums.ResponsePhase phase,
                                           Map<String, Object> slots, List<String> riskNotices,
                                           boolean hasTaskPlan) {
        LinkedHashSet<String> components = new LinkedHashSet<>();
        String template = templateKey == null ? "" : templateKey;
        Map<String, Object> visibleSlots = slots == null ? Map.of() : slots;

        if (hasTaskPlan || template.startsWith("tpl.plan.")) {
            components.add(ResponseComponent.TASK_PROGRESS);
        }
        if (template.startsWith("tpl.loop.")) {
            components.add(ResponseComponent.LOOP_STATUS);
        }
        if (phase == Enums.ResponsePhase.FINAL && template.contains(".result")
                && !visibleSlots.isEmpty()) {
            components.add(ResponseComponent.RESULT_SUMMARY);
        }
        if (listHasItems(visibleSlots.get("resultCards"))) {
            components.add(ResponseComponent.RESULT_SUMMARY);
        }
        if (Boolean.TRUE.equals(visibleSlots.get("comparisonReady"))) {
            components.add(ResponseComponent.PRODUCT_COMPARISON);
        }
        if (riskNotices != null && !riskNotices.isEmpty()) {
            components.add(ResponseComponent.RISK_NOTICE);
        }
        if (phase == Enums.ResponsePhase.REVIEW || phase == Enums.ResponsePhase.CONFIRM
                || phase == Enums.ResponsePhase.SWITCH_REVIEW) {
            components.add(ResponseComponent.REVIEW_SUMMARY);
        }
        if (template.startsWith("tpl.nav.")) {
            components.add(ResponseComponent.NAVIGATION);
        }
        if (phase == Enums.ResponsePhase.CLARIFY && hasChoices(visibleSlots)) {
            components.add(ResponseComponent.CHOICE_LIST);
        }
        if (listHasItems(visibleSlots.get("menuItems"))) {
            components.add(ResponseComponent.MENU_LIST);
        }
        return List.copyOf(components);
    }

    private static boolean hasChoices(Map<String, Object> slots) {
        return listHasItems(slots.get("options")) || listHasItems(slots.get("taskSummaries"))
                || listHasItems(slots.get("tasks"));
    }

    private static boolean listHasItems(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private static String responseAgent(ResponseContext context) {
        CapabilityCard card = context.capability();
        if (card == null) return context.ctx().agentId();
        if (card.type() == Enums.CapabilityType.AGENT) return card.capabilityId();
        return card.parentCapabilityId() == null || card.parentCapabilityId().isBlank()
                ? context.ctx().agentId() : card.parentCapabilityId();
    }

    private Draft fastExecute(ResponseContext context) {
        CapabilityCard card = context.capability();
        TaskResult result = context.result();

        if (context.guardrail().status() == Enums.GuardrailStatus.FAILED) {
            return guardrailReject(context);
        }

        // 已执行：用领域 Agent 的返回渲染终态
        if (result != null) {
            if (!result.success()) {
                return failureDraft(result);
            }
            Draft aggregate = planAggregate(context);
            if (aggregate != null) {
                return aggregate;
            }
            Map<String, Object> slots = withCurrency(result.resultPayload());
            List<String> actions = isNavCapability(card) ? List.of("OPEN_MENU") : List.of();
            return new Draft(templateFor(responseCapability(card, result), "FINAL"),
                    Enums.ResponsePhase.FINAL, slots, actions);
        }

        // 未执行的 EXECUTE_CAPABILITY 只可能在等待 Review 或显式确认。
        Map<String, Object> slots = withCurrency(context.slots());
        if (card != null && card.confirmationPolicy() == ConfirmationPolicy.REVIEW_ONLY) {
            return new Draft(templateFor(card, "REVIEW"), Enums.ResponsePhase.REVIEW, slots,
                    List.of("REVIEW_ACCEPT", "CANCEL"));
        }
        return new Draft(templateFor(card, "CONFIRM"), Enums.ResponsePhase.CONFIRM, slots,
                List.of("CONFIRM", "CANCEL"));
    }

    /**
     * 执行失败时说哪一句。
     *
     * <p>关键分支是 {@code PARTIAL}——「结果未知」。转账超时后中断线程并没有撤回那笔钱，
     * 此时回一句「办理失败，请重试」是在诱导用户再转一次；幂等键只挡得住经由本系统的重放，
     * 挡不住用户换个说法重新发起。所以这一类不给 RETRY 动作，明说结果待确认、
     * 引导去查明细或转人工，由 FP-27 的补偿与对账收口。
     *
     * <p>其余失败沿用原有的通用兜底加重试引导：查询类重试是安全的，拦着反而降低可用性。
     */
    private static Draft failureDraft(TaskResult result) {
        if (result.failureClass() == Enums.FailureClass.PARTIAL) {
            return new Draft("tpl.fallback.uncertain", Enums.ResponsePhase.ERROR, Map.of(),
                    List.of("CHECK_DETAIL", "CONTACT_SERVICE"));
        }
        return new Draft("tpl.fallback.generic", Enums.ResponsePhase.ERROR, Map.of(),
                List.of("RETRY"));
    }

    private Draft clarify(ResponseContext context) {
        if (context.decision().reasonCode() == ReasonCode.LOW_MARGIN
                || context.decision().missingSlots().isEmpty()) {
            ClarifyConfig.IntentChoiceClarify clarify = bundle.clarify().getIntentChoice();
            List<String> options = context.decision().candidateIds().stream()
                    .map(bundle::capability)
                    .filter(java.util.Objects::nonNull)
                    .map(CapabilityCard::name)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .limit(clarify.getMaxOptions())
                    .toList();
            return new Draft(clarify.getTemplateKey(), Enums.ResponsePhase.CLARIFY,
                    Map.of("question", clarify.getQuestion(), "options", options),
                    List.of("PICK_ONE"));
        }
        List<String> missing = context.decision().missingSlots();
        String slotName = missing.isEmpty() ? null : missing.get(0);
        ClarifyConfig.SlotClarify clarify = slotName == null
                ? null : bundle.clarify().getSlots().get(slotName);

        if (clarify == null) {
            // 缺了没有配话术的槽位。宁可用兜底也不要临时拼一句「请提供 payee」，
            // 把内部字段名摆到用户面前是更糟的体验
            return new Draft("tpl.fallback.generic", Enums.ResponsePhase.CLARIFY, Map.of(), List.of());
        }

        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("question", clarify.getQuestion());
        slots.put("options", clarify.getOptions());
        return new Draft("tpl.clarify.slot", Enums.ResponsePhase.CLARIFY, slots, List.of("ANSWER"));
    }

    private Draft staticPlan(ResponseContext context) {
        if (context.result() != null
                && Boolean.TRUE.equals(context.result().resultPayload().get("conditionNotMet"))) {
            return new Draft("tpl.answer.condition-not-met", Enums.ResponsePhase.FINAL,
                    Map.of("condition", context.result().resultPayload().get("condition"),
                            "taskSummary", context.result().resultPayload().get("taskSummary")),
                    List.of());
        }
        Draft aggregate = planAggregate(context);
        if (aggregate != null) return aggregate;
        if (context.result() != null && !context.result().success()) {
            return failureDraft(context.result());
        }
        if (context.slots().containsKey("condition")
                && context.slots().containsKey("taskSummary")) {
            Map<String, Object> conditionSlots = new LinkedHashMap<>();
            conditionSlots.put("condition", context.slots().get("condition"));
            conditionSlots.put("taskSummary", context.slots().get("taskSummary"));
            conditionSlots.put("options", context.slots().getOrDefault("options", List.of()));
            if (context.slots().containsKey("capabilities")) {
                conditionSlots.put("capabilities", context.slots().get("capabilities"));
            }
            return new Draft("tpl.clarify.condition", Enums.ResponsePhase.CLARIFY,
                    conditionSlots,
                    List.of("ANSWER"));
        }
        if (context.slots().containsKey("question")) {
            Map<String,Object> clarify = new LinkedHashMap<>();
            clarify.put("question", context.slots().get("question"));
            clarify.put("options", context.slots().getOrDefault("options", List.of()));
            return new Draft("tpl.clarify.slot", Enums.ResponsePhase.CLARIFY,
                    clarify, List.of("ANSWER"));
        }
        CapabilityCard card = context.capability();
        if (card != null && card.confirmationPolicy() == ConfirmationPolicy.REVIEW_ONLY) {
            return new Draft(templateFor(card, "REVIEW"), Enums.ResponsePhase.REVIEW,
                    withCurrency(context.slots()), List.of("REVIEW_ACCEPT", "CANCEL"));
        }
        if (card != null && card.confirmationPolicy() == ConfirmationPolicy.EXPLICIT) {
            return new Draft(templateFor(card, "CONFIRM"), Enums.ResponsePhase.CONFIRM,
                    withCurrency(context.slots()), List.of("CONFIRM", "CANCEL"));
        }
        return slowPathDegraded(context);
    }

    /**
     * Static Plan 无法推进时的降级映射。
     *
     * <p>多意图能拆就引导用户逐项办理；跨域对比已联邦取到事实则出对照答复；
     * 其余拆不动的直接转人工。假装能办然后办错，比坦白说不了更伤信任。
     */
    private Draft slowPathDegraded(ResponseContext context) {
        Draft aggregate = planAggregate(context);
        if (aggregate != null) {
            return aggregate;
        }
        if (context.decision().reasonCode() == ReasonCode.MULTI_INTENT
                || context.decision().reasonCode() == ReasonCode.RESULT_RULE) {
            Map<String, Object> slots = new LinkedHashMap<>();
            slots.put("question", bundle.clarify().getMultiTask().getQuestion());
            // 拆不出来时仍给空列表：模板对空列表的处理是只念那句总的引导语，
            // 与拆解上线之前的行为一致。宁可少说一句，也不要把整句原话当成条目念回去
            slots.put("taskSummaries", context.intentPlan() == null
                    ? List.of() : context.intentPlan().summaries());
            return new Draft(bundle.clarify().getMultiTask().getTemplateKey(),
                    Enums.ResponsePhase.CLARIFY, slots, List.of("PICK_ONE"));
        }
        if (context.decision().reasonCode() == ReasonCode.CROSS_DOMAIN
                && isCompareReady(context.slots())) {
            return new Draft("tpl.product.compare", Enums.ResponsePhase.FINAL,
                    new LinkedHashMap<>(context.slots()), List.of());
        }
        return new Draft("tpl.fallback.handoff", Enums.ResponsePhase.ERROR, Map.of(),
                List.of("CONTACT_SERVICE"));
    }

    /**
     * 已实际执行的多意图计划不能再按「请选择一件」回复。
     *
     * <p>Static Plan 首轮保留 STATIC_PLAN 决策，续办轮以 EXECUTE_CAPABILITY 进入回复层，
     * 所以聚合识别必须同时供两个出口使用。领域措辞留给产品模板；这里仅把计划、
     * 已完成事实和剩余事项整理成稳定变量。
     */
    private Draft planAggregate(ResponseContext context) {
        TaskResult result = context.result();
        IntentPlan plan = context.intentPlan();
        if (result == null || !result.success() || plan == null) {
            return null;
        }
        Object raw = result.resultPayload().get("capabilities");
        if (!(raw instanceof Map<?, ?> values) || values.isEmpty()) {
            return null;
        }

        Map<String, Object> capabilities = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key instanceof String capabilityId && !capabilityId.isBlank()) {
                capabilities.put(capabilityId, value);
            }
        });
        if (capabilities.isEmpty()) {
            return null;
        }

        List<String> completed = plan.items().stream()
                .filter(item -> completed(item, capabilities))
                .map(SubIntent::summary)
                .toList();
        List<String> remaining = plan.items().stream()
                .filter(item -> !completed(item, capabilities))
                .map(SubIntent::summary)
                .toList();

        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("capabilities", Map.copyOf(capabilities));
        slots.put("completedSummaries", completed);
        slots.put("remainingSummaries", remaining);
        slots.put("completedCount", completed.size());
        slots.put("totalCount", plan.items().size());
        String template = remaining.isEmpty() ? "tpl.plan.result" : "tpl.plan.progress";
        return new Draft(template, Enums.ResponsePhase.FINAL, slots, List.of());
    }

    private static boolean completed(SubIntent item, Map<String, Object> capabilities) {
        return item.capabilityId() != null && capabilities.containsKey(item.capabilityId());
    }

    private void addResultCards(ResponseContext context, Enums.ResponsePhase phase,
                                Map<String, Object> slots) {
        Object rawCapabilities = slots.get("capabilities");
        if (rawCapabilities instanceof Map<?, ?> capabilities && !capabilities.isEmpty()) {
            List<Map<String, Object>> cards = capabilities.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String
                            && entry.getValue() instanceof Map<?, ?>)
                    .map(entry -> resultCard(String.valueOf(entry.getKey()),
                            (Map<?, ?>) entry.getValue()))
                    .toList();
            if (!cards.isEmpty()) slots.put("resultCards", cards);
            return;
        }
        if (phase != Enums.ResponsePhase.FINAL || context.result() == null
                || !context.result().success() || context.capability() == null) {
            return;
        }
        slots.put("resultCards", List.of(resultCard(
                context.capability().capabilityId(), context.result().resultPayload())));
    }

    private Map<String, Object> resultCard(String capabilityId, Map<?, ?> rawFields) {
        Map<String, Object> fields = new LinkedHashMap<>();
        rawFields.forEach((key, value) -> {
            if (key instanceof String name && !name.startsWith("__context.")) {
                fields.put(name, value);
            }
        });
        fields.putIfAbsent("currency", defaultCurrency);
        CapabilityCard capability = bundle.capability(capabilityId);
        String title = capability == null || capability.name() == null || capability.name().isBlank()
                ? "办理结果" : capability.name();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("capabilityId", capabilityId);
        card.put("title", title);
        card.put("fields", Map.copyOf(fields));
        return Map.copyOf(card);
    }

    /** 联邦拉取成功时 slots 会带上 compareReady=true。 */
    private static boolean isCompareReady(Map<String, Object> slots) {
        return slots != null && Boolean.TRUE.equals(slots.get("compareReady"));
    }

    private Draft reject(ResponseContext context) {
        if (context.cancelled()) {
            return new Draft("tpl.cancel.ack", Enums.ResponsePhase.FINAL, Map.of(), List.of());
        }

        ReasonCode reason = context.decision().reasonCode();
        if (reason == ReasonCode.STANDARD_ANSWER) {
            return standardAnswer(context);
        }
        if (reason == ReasonCode.CLARIFY_EXHAUSTED) {
            return new Draft(bundle.clarify().getExhausted().getTemplateKey(),
                    Enums.ResponsePhase.ERROR, Map.of(), List.of("CONTACT_SERVICE"));
        }
        if (context.guardrail().status() == Enums.GuardrailStatus.FAILED) {
            return guardrailReject(context);
        }
        return new Draft("tpl.fallback.handoff", Enums.ResponsePhase.ERROR, Map.of(),
                List.of("CONTACT_SERVICE"));
    }

    private Draft direct(ResponseContext context) {
        if (context.decision().reasonCode() == ReasonCode.STANDARD_ANSWER) {
            return standardAnswer(context);
        }
        if (context.capability() != null || context.result() != null) {
            return fastExecute(context);
        }
        return new Draft("tpl.fallback.generic", Enums.ResponsePhase.FINAL,
                context.slots(), List.of());
    }

    /**
     * 导航是入口已确定的菜单动作，不创建任务，也不进入能力 Review/Confirm。
     * 菜单展示字段从当前资产快照按 capabilityId 精确映射，避免把自然语言解析塞进回复层。
     */
    private Draft navigation(ResponseContext context) {
        CapabilityCard card = context.capability();
        var menu = bundle.menus().findByCapabilityId(card == null ? null : card.capabilityId())
                .orElse(null);
        if (menu == null) {
            return new Draft("tpl.fallback.generic", Enums.ResponsePhase.FINAL,
                    context.slots(), List.of());
        }
        Map<String, Object> slots = new LinkedHashMap<>(context.slots());
        slots.put("menuName", menu.getFinalName());
        slots.put("menuId", menu.getMenuId());
        slots.put("bksPath", menu.getBksPath());
        slots.put("action", "OPEN_MENU");
        // NAVIGATION 已经是完整出口，所有菜单共用同一条打开话术；菜单正文来自 slots。
        // 动态投影菜单不应要求知识维护者再为每张卡复制一条模板映射。
        return new Draft("tpl.nav.open", Enums.ResponsePhase.FINAL,
                slots, List.of("OPEN_MENU"));
    }

    /**
     * 标准问答直答（FP-1I）。
     *
     * <p>它落在 {@code DIRECT_KNOWLEDGE} 出口下，阶段是 FINAL：
     * 用户的问题已经答完了，这一轮是正常收尾。阶段决定前端怎么显示（错误态会带重试与转人工），
     * 判错了用户会看到一段正确答案配着一句「很抱歉」。
     *
     * <p>模板键与答案正文由快路径带来，走的是 {@code templateKeyOverride} 那条既有通路，
     * 所以这里给的模板键只是占位——真正生效的是资产里那条。动作码按有没有配动作入口给：
     * 没配就不给按钮，前端拿到空动作会渲染出一个点不动的东西。
     */
    private static Draft standardAnswer(ResponseContext context) {
        boolean hasAction = context.slots() != null
                && context.slots().get(StandardAnswer.SLOT_ACTION_CAPABILITY) != null;
        return new Draft(StandardAnswer.TEMPLATE_KEY, Enums.ResponsePhase.FINAL, Map.of(),
                hasAction ? List.of("OPEN_CAPABILITY") : List.of());
    }

    /** 护栏拒绝的说法出自资产，代码只负责取。措辞归业务部，改一个字不该发一次版。 */
    private Draft guardrailReject(ResponseContext context) {
        return new Draft("tpl.reject.guardrail", Enums.ResponsePhase.ERROR,
                Map.of("reasonText", bundle.clarify().guardrailReasonText(context.guardrail().codes())),
                List.of("CONTACT_SERVICE"));
    }

    private String templateFor(CapabilityCard card, String phase) {
        if (card == null) {
            return "tpl.fallback.generic";
        }
        String key = bundle.templateKeyFor(card.capabilityId(), phase);
        return key == null ? "tpl.fallback.generic" : key;
    }

    /**
     * AGENT/GOAL 的父任务只知道目标 Agent，真正的终态结构由目标侧识别出的叶子能力决定。
     * 只接受当前资产快照中存在的能力，避免远端用任意字符串影响模板选择。
     */
    private CapabilityCard responseCapability(CapabilityCard card, TaskResult result) {
        if (card == null || card.type() != Enums.CapabilityType.AGENT || result == null) {
            return card;
        }
        Object target = result.resultPayload().get(TaskResultMetadata.TARGET_CAPABILITY_ID);
        if (!(target instanceof String capabilityId) || capabilityId.isBlank()) {
            return card;
        }
        CapabilityCard resolved = bundle.capability(capabilityId);
        return resolved == null ? card : resolved;
    }

    private static boolean isNavCapability(CapabilityCard card) {
        return card != null && card.capabilityId() != null
                && card.capabilityId().startsWith("cap.nav.");
    }

    /**
     * 生成用户可见槽位并补币种。
     *
     * <p>领域 Agent 返回的是金额数字，币种属于展示约定而非业务数据。让每个 Agent 各自返回，
     * 迟早会出现一个返回 CNY、一个返回 ¥ 的局面。
     * 平台内部 {@code __context.*} 标记只服务执行协议，不能进入用户可见投影。
     */
    private Map<String, Object> withCurrency(Map<String, Object> slots) {
        Map<String, Object> merged = new LinkedHashMap<>();
        slots.forEach((key, value) -> {
            if (!key.startsWith("__context.")) merged.put(key, value);
        });
        merged.putIfAbsent("currency", defaultCurrency);
        return merged;
    }

    /**
     * 风险提示码，两条来源并列。
     *
     * <p>第一条来自能力卡的 {@code riskLevel}——要动钱的操作必须提示。
     * 第二条来自话题清单：「买什么基金好」不触发任何能力，却落在持牌业务里（§2.7.9）。
     * 后者不从属于前者，因此 {@code card == null} 或 R0 时同样要判。
     *
     * <p>用 LinkedHashSet 去重且保序：两条来源给出同一个提示码时说一遍就够，
     * 而顺序影响话术拼接的先后，不能交给 HashSet 决定。
     */
    private List<String> riskNotices(ResponseContext context) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();

        CapabilityCard card = context.capability();
        if (card != null && card.riskLevel().requiresExplicitConfirmation()) {
            codes.add("FUND_MOVEMENT");
        }

        for (String topicNotice : bundle.complianceTopics().match(context.userQuery())) {
            if (codes.add(topicNotice)) {
                meterRegistry.counter(AgentMetrics.COMPLIANCE_TOPIC,
                        AgentMetrics.TAG_REASON, topicNotice).increment();
            }
        }

        return List.copyOf(codes);
    }

    private static String sceneCode(ResponseContext context) {
        String capability = context.capability() == null ? "unknown" : context.capability().capabilityId();
        return capability + "#" + context.decision().decision();
    }

    private record Draft(String templateKey, Enums.ResponsePhase phase, Map<String, Object> slots,
                         List<String> actionCodes) {
    }
}
