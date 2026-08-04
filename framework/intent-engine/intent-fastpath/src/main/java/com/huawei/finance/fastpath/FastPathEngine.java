package com.huawei.finance.fastpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.event.ActiveTaskView;
import com.huawei.finance.common.event.EventClassification;
import com.huawei.finance.common.event.EventClassifier;
import com.huawei.finance.common.event.InputEvent;
import com.huawei.finance.common.event.IntentSignalProbe;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.obs.trace.ScoredCandidate;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.contracts.validation.ValidationOutcome;
import com.huawei.finance.fastpath.arbitration.ArbitrationInput;
import com.huawei.finance.fastpath.arbitration.FailSafeGuard;
import com.huawei.finance.fastpath.arbitration.ModelArbitrator;
import com.huawei.finance.fastpath.arbitration.RuleArbitrator;
import com.huawei.finance.fastpath.arbitration.SlotGate;
import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.fastpath.cache.DecisionCacheKey;
import com.huawei.finance.fastpath.recall.HybridRecall;
import com.huawei.finance.fastpath.rewrite.QueryRewriter;
import com.huawei.finance.fastpath.rewrite.RewriteResult;
import com.huawei.finance.fastpath.rewrite.SlotExtractor;
import com.huawei.finance.fastpath.rule.StrongRuleEngine;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.StrongRule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 快路径主流程（v0.7 §3.3）。
 *
 * <p>这里是整条链路的**仲裁唯一切换点**：无论走哪一级短路、模型是否可用，
 * 出口都从这个类返回，且一律是同一个 {@code RouteDecision} 契约。
 * 让某条路径「直接返回结果」看起来能省几行代码，代价是分流出口看板从此漏统计一整类流量。
 *
 * <p>三级短路的顺序是成本顺序：一级缓存零计算，二级强规则微秒级，三级才动模型。
 */
public class FastPathEngine {

    private static final Logger log = LoggerFactory.getLogger(FastPathEngine.class);

    /**
     * 分段耗时的阶段名（FP-62）。
     *
     * <p>只切这三段，因为它们劣化时的处置方式互不相同：改写是本地 CPU（扩容），
     * 召回含 embedding 往返（摘语义通道），仲裁含大模型往返（降级到规则仲裁）。
     * 切得更细并不会多指出一个可执行的动作，只会多几条没人看的曲线。
     */
    static final String PHASE_REWRITE = "rewrite";
    static final String PHASE_RECALL = "recall";
    static final String PHASE_ARBITRATION = "arbitration";

    /** 出口由谁定，见 {@link DecisionTrace#recordDecision}。 */
    private static final String BY_MODEL = "MODEL";
    private static final String BY_RULE_FALLBACK = "RULE_FALLBACK";
    private static final String BY_SHORT_CIRCUIT = "SHORT_CIRCUIT";

    private final ContractValidator validator;
    private final MeterRegistry meterRegistry;
    private final DecisionTrace decisionTrace;
    private final FastPathSteps steps;
    private final FastPathGraph graph;

    public FastPathEngine(AssetBundle bundle, EventClassifier eventClassifier, IntentSignalProbe intentProbe,
                          QueryRewriter rewriter, SlotExtractor slotExtractor, HybridRecall recall,
                          StrongRuleEngine strongRules, DecisionCache cache, RuleArbitrator ruleArbitrator,
                          ModelArbitrator modelArbitrator, FailSafeGuard failSafeGuard, SlotGate slotGate,
                          ContractValidator validator, ModelGatewayProperties modelProps,
                          MeterRegistry meterRegistry, DecisionTrace decisionTrace) {
        this(bundle, eventClassifier, intentProbe, rewriter, slotExtractor, recall, strongRules,
                cache, ruleArbitrator, modelArbitrator, failSafeGuard, slotGate, validator,
                modelProps, meterRegistry, decisionTrace, List.of());
    }

