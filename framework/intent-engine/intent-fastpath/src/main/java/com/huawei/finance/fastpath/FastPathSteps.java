package com.huawei.finance.fastpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.common.event.ActiveTaskView;
import com.huawei.finance.common.event.EventClassification;
import com.huawei.finance.common.event.EventClassifier;
import com.huawei.finance.common.event.InputEvent;
import com.huawei.finance.common.event.IntentSignalProbe;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.SlotNames;
import com.huawei.finance.contracts.model.StandardAnswer;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.fastpath.arbitration.ArbitrationInput;
import com.huawei.finance.fastpath.arbitration.FailSafeGuard;
import com.huawei.finance.fastpath.arbitration.ModelArbitrator;
import com.huawei.finance.fastpath.arbitration.RuleArbitrator;
import com.huawei.finance.fastpath.arbitration.SlotGate;
import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.intent.extension.CandidateSet;
import com.huawei.finance.intent.extension.IntentInput;
import com.huawei.finance.fastpath.cache.DecisionCacheKey;
import com.huawei.finance.fastpath.recall.HybridRecall;
import com.huawei.finance.fastpath.rewrite.QueryRewriter;
import com.huawei.finance.fastpath.rewrite.RewriteResult;
import com.huawei.finance.fastpath.rewrite.SlotExtractor;
import com.huawei.finance.fastpath.rule.StrongRuleEngine;
import com.huawei.finance.fastpath.policy.ProductComparisonPolicyGate;
import com.huawei.finance.fastpath.recall.ProductComparisonGrounder;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.obs.trace.ScoredCandidate;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.StandardQaBank;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.StrongRule;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快路径的各个步骤，与「按什么顺序走」分离。
 *
 * <p>顺序与分支由执行方式决定（{@link FastPathGraph} 交给 OpenJiuwen 图引擎，
 * {@link SequentialFastPath} 是留作比对的参考实现），业务动作全在这里。
 * 两条路径共用同一份步骤代码，是「换执行方式不改行为」这句话唯一可靠的保证方式。
 *
 * <p>每个方法只做一件事并把产出写回 {@link FastPathState}，判断「下一步去哪」的
 * 谓词单独提供（{@link #continuationApplies}、{@link #cacheLookupHits} 等），
 * 好让图引擎在**路由时**求值——跳过的步骤在 trace 里就不该有节点记录。
 */
class FastPathSteps {

    private final AssetBundle bundle;
    private final EventClassifier eventClassifier;
    private final IntentSignalProbe intentProbe;
    private final QueryRewriter rewriter;
    private final SlotExtractor slotExtractor;
    private final HybridRecall recall;
    private final StrongRuleEngine strongRules;
    private final DecisionCache cache;
    private final RuleArbitrator ruleArbitrator;
    private final ModelArbitrator modelArbitrator;
    private final FailSafeGuard failSafeGuard;
    private final SlotGate slotGate;
    private final ModelGatewayProperties modelProps;
    private final MeterRegistry meterRegistry;
    private final DecisionTrace decisionTrace;
    private final CandidatePostProcessorChain candidatePostProcessors;
    private final ProductComparisonPolicyGate productComparisonPolicyGate;
    private final ProductComparisonGrounder productComparisonGrounder;

    FastPathSteps(AssetBundle bundle, EventClassifier eventClassifier, IntentSignalProbe intentProbe,
                  QueryRewriter rewriter, SlotExtractor slotExtractor, HybridRecall recall,
                  StrongRuleEngine strongRules, DecisionCache cache, RuleArbitrator ruleArbitrator,
                  ModelArbitrator modelArbitrator, FailSafeGuard failSafeGuard, SlotGate slotGate,
                  ModelGatewayProperties modelProps, MeterRegistry meterRegistry,
                  DecisionTrace decisionTrace,
                  List<CandidatePostProcessor> candidatePostProcessors) {
        this.bundle = bundle;
        this.eventClassifier = eventClassifier;
        this.intentProbe = intentProbe;
        this.rewriter = rewriter;
        this.slotExtractor = slotExtractor;
        this.recall = recall;
        this.strongRules = strongRules;
        this.cache = cache;
        this.ruleArbitrator = ruleArbitrator;
        this.modelArbitrator = modelArbitrator;
        this.failSafeGuard = failSafeGuard;
        this.slotGate = slotGate;
        this.modelProps = modelProps;
        this.meterRegistry = meterRegistry;
        this.decisionTrace = decisionTrace;
        this.candidatePostProcessors = new CandidatePostProcessorChain(candidatePostProcessors);
        this.productComparisonPolicyGate = new ProductComparisonPolicyGate(bundle);
        this.productComparisonGrounder = new ProductComparisonGrounder(bundle);
    }

    // ==================== 步骤 ====================

    /** 改写归一与本轮槽位抽取。 */
    void rewrite(FastPathState state) {
        long start = System.nanoTime();
        String rawQuery = state.request().rawQuery();
        ContextualQuery contextual = state.request().contextualQuery();
        RewriteResult rewrite = contextual == null
                ? rewriter.rewrite(rawQuery)
                : rewriter.rewriteContextual(rawQuery, routingQuery(rawQuery, contextual));
        state.rewrite(rewrite);
        RewriteResult slotSource = contextual == null ? rewrite : rewriter.rewrite(rawQuery);
        Map<String, Object> turnSlots = new LinkedHashMap<>(
                slotExtractor.extract(slotSource.normalized(), slotSource.analysis()));
        if (contextual != null) {
            // ContextualQuery has already passed ContextRewritePolicyGate. Merge its typed semantic
            // slots before missing-slot checks and arbitration; merging them later produces a false
            // CLARIFY even though the model supplied the required calculation basis.
            turnSlots.putAll(contextual.slotUpdates());
        }
        state.turnSlots(Map.copyOf(turnSlots));
        long nanos = System.nanoTime() - start;
        state.recordPhase(FastPathEngine.PHASE_REWRITE, nanos);
        decisionTrace.recordPhase(FastPathEngine.PHASE_REWRITE, nanos);
        recordStep(state.request().ctx(), "intent-rewrite", "rewrite", "MAIN",
                withContext(Map.of("rawQuery", rawQuery == null ? "" : rawQuery), state),
                Map.of(
                        "original", nullSafe(rewrite.original()),
                        "normalized", nullSafe(rewrite.normalized()),
                        "searchText", nullSafe(rewrite.searchText()),
                        "semanticText", nullSafe(rewrite.semanticText()),
                        "contextStateVersion", contextual == null ? -1 : contextual.stateVersion(),
                        "usedContextRefs", contextual == null ? List.of() : contextual.usedContextRefs(),
                        "terms", rewrite.terms() == null ? List.of() : rewrite.terms(),
                        "slots", state.turnSlots() == null ? Map.of() : state.turnSlots()),
                "OK", nanos);
    }

    /**
     * A deferred value such as a balance ratio is context for the user's action, not a replacement
     * action. The model may make the dependency explicit by prefixing the standalone query with an
     * authoritative refresh step. Recall must still rank the action in the user's original wording;
     * the typed basis remains available to slot checks and execution policy.
     */
    private static String routingQuery(String rawQuery, ContextualQuery contextual) {
        Object amountBasis = contextual.slotUpdates().get(SlotNames.AMOUNT_BASIS);
        boolean deferredAmount = amountBasis != null
                && !String.valueOf(amountBasis).isBlank()
                && contextual.slotUpdates().get(SlotNames.AMOUNT) == null;
        return deferredAmount ? rawQuery : contextual.standaloneQuery();
    }

    /** 多轮事件分类。 */
    void classifyEvent(FastPathState state) {
        ContextualQuery contextual = state.request().contextualQuery();
        if (contextual != null && contextual.consumedContext()) {
            state.event(new EventClassification(
                    InputEvent.valueOf(contextual.eventType().name()), contextual.confidence(),
                    contextual.promptVersion(), contextual.reasonCode()));
            return;
        }
        state.event(eventClassifier.classify(
                state.rewrite().normalized(), state.request().activeTask(), intentProbe));
    }

    /**
     * 续轮短路（v0.7 §3.3 活跃慢任务）。
     *
     * <p>补充与纠正不重跑召回——用户回答「信用卡」时去做意图识别，只会召回到「信用卡账单」
     * 这类无关能力，把好好的续轮打断成新任务。但槽位校验一定要重跑：补了一个不等于补齐了。
     */
    void continuation(FastPathState state) {
        FastPathRequest request = state.request();
        RewriteResult rewrite = state.rewrite();
        EventClassification event = state.event();
        ActiveTaskView task = request.activeTask();
        Map<String, Object> slots = merge(task.filledSlots(), state.turnSlots());

        // 待澄清槽位没被正则抽到，但用户回的正是给出的选项之一：直接采纳原文
        if (task.pendingSlot() != null && !slots.containsKey(task.pendingSlot())) {
            for (String expected : task.expectedAnswers()) {
                if (!expected.isBlank() && rewrite.normalized().contains(expected)) {
                    slots.put(task.pendingSlot(), expected);
                    break;
                }
            }
        }

        RouteDecision.Builder builder = RouteDecision.builder()
                .confidence(event.confidence())
                .reasonCode(ReasonCode.CONTINUATION)
                .configVersion(bundle.assetVersion())
                .shortCircuit(ShortCircuitLevel.CONTINUATION)
                .evidenceRefs(List.of("event:" + event.event() + ":" + event.matchedRule()));

        if (task.capabilityId() != null) {
            builder.candidateIds(List.of(task.capabilityId()));
        }

        if (event.event() == InputEvent.CANCEL) {
            // CANCEL 是「不执行且终结」的独立路由。取消不是拒绝，
            // 但契约不为它单开一个出口——出口枚举扩一个，下游所有分支都要跟着改
            builder.decision(Decision.CANCEL);
            state.result(FastPathResult.shortCircuit(builder.build(), rewrite, slots, event, null));
            return;
        }

        CapabilityCard card = bundle.capability(task.capabilityId());
        List<String> missing = slotGate.missingSlots(card, slots);
        if (!missing.isEmpty()) {
            boolean exhausted = task.clarifyRounds() >= bundle.fusion().getClarify().getMaxRounds();
            builder.decision(exhausted ? Decision.HANDOFF : Decision.CLARIFY)
                    .reasonCode(exhausted ? ReasonCode.CLARIFY_EXHAUSTED : ReasonCode.MISSING_SLOT)
                    .missingSlots(missing);
            state.result(FastPathResult.shortCircuit(builder.build(), rewrite, slots, event, null));
            return;
        }

        // 已在 CONFIRM_PENDING 上拿到确认，或槽位补齐可以继续跑，都归到 EXECUTE_CAPABILITY：
        // 中控按任务当前状态决定是执行还是恢复
        builder.decision(Decision.EXECUTE_CAPABILITY);
        if (card != null && card.riskLevel().requiresExplicitConfirmation()
                && event.event() != InputEvent.CONFIRMATION) {
            builder.reasonCode(ReasonCode.CONFIRMATION_REQUIRED);
        }
        state.result(FastPathResult.shortCircuit(builder.build(), rewrite, slots, event, null));
    }

    /** 合并会话已确认槽位与本轮槽位，并算出缓存键。 */
    void mergeSlotsAndKey(FastPathState state) {
        FastPathRequest request = state.request();
        state.slots(merge(request.activeTask() == null ? Map.of() : request.activeTask().filledSlots(),
                state.turnSlots()));
        state.cacheKey(cacheKey(request, state.rewrite()));
    }

    /**
     * 一级：出口缓存。命中即把结果落进状态。
     *
     * <p>缓存里只有出口，没有拆解——{@link RouteDecision} 契约里本就没这个字段。
     * 多意图出口若照直返回，用户会收到一句列不出选项的「先办哪一件」，计划也开不起来，
     * 而缓存 TTL 是十分钟，同一句话在这十分钟里次次如此。所以命中时就地补切一次：
     * 纯规则、不进网关，代价只是几十微秒的字符串处理。
     */
    void cacheLookup(FastPathState state) {
        Optional<RouteDecision> cached = cache.get(state.cacheKey());
        cached.ifPresent(decision -> {
            RouteDecision restored = fromCache(decision);
            state.result(new FastPathResult(restored, state.rewrite(), state.slots(), null,
                    state.event(), null, splitIfNeeded(restored, state)));
        });
    }

    /** 二级：强规则。 */
    void strongRules(FastPathState state) {
        state.ruleContext(ruleContext(state.request(), state.rewrite(), state.slots()));
        Optional<StrongRule> matched = strongRules.firstMatch(state.ruleContext());
        matched.ifPresent(rule -> strongRuleExit(state, rule));
    }

    /**
     * 标准问答直答（FP-1I 小 i 共存）。
     *
     * <p>句法模版命中标准问，就直接念人写好的标准答案，不调模型也不建任务。这是与小 i 共存时
     * 口径必须一致的那一段，同一句话在两侧得到的答复不能不一样。
     *
     * <p><b>位置在规则仲裁这一步，召回照常先跑完</b>。省掉召回是省得下来的——答案是人写死的——
     * 但省掉之后就再也回答不了「这条模版抢了谁的活」：一条写宽了的标准问会悄悄吃掉本该
     * 走能力的流量，而没有候选集对照，它在出口分布上只表现为「知识问答涨了」。多付的是
     * 一次 embedding；省下的仍是仲裁那一次模型往返，而那一次才是贵的。
     *
     * <p>强规则仍排在它前面：策略拦截必须压过知识问答，否则一句「信用卡额度怎么调」
     * 会被答成一段操作指引，而它本该判成未开放。
     *
     * <p>吃的是**用户原话**。模版是照着口语写的，改写表会把口语换成检索用的核心业务词，
     * 拿改写结果去套模版，写模版的人会发现自己写的话匹配不上自己说的话。
     *
     * @return 是否已经直出。真则本轮到此为止，不再进模型仲裁
     */
    boolean standardAnswer(FastPathState state) {
        Optional<StandardQaBank.Entry> matched = bundle.standardQa().match(state.rewrite().original())
                .filter(entry -> slotsSatisfied(entry, state.slots()));
        matched.ifPresent(entry -> standardAnswerExit(state, entry));
        return matched.isPresent();
    }

    /**
     * 答案里要用的槽位一个都不能缺。
     *
     * <p>缺了就别命中，让这句话走正常链路——正常链路至少会问，而念一句带空洞的答案
     * 既解决不了问题，还让用户以为这就是系统能给的全部。
     */
    private static boolean slotsSatisfied(StandardQaBank.Entry entry, Map<String, Object> slots) {
        for (String required : entry.getRequiredSlots()) {
            Object value = slots == null ? null : slots.get(required);
            if (value == null || String.valueOf(value).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void standardAnswerExit(FastPathState state, StandardQaBank.Entry entry) {
        if (entry.isBlocked()) {
            blockedKnowledgeExit(state, entry);
            return;
        }
        // 标准答案使用 DIRECT_KNOWLEDGE，明确表达「已回答且无需执行」；
        // 恰恰是答完就结束、什么也不办。取消走的是同一个出口，先例见 continuation。
        // 真正把它与「拒绝」分开的是 reasonCode——看板按原因码拆，不看出口
        List<String> evidence = new java.util.ArrayList<>();
        evidence.add("standardQa:" + entry.getId());
        if (!entry.getSourceRef().isBlank()) {
            evidence.add(entry.getSourceRef());
        }
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.DIRECT_KNOWLEDGE)
                .reasonCode(ReasonCode.STANDARD_ANSWER)
                .confidence(1.0)
                .evidenceRefs(evidence)
                .configVersion(bundle.assetVersion())
                .shortCircuit(ShortCircuitLevel.STANDARD_ANSWER_RULE)
                .build();

        // 知识直答不是业务任务，只能带这条知识明确声明的槽位。
        // 通用抽槽可能把“换卡无忧”中的“无忧”误识为 payee，整包复制会污染后续任务记忆。
        Map<String, Object> slots = new LinkedHashMap<>();
        for (String required : entry.getRequiredSlots()) {
            slots.put(required, state.slots().get(required));
        }
        slots.put(StandardAnswer.SLOT_ANSWER, entry.getAnswer());
        if (!entry.getMenuOptions().isEmpty()) {
            List<Map<String, Object>> menuItems = entry.getMenuOptions().stream()
                    .map(menuId -> bundle.menus().find(menuId).orElseThrow(() ->
                            new IllegalStateException("标准问答 " + entry.getId()
                                    + " 引用了不存在的菜单 " + menuId)))
                    .map(menu -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("label", menu.getFinalName());
                        item.put("menuId", menu.getMenuId());
                        item.put("capabilityId", AssetLoader.capabilityId(menu));
                        item.put("query", AssetLoader.routeQuery(bundle.menus(), menu));
                        item.put("path", menu.getPath());
                        return Map.copyOf(item);
                    })
                    .toList();
            slots.put("menuItems", menuItems);
        }
        if (entry.hasAction()) {
            slots.put(StandardAnswer.SLOT_ACTION_LABEL, entry.getActionLabel());
            slots.put(StandardAnswer.SLOT_ACTION_CAPABILITY, entry.getActionCapabilityId());
        }

        meterRegistry.counter(AgentMetrics.STANDARD_ANSWER, AgentMetrics.TAG_QA_ID, entry.getId())
                .increment();

        // 召回本来会推谁，留在 trace 上。选中项传 null 是如实的——标准答案什么能力也没选，
        // 而对照里那个「本来的第一名」正是查「这条模版是不是抢了别人的活」的唯一依据
        decisionTrace.recordArbitrationComparison(state.rankedCandidates(), null);

        // 刻意不写出口缓存。缓存里只有 RouteDecision，装不下答案正文与动作入口，
        // 下一次命中缓存拿回来的会是一个没有答案的标准答案出口——渲染出来是一句兜底话术。
        // 强规则同样不写缓存，理由是一样的：出口带外挂数据的路径都不能进那个只存出口的缓存
        state.result(new FastPathResult(decision, state.rewrite(), slots,
                state.recallOutput() == null ? null : state.recallOutput().result(),
                state.event(), StandardAnswer.TEMPLATE_KEY, null));
    }

    /**
     * 来源损坏或答案未审批时，只给资产维护的安全引导，不把相邻执行能力当成答案。
     * 选项是自然语言，新一轮仍经过完整入口规则、模型和 PolicyGate。
     */
    private void blockedKnowledgeExit(FastPathState state, StandardQaBank.Entry entry) {
        List<String> evidence = new java.util.ArrayList<>();
        evidence.add("standardQaBlocked:" + entry.getId());
        if (!entry.getSourceRef().isBlank()) {
            evidence.add(entry.getSourceRef());
        }
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.POLICY_BLOCK)
                .confidence(1.0)
                .evidenceRefs(evidence)
                .configVersion(bundle.assetVersion())
                .shortCircuit(ShortCircuitLevel.STANDARD_ANSWER_RULE)
                .build();

        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("question", entry.getGuidance());
        slots.put("options", entry.getOptions());
        meterRegistry.counter(AgentMetrics.STANDARD_ANSWER, AgentMetrics.TAG_QA_ID, entry.getId())
                .increment();
        decisionTrace.recordArbitrationComparison(state.rankedCandidates(), null);
        state.result(new FastPathResult(decision, state.rewrite(), slots,
                state.recallOutput() == null ? null : state.recallOutput().result(),
                state.event(), "tpl.clarify.slot", null));
    }

    /** 混合召回与融合。 */
    void recall(FastPathState state) {
        DecisionTrace.PhaseSpan phase = decisionTrace.startPhaseSpan(
                FastPathEngine.PHASE_RECALL, state.request().ctx().traceId());
        try {
            recallWithinSpan(state);
        } catch (RuntimeException error) {
            phase.error(error);
            throw error;
        } finally {
            phase.close();
        }
    }

    private void recallWithinSpan(FastPathState state) {
        long start = System.nanoTime();
        HybridRecall.Output output = recall.recall(state.rewrite(), state.ruleContext());
        CandidateSet platformDefault = new CandidateSet(output.result(), output.fusedScores());
        CandidateSet processed = candidatePostProcessors.process(new IntentInput(
                state.request().ctx(), state.rewrite().original(), state.rewrite().normalized(),
                state.slots(), state.request().userStateValues()), platformDefault);
        output = new HybridRecall.Output(processed.recall(), output.multiTask(),
                output.intentPlan(), processed.fusedScores());
        state.recallOutput(output);
        long nanos = System.nanoTime() - start;
        state.recordPhase(FastPathEngine.PHASE_RECALL, nanos);
        decisionTrace.recordPhase(FastPathEngine.PHASE_RECALL, nanos);
        traceCandidates(state);
        List<ScoredCandidate> ranked = state.rankedCandidates() == null ? List.of() : state.rankedCandidates();
        List<String> topIds = ranked.stream().limit(5).map(ScoredCandidate::candidateId).toList();
        int candidateCount = output.result() == null || output.result().candidates() == null
                ? 0 : output.result().candidates().size();
        recordStep(state.request().ctx(), "intent-recall", "hybrid-recall", "MAIN",
                withContext(Map.of(
                        "normalizedQuery", nullSafe(state.rewrite().normalized()),
                        "semanticText", nullSafe(state.rewrite().semanticText()),
                        "searchText", nullSafe(state.rewrite().searchText())), state),
                Map.of(
                        "candidateCount", candidateCount,
                        "topCandidates", topIds,
                        "multiTask", output.multiTask(),
                        "hasIntentPlan", output.intentPlan() != null),
                "OK", nanos);
    }

    /** 三级：模型仲裁，失败按 §3.3 回退规则仲裁。规则先直出的那部分排在模型之前。 */
    void arbitrate(FastPathState state) {
        DecisionTrace.PhaseSpan phase = decisionTrace.startPhaseSpan(
                FastPathEngine.PHASE_ARBITRATION, state.request().ctx().traceId());
        try {
            arbitrateWithinSpan(state);
        } catch (RuntimeException error) {
            phase.error(error);
            throw error;
        } finally {
            phase.close();
        }
    }

    private void arbitrateWithinSpan(FastPathState state) {
        // 标准问答是纯规则判定，判得了就不必再问模型。放在这一步的开头而不是单独一个节点，
        // 是因为它要用召回的候选集做对照（见 standardAnswer 的说明）
        if (standardAnswer(state)) {
            return;
        }

        FastPathRequest request = state.request();
        // prompt 里写的是「用户输入：…」，那就得是用户真说的那句。塞改写结果进去，
        // 一来模型读到一句用户没说过的话，二来 FP-1J 的槽位回填要从原话里抠收款人和金额
        ArbitrationInput input = new ArbitrationInput(
                state.rewrite().original(), request.ctx().channel(), request.ctx().page(),
                state.recallOutput(), state.slots(), request.clarifyRounds(),
                request.contextualQuery(), request.intentContext());

        long start = System.nanoTime();
        Arbitrated arbitrated = doArbitrate(input);
        long nanos = System.nanoTime() - start;
        state.recordPhase(FastPathEngine.PHASE_ARBITRATION, nanos);
        decisionTrace.recordPhase(FastPathEngine.PHASE_ARBITRATION, nanos);
        recordStep(request.ctx(), "intent-arbitration", "task-shape", "MAIN",
                withContext(Map.of(
                        "query", nullSafe(input.normalizedQuery()),
                        "candidateIds", state.rankedCandidates() == null ? List.of()
                                : state.rankedCandidates().stream()
                                        .map(ScoredCandidate::candidateId).toList(),
                        "filledSlotKeys", input.filledSlots().keySet()), state),
                Map.of(
                        "decision", arbitrated.decision().decision().name(),
                        "reasonCode", arbitrated.decision().reasonCode() == null ? ""
                                : arbitrated.decision().reasonCode().name(),
                        "taskShape", arbitrated.decision().taskShape() == null ? ""
                                : arbitrated.decision().taskShape().name(),
                        "selectedCandidateIds", arbitrated.decision().candidateIds(),
                        "decidedByModel", arbitrated.decision().decidedByModel()),
                "OK", nanos);

        // 对照记在这里而不是 FastPathEngine：融合分只存在于 HybridRecall.Output 里，
        // 而 FastPathResult 只带 RecallResult。挪到出口那一步就得重算一次排序，
        // 那时对照的是两份各自算出来的排名（见 FastPathState.rankedCandidates）
        decisionTrace.recordArbitrationComparison(
                state.rankedCandidates(), arbitrated.decision().selectedCandidateId());

        // 模型不可用时产生的 LOW_MARGIN 是瞬时降级结论。把它缓存会让模型恢复后仍在 TTL 内
        // 持续澄清，甚至让完整固定计划无法进入 Static Plan。
        if (cacheEnabled(state) && cacheableDecision(arbitrated.decision())) {
            // browseMeta 只给控制台 Redis 浏览用；主缓存键仍是哈希，不把原话编进 key。
            Map<String, Object> browseMeta = new LinkedHashMap<>();
            browseMeta.put("query", state.rewrite().normalized());
            browseMeta.put("rawQuery", state.rewrite().original());
            browseMeta.put("spaceId", request.ctx().spaceId());
            browseMeta.put("agentId", request.ctx().agentId());
            browseMeta.put("channel", request.ctx().channel());
            browseMeta.put("page", request.ctx().page());
            browseMeta.put("userState", request.ctx().userState());
            browseMeta.put("assetVersion", bundle.assetVersion());
            cache.put(state.cacheKey(), arbitrated.decision(), browseMeta);
        }

        Map<String, Object> responseSlots = arbitrated.slots();
        String templateKey = null;
        if (arbitrated.decision().reasonCode() == ReasonCode.INCOMPARABLE_PRODUCT_TYPE) {
            ProductComparisonPolicyGate.Outcome comparison = productComparisonGrounder
                    .ground(input.normalizedQuery()).resolvedRequest()
                    .map(productComparisonPolicyGate::evaluate).orElse(null);
            if (comparison != null && comparison.incompatible()) {
                responseSlots = merge(responseSlots,
                        productComparisonPolicyGate.presentationSlots(comparison));
                templateKey = bundle.productComparisonPolicy().getOnIncompatible().templateKey();
            }
        }
        state.result(new FastPathResult(arbitrated.decision(), state.rewrite(), responseSlots,
                state.recallOutput().result(), state.event(), templateKey,
                intentPlanFor(arbitrated.decision(), state)));
    }

    /**
     * 多意图出口一定要带着拆解结果出去。
     *
     * <p>召回与模型可以各自判出多意图，且判据不同：召回靠连词表加「召回到几个不同能力」，
     * 模型靠语义。后者能读懂「查余额，再给老徐转 1000；不足就别转」这种词表覆盖不到的说法，
     * 于是出现召回没判、模型判了的组合——那时手上没有拆解结果，回复层只能说一句
     * 「您提到了多件事」，说不出是哪几件。用户看到这句话时无从选择先办哪一件，
     * 而多意图计划也开不起来，跨轮续办跟着一起失效。
     *
     * <p>这个缺口此前被降级掩盖着：语义通道一降级，召回的多任务判定就退回纯词表形态，
     * 反而每次都判得出、切得开。等模型真正接通，它才浮出来。
     */
    private IntentPlan intentPlanFor(RouteDecision decision, FastPathState state) {
        if (decision.intentPlan() != null) {
            return decision.intentPlan();
        }
        IntentPlan fromRecall = state.recallOutput().intentPlan();
        return fromRecall != null ? fromRecall : splitIfNeeded(decision, state);
    }

    /**
     * 仲裁之后补救拆解：多意图走连词切分，跨域对比走产品对照计划。
     * 单任务句子切出来的东西没人会用。
     */
    private IntentPlan splitIfNeeded(RouteDecision decision, FastPathState state) {
        String normalized = state.rewrite().normalized();
        if (decision.reasonCode() == ReasonCode.MULTI_INTENT
                || decision.reasonCode() == ReasonCode.RESULT_RULE) {
            return recall.split(normalized).orElse(null);
        }
        if (decision.reasonCode() == ReasonCode.CROSS_DOMAIN) {
            return recall.comparePlan(normalized).orElse(null);
        }
        return null;
    }

    // ==================== 路由谓词 ====================

    /**
     * 是否走续轮短路。
     *
     * <p>谓词与步骤分开，是为了让图引擎在**路由时**判断：跳过的步骤在 trace 里根本没有节点，
     * 而「进去了又空转返回」在 trace 里和真的执行过长得一样。
     */
    boolean continuationApplies(FastPathState state) {
        return state.request().activeTask() != null
                && state.event().event().allowsContinuationShortCircuit()
                && state.event().confidentEnoughToShortCircuit(eventClassifier.shortCircuitThreshold());
    }

    /**
     * 本轮是否查缓存。
     *
     * <p>澄清重试必须绕过一级缓存（v0.7 §2.1.1 注 4）。上一轮正是因为信息不全才被缓存成
     * CLARIFY，补充后仍读缓存会拿回同一个问题，用户会看到系统在原地重复提问。
     */
    boolean cacheEnabled(FastPathState state) {
        return !state.request().ctx().clarifyRetry()
                && (state.request().intentContext() == null
                        || state.request().intentContext().evidence().isEmpty())
                && !"true".equals(state.request().userStateValues().get("continuationContext"))
                && !"true".equals(state.request().userStateValues().get("pendingSwitch"))
                && !"true".equals(state.request().userStateValues().get("resumeContext"));
    }

    static boolean cacheableDecision(RouteDecision decision) {
        return decision != null && (decision.reasonCode() != ReasonCode.LOW_MARGIN
                || decision.decidedByModel())
                && decision.reasonCode() != ReasonCode.INCOMPARABLE_PRODUCT_TYPE;
    }

    // ==================== 内部 ====================

    /**
     * 二级短路：强规则直出。
     *
     * <p>正向规则（指向具体能力）仍要过必填槽位与风险等级，§3.3 对此没有例外。
     * 规则只回答「是不是这件事」，回答不了「参数够不够」。
     *
     * <p>不写缓存：规则求值是微秒级，缓存反而多一次 Redis 往返。
     */
    private void strongRuleExit(FastPathState state, StrongRule rule) {
        FastPathRequest request = state.request();
        Map<String, Object> slots = state.slots();
        ReasonCode reason = rule.reasonCode() == null
                ? ReasonCode.SHORT_CIRCUIT_STRONG_RULE
                : rule.reasonCode();

        RouteDecision.Builder builder = RouteDecision.builder()
                .decision(rule.decision())
                .reasonCode(reason)
                .confidence(1.0)
                .evidenceRefs(List.of("strongRule:" + rule.ruleId()))
                .configVersion(bundle.assetVersion())
                .shortCircuit(ShortCircuitLevel.L2_STRONG_RULE);

        if (rule.isPositive()) {
            builder.candidateIds(List.of(rule.capabilityId()));
            CapabilityCard card = bundle.capability(rule.capabilityId());
            List<String> missing = slotGate.missingSlots(card, slots);
            if (!missing.isEmpty()) {
                boolean exhausted = request.clarifyRounds() >= bundle.fusion().getClarify().getMaxRounds();
                builder.decision(exhausted ? Decision.HANDOFF : Decision.CLARIFY)
                        .reasonCode(exhausted ? ReasonCode.CLARIFY_EXHAUSTED : ReasonCode.MISSING_SLOT)
                        .missingSlots(missing);
            } else if (card != null && card.riskLevel().requiresExplicitConfirmation()
                    && rule.decision() == Decision.EXECUTE_CAPABILITY) {
                builder.reasonCode(ReasonCode.CONFIRMATION_REQUIRED);
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>(slots);
        merged.putAll(rule.slots());
        state.result(FastPathResult.shortCircuit(builder.build(), state.rewrite(), merged,
                state.event(), rule.templateKey()));
    }

    /**
     * 模型回填的槽位在 fail-safe 复核**之前**并入。
     *
     * <p>复核要判「必填槽位齐不齐」，拿的若是正则那份，模型刚从「给我老板转两千」里读出的
     * 收款人和金额就白读了，系统仍会追问一遍用户已经说过的事。
     */
    private Arbitrated doArbitrate(ArbitrationInput input) {
        RouteDecision deterministic = ruleArbitrator.arbitrate(input, ShortCircuitLevel.NONE, false);
        boolean gray = deterministic.reasonCode() == ReasonCode.LOW_MARGIN
                || deterministic.reasonCode() == ReasonCode.CROSS_DOMAIN
                || deterministic.reasonCode() == ReasonCode.MULTI_INTENT
                || deterministic.reasonCode() == ReasonCode.AFTER_OBSERVATION
                || deterministic.decision() == Decision.STATIC_PLAN
                || deterministic.decision() == Decision.START_LOOP
                || bundle.fusion().getPlanning().getDiagnosticMarkers().stream()
                        .anyMatch(input.normalizedQuery()::contains);
        if (!gray) {
            return new Arbitrated(deterministic, input.filledSlots());
        }
        Optional<ModelArbitrator.Result> model = modelArbitrator.arbitrate(input);
        if (model.isPresent()) {
            Map<String, Object> merged = merge(input.filledSlots(), model.get().modelSlots());
            ArbitrationInput enriched = input.withSlots(merged);
            Optional<RouteDecision> tightened = failSafeGuard.tighten(model.get().decision(), enriched);
            if (tightened.isPresent()) {
                return new Arbitrated(tightened.get(), merged);
            }
        }
        RouteDecision fallback = ruleArbitrator.arbitrate(input, ShortCircuitLevel.NONE, true);
        // 回退是运维要盯的信号：模型不可用时出口分布会整体右移，但只有这个计数能说明原因
        meterRegistry.counter(AgentMetrics.DEGRADED,
                AgentMetrics.TAG_COMPONENT, "arbitration",
                AgentMetrics.TAG_REASON, "rule-fallback").increment();
        return new Arbitrated(fallback, input.filledSlots());
    }

    /** 出口与其对应的槽位。模型回填后两者必须成对流转，分开传必然有一处拿到旧的那份。 */
    private record Arbitrated(RouteDecision decision, Map<String, Object> slots) {
    }

    private static RouteDecision fromCache(RouteDecision cached) {
        // 保留原始 reasonCode：命中缓存不改变「当初为什么是这个出口」，
        // 「这次没算」由 shortCircuit 字段表达，两个维度分别打点才看得出缓存的贡献
        return RouteDecision.builder()
                .decision(cached.decision())
                .target(cached.target())
                .candidateIds(cached.candidateIds())
                .taskShape(cached.taskShape())
                .confidence(cached.confidence())
                .reasonCode(cached.reasonCode() == null ? ReasonCode.SHORT_CIRCUIT_CACHE : cached.reasonCode())
                .missingSlots(cached.missingSlots())
                .evidenceRefs(cached.evidenceRefs())
                .modelVersion(cached.modelVersion())
                .promptVersion(cached.promptVersion())
                .configVersion(cached.configVersion())
                .shortCircuit(ShortCircuitLevel.L1_CACHE)
                .build();
    }

    private String cacheKey(FastPathRequest request, RewriteResult rewrite) {
        return DecisionCacheKey.of(request.ctx(), rewrite.normalized(), bundle.assetVersion(),
                modelProps.getEmbedding().getModel(), modelProps.getEmbedding().getInstructionVersion(),
                bundle.arbitrationSkill().getVersion(),
                bundle.fusion().getCache().getUserStateDimensions(), request.userStateValues());
    }

    /** 供 Aviator 规则读取的上下文。字段名即规则表达式里的变量名，改名等于改资产契约。 */
    private static Map<String, Object> ruleContext(FastPathRequest request, RewriteResult rewrite,
                                                   Map<String, Object> slots) {
        RequestContext ctx = request.ctx();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("query", rewrite.normalized());
        context.put("rawQuery", rewrite.original());
        context.put("channel", ctx.channel() == null ? "" : ctx.channel());
        context.put("page", ctx.page() == null ? "" : ctx.page());
        context.put("userState", ctx.userState() == null ? "" : ctx.userState());
        context.put("agentId", ctx.agentId());
        context.put("slots", slots);
        context.put("hasActiveTask", request.activeTask() != null);
        return context;
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(override);
        return merged;
    }

    /**
     * 把仲裁前的候选集与打分投影到 trace（FP-62）。
     *
     * <p>投影而非直接递交 {@code RecallResult}：{@link ScoredCandidate} 装不下用户说的话，
     * 这里就是那个边界。要让 span 上出现归一化查询或抽出的收款人金额，得先改那个 record 的字段。
     */
    private void traceCandidates(FastPathState state) {
        HybridRecall.Output output = state.recallOutput();
        List<RecallResult.Candidate> candidates = output.result().candidates();
        if (candidates == null || candidates.isEmpty()) {
            decisionTrace.recordCandidates(List.of(), 0, output.result().degradedChannels());
            return;
        }
        Map<String, Double> fused = output.fusedScores();
        List<ScoredCandidate> projected = candidates.stream()
                .map(c -> new ScoredCandidate(
                        c.candidateId(),
                        fused.getOrDefault(c.candidateId(), 0.0),
                        c.scores().semantic(),
                        c.scores().rule(),
                        c.scores().negative(),
                        c.matchedEvidence()))
                // 按融合分重排而不是信赖入参顺序：头两名的分差是这份数据最主要的用途，
                // 顺序错了这个数就是错的，而错得很不显眼
                .sorted(Comparator.comparingDouble(ScoredCandidate::fusedScore).reversed())
                .toList();
        state.rankedCandidates(projected);
        decisionTrace.recordCandidates(projected, projected.size(), output.result().degradedChannels());
    }

    private static void recordStep(RequestContext ctx, String moduleId, String operation, String role,
                                   Map<String, Object> input, Map<String, Object> output,
                                   String outcome, long nanos) {
        if (ctx == null) {
            return;
        }
        ctx.recordModuleStep(new RuntimeModuleStep(
                moduleId,
                operation,
                role,
                input,
                output,
                outcome,
                nanos / 1_000_000L));
    }

    private static Map<String, Object> withContext(
            Map<String, Object> moduleInput, FastPathState state) {
        Map<String, Object> input = new LinkedHashMap<>(moduleInput == null ? Map.of() : moduleInput);
        IntentContext context = state.request().intentContext();
        ContextualQuery rewrite = state.request().contextualQuery();
        if (context != null) {
            input.put("contextStateVersion", context.stateVersion());
            input.put("contextTrustworthy", context.trustworthy());
            input.put("conversationHistory", context.conversationHistory());
            input.put("availableContext", context.evidence());
            input.put("availableContextRefs", context.evidenceRefs());
        }
        if (rewrite != null) {
            input.put("standaloneQuery", rewrite.standaloneQuery());
            input.put("contextEventType", rewrite.eventType().name());
            input.put("usedContextRefs", rewrite.usedContextRefs());
            input.put("contextSlotUpdates", rewrite.slotUpdates());
        }
        return Map.copyOf(input);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
