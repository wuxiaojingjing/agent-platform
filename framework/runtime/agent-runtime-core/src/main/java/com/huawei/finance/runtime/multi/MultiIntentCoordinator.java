package com.huawei.finance.runtime.multi;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.intent.ComparePlans;
import com.huawei.finance.orchestrator.OrchestrationOutcome;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.IntentPlanner;
import com.huawei.finance.intent.SlowPathProperties;
import com.huawei.finance.runtime.SessionAffinityMismatchException;
import com.huawei.finance.runtime.spi.SessionAffinityPort;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多意图计划在一次请求里的进出：开计划、续办、推进游标。
 *
 * <p>单独一个类而不是塞进 {@code ChatService}，是因为它是**唯一**有权改写「本轮实际要办
 * 什么」的地方。这种改写很危险——它会让快路径看到的不是用户刚说的那句话——所以它必须只有
 * 一处入口，而不是散落在编排流程的几个 if 里。
 *
 * <p>一个会话同时只有一份在办计划，也同时只有一条活跃任务。两条唯一性合在一起决定了执行
 * 形态：一次只办一件，办完再推进。计划不是并发调度器。
 *
 * <p>开计划与续办走 {@link SessionAffinity}（FP-66 / ADR-004）：DeepAgent Workspace 粘实例。
 */