    public FastPathEngine(AssetBundle bundle, EventClassifier eventClassifier,
                          IntentSignalProbe intentProbe, QueryRewriter rewriter,
                          SlotExtractor slotExtractor, HybridRecall recall,
                          StrongRuleEngine strongRules, DecisionCache cache,
                          RuleArbitrator ruleArbitrator, ModelArbitrator modelArbitrator,
                          FailSafeGuard failSafeGuard, SlotGate slotGate,
                          ContractValidator validator, ModelGatewayProperties modelProps,
                          MeterRegistry meterRegistry, DecisionTrace decisionTrace,
                          List<CandidatePostProcessor> candidatePostProcessors) {
        this.validator = validator;
        this.meterRegistry = meterRegistry;
        this.decisionTrace = decisionTrace == null ? DecisionTrace.NOOP : decisionTrace;
        this.steps = new FastPathSteps(bundle, eventClassifier, intentProbe, rewriter, slotExtractor,
                recall, strongRules, cache, ruleArbitrator, modelArbitrator, failSafeGuard, slotGate,
                modelProps, meterRegistry, this.decisionTrace, candidatePostProcessors);
        this.graph = new FastPathGraph(steps);
    }

    public FastPathResult decide(FastPathRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try (DecisionTrace.TraceScope ignored = decisionTrace.bindParent(request.ctx().traceId())) {
            FastPathResult result = graph.decide(request);
            record(result, request.ctx());
            return result;
        } finally {
            sample.stop(meterRegistry.timer(AgentMetrics.FASTPATH_LATENCY));
        }
    }

    /**
     * 顺序执行的那份实现，只给比对用例调。
     *
     * <p>生产走图执行。留着它是因为现有用例只能证明「跑到的分支一致」，
     * 证明不了没跑到的那些，见 {@link SequentialFastPath}。
     */
    FastPathResult decideSequentially(FastPathRequest request) {
        return new SequentialFastPath(steps).decide(request);
    }

    /**
     * 出口由谁定。不新增管道，从契约字段推：只有 {@link ModelArbitrator} 会写
     * {@code modelVersion}，{@link FailSafeGuard} 收紧时保留它，其余路径留哨兵值。
     *
     * <p>短路优先判断：命中缓存时 {@code modelVersion} 是**上一次**算出来的那份，
     * 这一次并没有调模型。少了这一步，「模型仲裁量」会凭空多出缓存命中的那部分，
     * 而缓存命中率越高这个数虚得越多。
     */
    private static String arbitratedBy(RouteDecision decision) {
        ShortCircuitLevel sc = decision.shortCircuit();
        // L3_MODEL 是「走了模型仲裁」的标记，不是缓存/强规则短路
        if (sc != null && sc != ShortCircuitLevel.NONE && sc != ShortCircuitLevel.L3_MODEL) {
            return BY_SHORT_CIRCUIT;
        }
        return decision.decidedByModel() ? BY_MODEL : BY_RULE_FALLBACK;
    }

    private void record(FastPathResult result, RequestContext ctx) {
        RouteDecision decision = result.decision();

        decisionTrace.recordDecision(
                String.valueOf(decision.decision()),
                String.valueOf(decision.reasonCode()),
                String.valueOf(decision.shortCircuit()),
                decision.selectedCandidateId(),
                decision.confidence(),
                arbitratedBy(decision));
        decisionTrace.recordDecisionVersions(decision.modelVersion(), decision.promptVersion());

        ValidationOutcome outcome = validator.validate(SchemaRef.ROUTE_DECISION, decision);
        if (!outcome.valid()) {
            // 自己产出的契约不合自己的 Schema 属于代码缺陷，但不能让一次请求 500。
            // 打成 error 并计数，交给告警；请求本身照常返回
            log.error("产出的 RouteDecision 不合契约 trace={} 原因={}", ctx.traceId(), outcome.summary());
            meterRegistry.counter(AgentMetrics.DEGRADED,
                    AgentMetrics.TAG_COMPONENT, "fastpath",
                    AgentMetrics.TAG_REASON, "self-contract-violation").increment();
        }

        meterRegistry.counter(AgentMetrics.ARBITRATION_DECISION,
                AgentMetrics.TAG_DECISION, String.valueOf(decision.decision()),
                AgentMetrics.TAG_REASON_CODE, String.valueOf(decision.reasonCode()),
                AgentMetrics.TAG_SHORT_CIRCUIT, String.valueOf(decision.shortCircuit())).increment();

        meterRegistry.counter(AgentMetrics.SHORT_CIRCUIT,
                AgentMetrics.TAG_LEVEL, String.valueOf(decision.shortCircuit())).increment();

        meterRegistry.summary(AgentMetrics.GATEWAY_ROUND_TRIPS).record(ctx.gatewayRoundTrips());
    }
}
