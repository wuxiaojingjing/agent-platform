package com.huawei.finance.fastpath.arbitration;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.TaskShape;
import com.huawei.finance.contracts.model.SelectionBasis;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.PlanCondition;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.SlotNames;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.contracts.validation.ValidationOutcome;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.registry.asset.AssetBundle;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型仲裁（v0.7 §3.3 三级：Arbitration Skill 决策）。
 *
 * <p>输出要过三道关，任何一道不过都返回空、由调用方回退规则仲裁：
 *
 * <ol>
 *   <li>网关可用且返回了内容；
 *   <li>内容是合 Schema 的 JSON（枚举、类型、取值范围）；
 *   <li>选中的能力 ID 确实在候选列表里。
 * </ol>
 *
 * <p>第三关不能省。模型偶尔会「创造」一个看起来很合理的能力 ID，前两关都拦不住它，
 * 而这种 ID 一旦流到中控，就是拿着不存在的能力去路由。
 *
 * <p>这次调用同时承担**槽位回填**。原本模型只做选择题：读完整句话，只回答"是哪个能力"，
 * 而句子里的收款人和金额随手就扔了，留给正则去重抽一遍——正则抽不到"两千"，也抽不到
 * "我老板"。既然这次往返已经花掉，就让它把填空题一并做了，成本为零。
 * 回填结果受两条约束：只认被选中能力卡声明过的槽位（与"只能选候选内的能力"同一个道理），
 * 且与正则冲突时正则赢（金额、卡号这类要可复现，不能每次调用抽出不同结果）。
 */
public class ModelArbitrator {

    private static final Logger log = LoggerFactory.getLogger(ModelArbitrator.class);

    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties modelProps;
    private final AssetBundle bundle;
    private final ContractValidator validator;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ModelArbitrator(ModelGatewayClient gateway, ModelGatewayProperties modelProps,
                           AssetBundle bundle, ContractValidator validator,
                           MeterRegistry meterRegistry) {
        this(gateway, modelProps, bundle, validator, meterRegistry, Clock.systemDefaultZone());
    }