public class MultiIntentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MultiIntentCoordinator.class);

    private final IntentPlanRepository plans;
    private final IntentPlanner planner;
    private final ConditionEvaluator conditions;
    private final SlowPathProperties props;
    private final SessionAffinityPort affinity;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final PlanGroundingPolicy grounding = new PlanGroundingPolicy();

    public MultiIntentCoordinator(IntentPlanRepository plans, IntentPlanner planner,
                                  ConditionEvaluator conditions, SlowPathProperties props,
                                  SessionAffinityPort affinity) {
        this.plans = plans;
        this.planner = planner;
        this.conditions = conditions;
        this.props = props;
        this.affinity = affinity;
        this.meterRegistry = null;
        this.tracer = null;
    }

    public MultiIntentCoordinator(IntentPlanRepository plans, IntentPlanner planner,
                                  ConditionEvaluator conditions, SlowPathProperties props,
                                  SessionAffinityPort affinity, MeterRegistry meterRegistry,
                                  Tracer tracer) {
        this.plans = plans;
        this.planner = planner;
        this.conditions = conditions;
        this.props = props;
        this.affinity = affinity;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /**
     * 条件依赖是否放行。
     *
     * <p>判不出来时 {@link ConditionEvaluator.Verdict#UNDECIDED} 并不代表拒绝，只表示不能
     * 自动继续——调用方据此把问题交回用户。真正的资金安全兜底在 R2 的显式确认，不在这里。
     */
    public ConditionEvaluator.Evaluation checkCondition(Continuation continuation,
                                                        Map<String, Object> previousFacts,
                                                        Map<String, Object> slots) {
        if (continuation.item() == null) {
            return new ConditionEvaluator.Evaluation(
                    ConditionEvaluator.Verdict.PROCEED, null, Map.of(), "not-continuing");
        }
        return conditions.explain(continuation.item(), previousFacts, slots);
    }

    /**
     * 判出多意图时开一份计划，并给出用于渲染的那一份。
     *
     * <p>委托 {@link #openIfSlowPath}：历史调用点保留，语义不变。
     */
    public IntentPlan openIfMultiIntent(AssetBundle bundle, RequestContext ctx, RouteDecision decision,
                                        IntentPlan rulePlan) {
        return openIfSlowPath(bundle, ctx, decision, rulePlan);
    }

    /**
     * 慢路径开计划：多意图落库续办；跨域对比只返回计划（本轮联邦取事实，不建 pick-one 游标）。
     *
     * <p>开关打开时多意图先让规划器精化：它读得懂「不足就别转」是条件而不是第三件事，也认得出
     * 规则词表覆盖不到的能力。精化失败一律回退规则拆解，慢路径不该把规则本来能给的也弄丢。
     *
     * @return 用于话术渲染的计划，没有则为 null（调用方退回原有的笼统引导话术）
     */
    public IntentPlan openIfSlowPath(AssetBundle bundle, RequestContext ctx, RouteDecision decision,
                                     IntentPlan rulePlan) {
        if (decision.decision() != Decision.STATIC_PLAN) {
            return null;
        }
        ReasonCode reason = decision.reasonCode();
        if (reason != ReasonCode.MULTI_INTENT
                && reason != ReasonCode.RESULT_RULE
                && reason != ReasonCode.CROSS_DOMAIN) {
            return null;
        }

        if (affinity.claim(ctx) == SessionAffinityPort.Outcome.MISMATCH) {
            throw new SessionAffinityMismatchException(ctx.sessionId(), affinity.instanceId());
        }

        // 跨域对比：计划只服务本轮联邦拉取，落成 pick-one 游标会逼用户「选办哪一件」
        if (reason == ReasonCode.CROSS_DOMAIN) {
            if (!ComparePlans.isComparePlan(rulePlan)) {
                affinity.release(ctx);
                return null;
            }
            log.info("跨域对比计划 session={} 件数={}", ctx.sessionId(), rulePlan.items().size());
            return rulePlan;
        }

        IntentPlan plan = props.isEnabled() ? refine(bundle, ctx, rulePlan) : rulePlan;
        if (plan == null) {
            affinity.release(ctx);
            return null;
        }

        try {
            PlanRecord opened = plans.open(ctx.agentId(), ctx.sessionId(), ctx.traceId(), plan);
            log.info("开计划 plan={} 件数={} 来源={} 含条件={}",
                    opened.planId(), plan.items().size(), plan.source(), plan.hasConditional());
        } catch (RuntimeException e) {
            // 计划存不下不该让这一轮失败：话术照样能把几件事说清楚，代价是下一轮续不上。
            // 反过来把已经算出来的拆解连同这次回复一起丢掉，用户什么也得不到
            log.warn("计划落盘失败，本轮仍按拆解回话但续办不可用 session={} cause={}",
                    ctx.sessionId(), e.toString());
            affinity.release(ctx);
        }
        return plan;
    }

    /**
     * 跳过当前这一件：条件判成「不做」时用。
     *
     * <p>与办完了一样推进游标——这件事已经有结论，只是结论是不办。不推的话下一轮会把它
     * 重新端到用户面前，而用户刚刚已经用条件表达过态度了。
     */
    public void skipCurrent(Continuation continuation) {
        if (continuation.plan() == null) {
            return;
        }
        PlanRecord record = continuation.plan();
        if (!plans.advance(record.planId(), record.cursor())) {
            log.warn("跳过时游标推进落空 plan={} cursor={}", record.planId(), record.cursor());
        }
        releaseIfIdle(record);
    }

    /** 本轮办完一件就推进游标。没办完（还在澄清或确认）不推，否则会跳过这件事。 */
    public void afterTurn(Continuation continuation, OrchestrationOutcome outcome) {
        if (continuation.plan() == null || outcome.state() == null || !outcome.state().terminal()) {
            return;
        }
        PlanRecord record = continuation.plan();
        if (!plans.advance(record.planId(), record.cursor())) {
            log.warn("游标推进落空，计划可能已被另一路请求动过 plan={} cursor={}",
                    record.planId(), record.cursor());
        }
        releaseIfIdle(record);
    }

    private void releaseIfIdle(PlanRecord record) {
        if (plans.findActiveBySession(record.agentId(), record.sessionId()).isEmpty()) {
            releaseAffinity(record);
        }
    }

    /** 跨域对比等不落游标的慢路径出口：显式释放本轮 claim。 */
    public void releaseAffinity(RequestContext ctx) {
        affinity.release(ctx);
    }

    private void releaseAffinity(PlanRecord record) {
        affinity.release(ctxFor(record));
    }

    private static RequestContext ctxFor(PlanRecord record) {
        RequestContext held = RequestContextHolder.get();
        if (held != null) {
            return held;
        }
        return new RequestContext("affinity", record.sessionId(), "u", RequestContext.SPACE_UNSCOPED,
                record.agentId(), "MOBILE_BANK", "", "", false);
    }

    private IntentPlan refine(AssetBundle bundle, RequestContext ctx, IntentPlan rulePlan) {
        if (rulePlan == null) {
            return null;
        }
        if (rulePlan.items().stream().allMatch(item -> item.resolution().locked())) {
            recordPlanning(ctx, rulePlan, PlanGroundingPolicy.Outcome.ALL_LOCKED, 0);
            return rulePlan;
        }
        if (rulePlan.items().stream().anyMatch(item -> item.resolution().candidateIds().isEmpty())) {
            recordPlanning(ctx, rulePlan, PlanGroundingPolicy.Outcome.EMPTY_CANDIDATES, 0);
            return rulePlan;
        }

        List<CapabilityCard> candidates = planningCandidates(bundle, rulePlan);
        if (candidates.isEmpty() || !coversEveryStep(candidates, rulePlan)) {
            recordPlanning(ctx, rulePlan, PlanGroundingPolicy.Outcome.EMPTY_CANDIDATES, 0);
            return rulePlan;
        }
        IntentPlan proposed = planner.plan(rulePlan.original(), candidates, rulePlan).orElse(rulePlan);
        PlanGroundingPolicy.Result result = grounding.validate(rulePlan, proposed);
        recordPlanning(ctx, result.plan(), result.outcome(), candidates.size());
        return result.plan();
    }

    /** 每步先拿一个，再轮询补齐；不能让资产文件顺序决定模型看见什么。 */
    private List<CapabilityCard> planningCandidates(AssetBundle bundle, IntentPlan plan) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int limit = Math.max(1, props.getMaxCandidates());
        int depth = 0;
        boolean added;
        do {
            added = false;
            for (SubIntent item : plan.items()) {
                List<String> candidates = item.resolution().candidateIds();
                if (depth < candidates.size() && ids.size() < limit) {
                    added |= ids.add(candidates.get(depth));
                }
            }
            depth++;
        } while (added && ids.size() < limit);

        List<CapabilityCard> cards = new ArrayList<>();
        for (String id : ids) {
            CapabilityCard card = bundle.capability(id);
            if (card != null) {
                cards.add(card);
            }
        }
        return List.copyOf(cards);
    }

    private static boolean coversEveryStep(List<CapabilityCard> candidates, IntentPlan plan) {
        Set<String> available = candidates.stream()
                .map(CapabilityCard::capabilityId)
                .collect(java.util.stream.Collectors.toSet());
        return plan.items().stream()
                .allMatch(item -> item.resolution().candidateIds().stream().anyMatch(available::contains));
    }

    private void recordPlanning(RequestContext ctx, IntentPlan plan,
                                PlanGroundingPolicy.Outcome outcome, int candidateCount) {
        long locked = plan.items().stream()
                .filter(item -> item.resolution().strength() == PlanResolution.Strength.LOCKED).count();
        long preferred = plan.items().stream()
                .filter(item -> item.resolution().strength() == PlanResolution.Strength.PREFERRED).count();
        long unresolved = plan.items().size() - locked - preferred;
        if (meterRegistry != null) {
            meterRegistry.counter(AgentMetrics.SLOWPATH_PLANNING,
                    AgentMetrics.TAG_OUTCOME, outcome.name()).increment();
        }
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span != null) {
            span.tag("agent.plan.grounding", outcome.name());
            span.tag("agent.plan.locked", String.valueOf(locked));
            span.tag("agent.plan.preferred", String.valueOf(preferred));
            span.tag("agent.plan.unresolved", String.valueOf(unresolved));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("outcome", outcome.name());
        output.put("source", plan.source().name());
        output.put("locked", locked);
        output.put("preferred", preferred);
        output.put("unresolved", unresolved);
        output.put("candidateCount", candidateCount);
        ctx.recordModuleStep(new RuntimeModuleStep("intent-slowpath", "ground-plan", "MAIN",
                Map.of("stepCount", plan.items().size()), output, outcome.name(), null));
    }

    /**
     * 本轮要办什么，以及它属于哪份计划的哪一件。
     *
     * @param query 交给快路径的文本。续办时是子意图片段，否则是用户原话
     * @param plan  所属计划，非续办为 null
     * @param item  所办的子意图，非续办为 null
     */
    public record Continuation(String query, PlanRecord plan, SubIntent item) {

        public boolean continuing() {
            return plan != null;
        }
    }
}