    /** 时钟可注入：日期基准表进了 prompt，测试里必须能把「今天」钉死。 */
    public ModelArbitrator(ModelGatewayClient gateway, ModelGatewayProperties modelProps,
                           AssetBundle bundle, ContractValidator validator,
                           MeterRegistry meterRegistry, Clock clock) {
        this.gateway = gateway;
        this.modelProps = modelProps;
        this.bundle = bundle;
        this.validator = validator;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public Optional<Result> arbitrate(ArbitrationInput input) {
        if (!gateway.available()) {
            return fallback("gateway-unavailable");
        }

        List<RecallResult.Candidate> candidates = arbitrationCandidates(input);
        if (candidates.isEmpty()) {
            // 没有候选就没有可选项，调模型只是白花一次往返
            return Optional.empty();
        }

        String system = bundle.arbitrationSkill().getSystem();
        Prompt prompt = buildWithinBudget(input, candidates, system);

        GatewayResult<String> result = gateway.chat(new ChatRequest(
                modelProps.getArbitration().getModel(),
                system,
                prompt.user(),
                modelProps.getArbitration().getMaxTokens(),
                modelProps.getArbitration().getTemperature(),
                true,
                bundle.arbitrationSkill().getVersion()));

        if (!result.available()) {
            return fallback("gateway-" + result.reason());
        }

        return parseAndValidate(result.value(), prompt.candidates(), input.filledSlots(),
                input.contextualQuery(), input.normalizedQuery());
    }

    /**
     * A fully grounded rule plan is stronger evidence than whole-sentence Top-N recall. Every
     * grounded step must be visible to TaskShapeModel, otherwise it cannot preserve a dependency
     * that the sentence splitter already proved (for example transfer after balance query).
     */
    private List<RecallResult.Candidate> arbitrationCandidates(ArbitrationInput input) {
        LinkedHashMap<String, RecallResult.Candidate> candidates = new LinkedHashMap<>();
        input.recall().result().candidates().forEach(candidate ->
                candidates.put(candidate.candidateId(), candidate));
        if (input.recall().intentPlan() != null) {
            input.recall().intentPlan().items().stream()
                    .flatMap(item -> item.resolution().candidateIds().stream())
                    .distinct().forEach(id -> {
                        if (candidates.containsKey(id)) return;
                        CapabilityCard card = bundle.capability(id);
                        if (card == null) return;
                        candidates.put(id, new RecallResult.Candidate(id,
                                Enums.CandidateType.valueOf(card.type().name()), card.domains(),
                                RecallResult.Scores.zero(), List.of("intent-plan:grounded"),
                                card.requiredSlots(), card.riskLevel(), Map.of(
                                        "capabilityVersion", card.version(),
                                        "assetVersion", bundle.assetVersion())));
                    });
        }
        return List.copyOf(candidates.values());
    }

    /**
     * 在候选数与字符两条预算内拼出 prompt。
     *
     * <p>超预算时逐个丢掉融合分最低的候选，而不是整体放弃仲裁：少一个末位候选，模型仍能
     * 在剩下的里选对；放弃仲裁则整条链路退回规则，代价大得多。丢到只剩一个还超，就照发不误
     * 并打点告警——此时 prompt 的膨胀来自模板或 system 段，丢候选已经解决不了，
     * 硬拦下来只会让用户白等一个降级回复。
     *
     * <p>候选必须已按融合分降序（{@code HybridRecall.fuse} 排过），否则「丢末位」丢的就不是
     * 最弱的那个。
     *
     * <p>返回的候选列表要回传给 {@code parseAndValidate} 做越界校验：模型只看得到裁剪后的
     * 候选，允许集就必须跟着裁剪，否则「不得选候选之外的能力」这道关会放行被裁掉的 ID。
     */
    private Prompt buildWithinBudget(ArbitrationInput input,
                                     List<RecallResult.Candidate> candidates, String system) {
        int candidateCap = modelProps.getArbitration().getMaxPromptCandidates();
        int charBudget = modelProps.getArbitration().getMaxPromptChars();

        List<RecallResult.Candidate> kept = candidates;
        if (kept.size() > candidateCap) {
            trimmed("CANDIDATE_CAP");
            kept = kept.subList(0, candidateCap);
        }

        int systemChars = system == null ? 0 : system.length();
        String user = renderUser(input, kept);
        while (systemChars + user.length() > charBudget && kept.size() > 1) {
            trimmed("CHAR_BUDGET");
            kept = kept.subList(0, kept.size() - 1);
            user = renderUser(input, kept);
        }

        int total = systemChars + user.length();
        meterRegistry.summary(AgentMetrics.ARBITRATION_PROMPT_CHARS).record(total);
        if (total > charBudget) {
            trimmed("OVERSIZED");
            log.warn("仲裁 prompt 超字符预算且已无候选可丢 字符={} 预算={} 候选={}",
                    total, charBudget, kept.size());
        }
        return new Prompt(user, kept);
    }

    private String renderUser(ArbitrationInput input, List<RecallResult.Candidate> candidates) {
        return bundle.arbitrationSkill().renderUser(Map.of(
                "query", input.normalizedQuery(),
                "channel", nullSafe(input.channel()),
                "page", nullSafe(input.page()),
                "extractedSlots", json(input.filledSlots()),
                "contextRewrite", json(input.contextualQuery()),
                "conversationHistory", json(input.intentContext() == null
                        ? List.of() : input.intentContext().conversationHistory()),
                "knowledgeExamples", json(bundle.arbitrationSkill().getEligibleKnowledgeExamples()),
                "dateTable", DateTable.render(clock),
                "candidates", renderCandidates(candidates, input.recall().fusedScores())));
    }

    private static String json(Object value) {
        try {
            return ContractJson.mapper().writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private void trimmed(String reason) {
        meterRegistry.counter(AgentMetrics.ARBITRATION_PROMPT_TRIMMED,
                AgentMetrics.TAG_REASON, reason).increment();
    }

    /** 裁剪后的 prompt 与它实际呈现给模型的候选集。 */
    record Prompt(String user, List<RecallResult.Candidate> candidates) {
    }

    private Optional<Result> parseAndValidate(String raw, List<RecallResult.Candidate> candidates,
                                              Map<String, Object> regexSlots,
                                              ContextualQuery contextualQuery,
                                              String originalQuery) {
        String json = stripCodeFence(raw);

        ValidationOutcome outcome = validator.validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT, json);
        if (!outcome.valid()) {
            log.warn("模型仲裁输出不合 Schema，回退规则仲裁 原因={} 原文={}", outcome.summary(), truncate(raw));
            return fallback("schema-invalid");
        }

        JsonNode node;
        try {
            node = ContractJson.mapper().readTree(json);
        } catch (Exception e) {
            return fallback("unparsable");
        }

        List<String> selected = new ArrayList<>();
        for (JsonNode id : node.path("candidateIds")) {
            selected.add(id.asText());
        }

        Set<String> allowed = new HashSet<>();
        candidates.forEach(c -> allowed.add(c.candidateId()));
        if (!allowed.containsAll(selected)) {
            log.warn("模型选中了候选之外的能力，回退规则仲裁 selected={} allowed={}", selected, allowed);
            return fallback("candidate-out-of-scope");
        }

        Decision decision;
        ReasonCode reasonCode;
        TaskShape taskShape;
        try {
            decision = Decision.valueOf(node.path("decision").asText());
            reasonCode = ReasonCode.valueOf(node.path("reasonCode").asText());
            taskShape = TaskShape.valueOf(node.path("taskShape").asText());
        } catch (IllegalArgumentException e) {
            return fallback("enum-out-of-contract");
        }
        if (decision == Decision.EXECUTE_CAPABILITY && selected.size() != 1) {
            // Schema 已约束 RouteDecision，但模型原始输出的 Schema 刻意更宽松，
            // 这条业务级约束在此补上
            return fallback("fast-execute-without-single-candidate");
        }
        if (!validDecisionCandidateTypes(decision, selected)) {
            log.warn("模型出口与候选资产类型不一致，回退规则仲裁 decision={} selected={}",
                    decision, selected);
            return fallback("decision-candidate-type-mismatch");
        }
        if (!validTaskShapeEvidence(node.path("subGoals"), decision, taskShape, allowed)) {
            log.warn("模型任务形态证据无效，回退规则仲裁 decision={} taskShape={} allowed={} subGoals={}",
                    decision, taskShape, allowed, node.path("subGoals"));
            return fallback("task-shape-evidence-invalid");
        }

        IntentPlan modelPlan;
        try {
            modelPlan = toIntentPlan(node.path("subGoals"), decision, originalQuery, candidates);
        } catch (IllegalArgumentException invalidPlan) {
            log.warn("模型任务形态无法落成 IntentPlan，回退规则仲裁 reason={}",
                    invalidPlan.getMessage());
            return fallback("task-shape-plan-invalid");
        }

        List<String> missingSlots = new ArrayList<>();
        for (JsonNode slot : node.path("missingSlots")) {
            missingSlots.add(slot.asText());
        }
        if (!missingSlots.isEmpty() && decision != Decision.CLARIFY) {
            return fallback("execution-with-missing-slots");
        }

        RouteDecision built = RouteDecision.builder()
                .decision(decision)
                .candidateIds(selected)
                .taskShape(taskShape)
                .intentPlan(modelPlan)
                .confidence(node.path("confidence").asDouble())
                .reasonCode(reasonCode)
                .missingSlots(missingSlots)
                .modelVersion(modelProps.getArbitration().getModel())
                .promptVersion(bundle.arbitrationSkill().getVersion())
                .configVersion(bundle.assetVersion())
                .shortCircuit(ShortCircuitLevel.L3_MODEL)
                .build();

        return Optional.of(new Result(built,
                acceptModelSlots(node.path("extractedSlots"), built, regexSlots,
                        contextualQuery)));
    }

    private boolean validDecisionCandidateTypes(Decision decision, List<String> selected) {
        if (selected.isEmpty()) {
            return decision == Decision.CLARIFY || decision == Decision.HANDOFF;
        }
        return switch (decision) {
            case DELEGATE_GOAL -> selected.stream().map(bundle::capability)
                    .allMatch(card -> card != null && card.type() == com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT);
            case START_WORKFLOW -> selected.stream().map(bundle::capability)
                    .allMatch(card -> card != null && card.type() == com.huawei.finance.contracts.model.Enums.CapabilityType.WORKFLOW);
            case EXECUTE_CAPABILITY -> selected.stream().map(bundle::capability)
                    .allMatch(card -> card != null
                            && card.type() != com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT
                            && card.type() != com.huawei.finance.contracts.model.Enums.CapabilityType.WORKFLOW);
            default -> true;
        };
    }

    private static boolean validTaskShapeEvidence(JsonNode subGoals, Decision decision, TaskShape shape,
                                                  Set<String> allowedCandidates) {
        Set<String> ids = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<JsonNode> goals = new ArrayList<>();
        if (subGoals != null && subGoals.isArray()) {
            subGoals.forEach(goal -> {
                goals.add(goal);
                ids.add(goal.path("id").asText());
            });
        }
        if (ids.size() != goals.size()) return false;

        boolean afterObservation = false;
        for (JsonNode goal : goals) {
            SelectionBasis basis;
            try {
                basis = SelectionBasis.valueOf(goal.path("selectionBasis").asText());
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            afterObservation |= basis == SelectionBasis.AFTER_OBSERVATION;
            for (JsonNode candidate : goal.path("candidateIds")) {
                if (!allowedCandidates.contains(candidate.asText())) return false;
            }
            for (JsonNode dependency : goal.path("dependsOn")) {
                String dependencyId = dependency.asText();
                if (!ids.contains(dependencyId) || !seen.contains(dependencyId)
                        || dependencyId.equals(goal.path("id").asText())) return false;
            }
            boolean conditional = basis == SelectionBasis.RESULT_RULE;
            boolean hasCondition = goal.hasNonNull("conditionText")
                    && !goal.path("conditionText").asText().isBlank();
            if (conditional != hasCondition) return false;
            seen.add(goal.path("id").asText());
        }
        if (decision == Decision.STATIC_PLAN) {
            if (afterObservation) return false;
            if (shape != TaskShape.FIXED_MULTI_STEP && shape != TaskShape.CONDITIONAL_PLAN) return false;
            if (goals.size() < 2) return false;
            if (goals.stream().anyMatch(goal -> !goal.path("candidateIds").isArray()
                    || goal.path("candidateIds").isEmpty())) return false;
        }
        if (decision == Decision.START_LOOP) {
            boolean openEnded = shape == TaskShape.OPEN_ENDED_DIAGNOSIS;
            return afterObservation || openEnded && goals.isEmpty();
        }
        return !afterObservation;
    }

    private IntentPlan toIntentPlan(JsonNode subGoals, Decision decision, String original,
                                    List<RecallResult.Candidate> candidates) {
        if (decision != Decision.STATIC_PLAN || subGoals == null || !subGoals.isArray()
                || subGoals.size() < 2) {
            return null;
        }
        Map<String, RecallResult.Candidate> recalled = new LinkedHashMap<>();
        candidates.forEach(candidate -> recalled.put(candidate.candidateId(), candidate));
        List<SubIntent> items = new ArrayList<>();
        for (int index = 0; index < subGoals.size(); index++) {
            JsonNode goal = subGoals.get(index);
            String capabilityId = goal.path("candidateIds").get(0).asText();
            CapabilityCard card = bundle.capability(capabilityId);
            if (card == null || !recalled.containsKey(capabilityId)) {
                throw new IllegalArgumentException("MODEL_PLAN_CAPABILITY_OUT_OF_SCOPE:" + capabilityId);
            }
            SelectionBasis basis = SelectionBasis.valueOf(goal.path("selectionBasis").asText());
            Enums.IntentRelation relation = index == 0 ? Enums.IntentRelation.PARALLEL
                    : basis == SelectionBasis.RESULT_RULE ? Enums.IntentRelation.CONDITIONAL
                    : goal.path("dependsOn").isEmpty() ? Enums.IntentRelation.PARALLEL
                    : Enums.IntentRelation.SEQUENTIAL;
            String condition = relation == Enums.IntentRelation.CONDITIONAL
                    ? goal.path("conditionText").asText() : null;
            PlanCondition planCondition = null;
            if (condition != null) {
                ConditionExpression expression = goal.hasNonNull("conditionExpr")
                        ? ContractJson.mapper().convertValue(goal.path("conditionExpr"), ConditionExpression.class)
                        : null;
                planCondition = expression == null
                        ? PlanCondition.deferred(condition)
                        : PlanCondition.structured(condition, expression);
            }
            String summary = card.name() == null || card.name().isBlank()
                    ? capabilityId : card.name();
            List<String> dependsOn = new ArrayList<>();
            goal.path("dependsOn").forEach(value -> dependsOn.add(value.asText()));
            String stepId = goal.path("id").asText();
            items.add(new SubIntent(index, summary, capabilityId, summary, relation, condition,
                    PlanResolution.locked(capabilityId, "model:task-shape:" + stepId),
                    stepId, dependsOn, planCondition));
        }
        return new IntentPlan(original == null ? "" : original, items, IntentPlan.Source.PLANNER);
    }


    /**
     * 收下模型回填的槽位。
     *
     * <p>两道过滤，都不能省：
     *
     * <ol>
     *   <li>槽位名必须在被选中能力卡的 {@code requiredSlots} 里。模型会顺手填一些它认为
     *       有用的字段（{@code accountType}、{@code remark}），这些名字领域方从未声明过，
     *       塞进 {@code UnifiedTask} 就是主 Agent 替领域方定义入参。
     *   <li>正则已抽到的槽位不被覆盖。金额与卡号要求同输入同输出，而模型每次调用都可能
     *       给出略有差异的结果；一笔转账的金额取决于第几次调用，是不可接受的。
     * </ol>
     */
    private Map<String, Object> acceptModelSlots(JsonNode extracted, RouteDecision decision,
                                                 Map<String, Object> regexSlots,
                                                 ContextualQuery contextualQuery) {
        if (extracted == null || !extracted.isObject() || decision == null) {
            return Map.of();
        }
        LinkedHashMap<String, CapabilityCard> cards = new LinkedHashMap<>();
        decision.candidateIds().stream().map(bundle::capability).filter(java.util.Objects::nonNull)
                .forEach(card -> cards.put(card.capabilityId(), card));
        if (decision.intentPlan() != null) {
            decision.intentPlan().items().stream().map(item -> bundle.capability(item.capabilityId()))
                    .filter(java.util.Objects::nonNull)
                    .forEach(card -> cards.put(card.capabilityId(), card));
        }
        Set<String> allowedSlots = new HashSet<>();
        cards.values().forEach(card -> allowedSlots.addAll(card.requiredSlots()));
        if (allowedSlots.isEmpty()) {
            return Map.of();
        }
        String metricCapability = cards.size() == 1 ? cards.keySet().iterator().next() : "multi-step";

        Map<String, Object> accepted = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = extracted.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String slot = field.getKey();
            String value = field.getValue().asText("");

            String outcome;
            if (SlotNames.AMOUNT.equals(slot) && hasDeferredAmount(contextualQuery)) {
                outcome = "REJECTED";
            } else if (!allowedSlots.contains(slot)) {
                outcome = "REJECTED";
            } else if (value.isBlank()) {
                continue;
            } else if (regexSlots != null && regexSlots.get(slot) != null
                    && !String.valueOf(regexSlots.get(slot)).isBlank()) {
                outcome = "OVERRIDDEN";
            } else {
                accepted.put(slot, value);
                outcome = "ACCEPTED";
            }

            meterRegistry.counter(AgentMetrics.SLOT_MODEL_FILL,
                    AgentMetrics.TAG_CAPABILITY, metricCapability,
                    AgentMetrics.TAG_SLOT, slot,
                    AgentMetrics.TAG_OUTCOME, outcome).increment();
        }
        return accepted;
    }

    private static boolean hasDeferredAmount(ContextualQuery contextualQuery) {
        if (contextualQuery == null) return false;
        Object basis = contextualQuery.slotUpdates().get(SlotNames.AMOUNT_BASIS);
        return basis != null && !String.valueOf(basis).isBlank()
                && contextualQuery.slotUpdates().get(SlotNames.AMOUNT) == null;
    }

    private Optional<Result> fallback(String reason) {
        meterRegistry.counter(AgentMetrics.DEGRADED,
                AgentMetrics.TAG_COMPONENT, "arbitration",
                AgentMetrics.TAG_REASON, reason).increment();
        return Optional.empty();
    }

    private String renderCandidates(List<RecallResult.Candidate> candidates,
                                    Map<String, Double> fusedScores) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (RecallResult.Candidate c : candidates) {
            CapabilityCard card = bundle.capability(c.candidateId());
            sb.append(index++).append(". ").append(c.candidateId())
                    .append(" | 资产类型 ").append(card == null ? c.candidateType() : card.type())
                    .append(" | 名称 ").append(card == null ? c.candidateId() : nullSafe(card.name()))
                    .append(" | 描述 ").append(card == null ? "" : truncate(card.description(), 240))
                    .append(" | 融合分 ").append(String.format("%.3f", fusedScores.getOrDefault(c.candidateId(), 0.0)))
                    .append(" | 领域 ").append(String.join(",", c.domains()))
                    .append(" | 风险 ").append(c.riskLevel())
                    .append(" | Loop权限 ").append(card == null ? "UNKNOWN" : card.effectiveLoopAccess())
                    .append(" | 必填槽位 ").append(c.requiredSlots().isEmpty() ? "无" : String.join(",", c.requiredSlots()))
                    .append(" | 证据 ").append(String.join(",", c.matchedEvidence()))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 去掉 Markdown 代码围栏。
     *
     * <p>已经要求 json_object 输出且提示词里明说不要代码块，但模型仍会偶尔加上。
     * 为这种情况触发一次规则回退，代价（丢失模型判断）远大于收益（保持严格）。
     */
    private static String stripCodeFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
        }
        return s.trim();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        return truncate(s, 200);
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }

    /**
     * 模型仲裁产出。
     *
     * <p>槽位不并进 {@code RouteDecision}：那是附录 B 冻结的契约，
     * 加字段要走契约变更。与 {@code HybridRecall.Output.fusedScores} 同一处理方式——
     * 进程内多带一个返回值，不动对外口径。
     *
     * @param decision   出口
     * @param modelSlots 模型回填且已通过声明集与正则优先两道过滤的槽位
     */
    public record Result(RouteDecision decision, Map<String, Object> modelSlots) {

        public Result {
            modelSlots = modelSlots == null ? Map.of() : Map.copyOf(modelSlots);
        }
    }
}
