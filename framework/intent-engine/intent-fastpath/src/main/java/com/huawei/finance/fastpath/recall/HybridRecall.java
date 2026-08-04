package com.huawei.finance.fastpath.recall;

import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.port.CandidateHit;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.FusionConfig;
import com.huawei.finance.fastpath.rewrite.RewriteResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多路召回与融合（v0.7 §3.2）。
 *
 * <p>融合顺序按 §3.2 原文：安全与互斥规则优先、强规则校验、其他证据融合、保留 Top-N 和来源。
 * 强规则已在进入这里之前判过（二级短路），所以这里负责的是「负向优先，再融合正向」。
 *
 * <p>通道降级时**重分配权重而不是让总分塌陷**。若语义通道断了却仍按原权重算，
 * 所有候选的总分都会掉到阈值以下，结果是断个 embedding 就让全部请求变成 NO_CANDIDATE。
 */
public class HybridRecall {

    private final AssetBundle bundle;
    private final RuleRecall ruleRecall;
    private final CandidateSearch search;
    private final NegativeFilter negativeFilter;
    private final MultiTaskDetector multiTaskDetector;
    private final IntentSplitter intentSplitter;
    private final ComparePlanBuilder comparePlanBuilder;
    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties modelProps;
    private final MeterRegistry meterRegistry;

    public HybridRecall(AssetBundle bundle, RuleRecall ruleRecall, CandidateSearch search,
                        NegativeFilter negativeFilter, MultiTaskDetector multiTaskDetector,
                        ModelGatewayClient gateway, ModelGatewayProperties modelProps,
                        MeterRegistry meterRegistry) {
        this.bundle = bundle;
        this.ruleRecall = ruleRecall;
        this.search = search;
        this.negativeFilter = negativeFilter;
        this.multiTaskDetector = multiTaskDetector;
        // 不进构造参数：切分只依赖资产与规则召回，两者都已在手，而多一个参数就要改所有
        // 装配点与测试的构造调用，换来的可替换性并没有人需要
        this.intentSplitter = new IntentSplitter(bundle, ruleRecall);
        this.comparePlanBuilder = new ComparePlanBuilder(bundle);
        this.gateway = gateway;
        this.modelProps = modelProps;
        this.meterRegistry = meterRegistry;
    }

    public Output recall(RewriteResult rewrite, Map<String, Object> ruleContext) {
        String normalizedQuery = rewrite.normalized();
        FusionConfig fusion = bundle.fusion();
        FusionConfig.Channels channels = fusion.getChannels();
        List<String> degraded = new ArrayList<>();

        RuleRecall.Result rules;
        if (channels.isRuleEnabled()) {
            rules = ruleRecall.recall(normalizedQuery);
        } else {
            rules = new RuleRecall.Result(Map.of(), Map.of());
            degraded.add("rule");
        }

        Map<String, Double> bm25 = Map.of();
        if (channels.isBm25Enabled() && search.searchable()) {
            List<CandidateHit> hits = search.bm25(rewrite.searchText(), rewrite.terms(),
                    fusion.getTopN().getCandidates() * 2);
            bm25 = normalizeBm25(hits, fusion.getNormalization().getBm25Saturation());
        } else {
            degraded.add("bm25");
        }

        Map<String, Double> semantic = Map.of();
        if (channels.isSemanticEnabled() && search.semanticAvailable()) {
            // 语义通道吃原话而非改写结果：文档侧的 utterances 就是口语原句，
            // 把「卡里还有多少钱」先改写成「余额」再去匹配，等于抹掉最高分那条
            Semantic result = semanticRecall(rewrite.semanticText(), fusion);
            semantic = result.scores();
            if (result.degradedReason() != null) {
                degraded.add("semantic");
            }
        } else {
            degraded.add("semantic");
        }

        if (degraded.contains("semantic")) {
            meterRegistry.counter(AgentMetrics.DEGRADED,
                    AgentMetrics.TAG_COMPONENT, "recall",
                    AgentMetrics.TAG_REASON, "semantic-channel-off").increment();
        }

        NegativeFilter.Result negatives = negativeFilter.apply(ruleContext);
        Fused fused = fuse(rules, bm25, semantic, negatives, fusion, degraded);
        fused = maybeRerank(rewrite, fused, fusion, degraded);

        // 语义通道是能力计数的主要来源。它一降级，「召回到几个能力」就不再是可用的否定证据，
        // 多任务判定必须退回纯词表的保守形态
        boolean recallTrustworthy = !degraded.contains("semantic");
        MultiTaskDetector.Signal multiTask = multiTaskDetector.detect(
                normalizedQuery, countSignificant(fused, fusion), recallTrustworthy);

        // 能按能力资产把每个分句都锁定时，计划本身就是比“显著候选数量”更强的确定性证据。
        // 语义召回可能把第二个能力压到显著阈值下，但这不能推翻已完整解析的固定计划。
        IntentPlan resolvedSplit = null;
        if (multiTask.hasConjunction() || multiTask.hasConditional()) {
            IntentPlan split = intentSplitter.split(normalizedQuery).orElse(null);
            if (split != null && split.fullyResolved()) {
                resolvedSplit = split;
                multiTask = new MultiTaskDetector.Signal(
                        true, multiTask.hasConjunction(), multiTask.hasConditional());
            }
        }

        // 对比信号先算：即便融合分被语义通道拉开，也不能把跨域对比误判成单域直出
        IntentPlan comparePlan = comparePlanBuilder.plan(normalizedQuery).orElse(null);
        RecallResult.DomainRouting routing = routing(fused, fusion, multiTask, comparePlan != null);

        // 多任务走 IntentSplitter；跨域对比走 ComparePlanBuilder（不是连词多任务，切不开）
        IntentPlan plan = null;
        if (multiTask.multiTask()) {
            plan = resolvedSplit != null ? resolvedSplit : intentSplitter.split(normalizedQuery).orElse(null);
        } else if (comparePlan != null) {
            plan = comparePlan;
        }

        return new Output(
                new RecallResult(routing, fused.candidates(), degraded),
                multiTask,
                plan,
                fused.scores());
    }

    /** 语义召回：query 侧拼指令，文档侧不拼（实施架构 §2.5.6 落地约束 1）。 */
    private Semantic semanticRecall(String normalizedQuery, FusionConfig fusion) {
        String instructed = modelProps.getEmbedding().formatQuery(normalizedQuery);
        GatewayResult<List<float[]>> embedding = gateway.embed(List.of(instructed));
        if (!embedding.available() || embedding.value().isEmpty()) {
            return new Semantic(Map.of(), embedding.reason() == null ? "empty" : embedding.reason());
        }
        List<CandidateHit> hits = search.knn(embedding.value().get(0), fusion.getTopN().getCandidates() * 2);
        Map<String, Double> scores = new HashMap<>();
        for (CandidateHit hit : hits) {
            scores.put(hit.capabilityId(), cosineFromOpenSearchScore(hit.rawScore()));
        }
        return new Semantic(scores, null);
    }

    /**
     * 把 OpenSearch 的 cosinesimil 分数还原成余弦值。
     *
     * <p>OpenSearch 返回的是 {@code (1 + cos) / 2}，取值恒在 0.5 上下——不相关的文本也能拿到
     * 0.4~0.5。直接拿它当语义分，阈值就失去区分力。还原成余弦并在 0 处截断后，
     * 不相关文本得 0，相关文本得 0.3~0.9，阈值才有意义。
     */
    private static double cosineFromOpenSearchScore(double score) {
        return Math.max(0.0, 2.0 * score - 1.0);
    }

    /**
     * BM25 分数归一化。
     *
     * <p>用饱和函数 {@code s/(s+k)} 而不是除以本次最大分：后者会让 Top1 恒为 1.0，
     * 使 {@code top1Min} 绝对阈值永远满足，弱匹配也能直出。
     */
    private static Map<String, Double> normalizeBm25(List<CandidateHit> hits, double saturation) {
        Map<String, Double> scores = new HashMap<>();
        for (CandidateHit hit : hits) {
            double s = hit.rawScore();
            scores.put(hit.capabilityId(), s / (s + saturation));
        }
        return scores;
    }

    private Fused fuse(RuleRecall.Result rules,
                       Map<String, Double> bm25,
                       Map<String, Double> semantic,
                       NegativeFilter.Result negatives,
                       FusionConfig fusion,
                       List<String> degraded) {
        Weights weights = Weights.redistribute(fusion.getWeights(), degraded);

        Map<String, Boolean> ids = new LinkedHashMap<>();
        rules.scores().keySet().forEach(id -> ids.put(id, true));
        bm25.keySet().forEach(id -> ids.put(id, true));
        semantic.keySet().forEach(id -> ids.put(id, true));

        List<Scored> scored = new ArrayList<>();
        for (String id : ids.keySet()) {
            CapabilityCard card = bundle.capability(id);
            if (card == null) {
                // 索引里有但资产里没有，说明索引比资产旧。宁可少召回，不能拿旧卡去执行
                continue;
            }
            double ruleScore = rules.scoreOf(id);
            double bm25Score = bm25.getOrDefault(id, 0.0);
            double semanticScore = semantic.getOrDefault(id, 0.0);
            double penalty = negatives.penaltyOf(id);

            double total = weights.semantic() * semanticScore
                    + weights.bm25() * bm25Score
                    + weights.rule() * ruleScore
                    - penalty;

            List<String> evidence = new ArrayList<>(rules.evidenceOf(id));
            evidence.addAll(negatives.reasonsOf(id));
            if (semanticScore > 0) {
                evidence.add(String.format("semantic:%.3f", semanticScore));
            }
            if (bm25Score > 0) {
                evidence.add(String.format("bm25:%.3f", bm25Score));
            }

            scored.add(new Scored(card, Math.max(0.0, total),
                    new RecallResult.Scores(semanticScore, ruleScore, 0.0, penalty), evidence));
        }

        scored.sort(Comparator.comparingDouble(Scored::fused).reversed());
        List<Scored> top = scored.subList(0, Math.min(scored.size(), fusion.getTopN().getCandidates()));

        List<RecallResult.Candidate> candidates = new ArrayList<>(top.size());
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        for (Scored s : top) {
            candidates.add(new RecallResult.Candidate(
                    s.card().capabilityId(),
                    Enums.CandidateType.valueOf(s.card().type().name()),
                    s.card().domains(),
                    s.scores(),
                    s.evidence(),
                    s.card().requiredSlots(),
                    s.card().riskLevel(),
                    Map.of("capabilityVersion", s.card().version(),
                           "assetVersion", bundle.assetVersion())));
            fusedScores.put(s.card().capabilityId(), s.fused());
        }
        return new Fused(candidates, fusedScores);
    }

    /**
     * 融合后的候选再过一次重排模型（FP-1H）。
     *
     * <p>开关在 {@code fusion.channels.rerankEnabled}，默认关。关着时本方法是空操作，
     * 不产生网关往返。开着时多一次 {@code rerank} 调用——次数硬门已废（ADR-003），
     * 是否启用看延迟与效果，不靠「第 3 次是否违约」否决。
     *
     * <p>重排不可用时**保留融合序并记降级**，不整条召回失败：融合结果仍在，
     * 丢掉它等于「模型挂了就没候选」，而重排本就是可选增强。
     *
     * <p>喂给重排的查询用原话（{@link RewriteResult#semanticText()}），文档用能力名 + 描述：
     * 与语义召回同一口径，改写表是为字面匹配服务的。
     */
    private Fused maybeRerank(RewriteResult rewrite, Fused fused, FusionConfig fusion,
                              List<String> degraded) {
        if (!fusion.getChannels().isRerankEnabled() || fused.candidates().isEmpty()) {
            return fused;
        }

        List<RecallResult.Candidate> before = fused.candidates();
        List<String> documents = new ArrayList<>(before.size());
        for (RecallResult.Candidate candidate : before) {
            documents.add(documentOf(bundle.capability(candidate.candidateId())));
        }

        GatewayResult<List<RerankHit>> result =
                gateway.rerank(rewrite.semanticText(), documents, before.size());
        if (!result.available() || result.value() == null || result.value().isEmpty()) {
            degraded.add("rerank");
            meterRegistry.counter(AgentMetrics.DEGRADED,
                    AgentMetrics.TAG_COMPONENT, "recall",
                    AgentMetrics.TAG_REASON, "rerank-unavailable").increment();
            return fused;
        }

        // 按重排分降序重排；下标越界的命中丢掉（模型偶发返回脏下标时，宁少一个也不乱序）
        List<RerankHit> hits = new ArrayList<>(result.value());
        hits.sort(Comparator.comparingDouble(RerankHit::relevanceScore).reversed());

        List<RecallResult.Candidate> reordered = new ArrayList<>(before.size());
        Map<String, Double> scores = new LinkedHashMap<>();
        boolean[] seen = new boolean[before.size()];
        for (RerankHit hit : hits) {
            int index = hit.index();
            if (index < 0 || index >= before.size() || seen[index]) {
                continue;
            }
            seen[index] = true;
            RecallResult.Candidate original = before.get(index);
            List<String> evidence = new ArrayList<>(original.matchedEvidence());
            evidence.add(String.format("rerank:%.3f", hit.relevanceScore()));
            reordered.add(new RecallResult.Candidate(
                    original.candidateId(),
                    original.candidateType(),
                    original.domains(),
                    original.scores(),
                    evidence,
                    original.requiredSlots(),
                    original.riskLevel(),
                    original.sourceVersions()));
            scores.put(original.candidateId(), hit.relevanceScore());
        }
        // 模型没覆盖到的候选（或脏下标导致漏掉的）按原融合序缀在后面，分数保留融合分
        for (int i = 0; i < before.size(); i++) {
            if (seen[i]) {
                continue;
            }
            RecallResult.Candidate leftover = before.get(i);
            reordered.add(leftover);
            scores.putIfAbsent(leftover.candidateId(),
                    fused.scores().getOrDefault(leftover.candidateId(), 0.0));
        }
        return new Fused(reordered, scores);
    }

    /** 重排文档：能力名 + 描述。缺描述时退回名称，空卡不应进召回，这里只是防 NPE。 */
    private static String documentOf(CapabilityCard card) {
        if (card == null) {
            return "";
        }
        String name = card.name() == null ? "" : card.name();
        String description = card.description() == null ? "" : card.description();
        if (description.isBlank()) {
            return name;
        }
        if (name.isBlank()) {
            return description;
        }
        return name + "。" + description;
    }

    private static int countSignificant(Fused fused, FusionConfig fusion) {
        return (int) fused.scores().values().stream()
                .filter(v -> v >= fusion.getThresholds().getTop1Min())
                .count();
    }

    private static RecallResult.DomainRouting routing(Fused fused, FusionConfig fusion,
                                                      MultiTaskDetector.Signal multiTask,
                                                      boolean forceCompareMulti) {
        Map<String, Double> byDomain = new LinkedHashMap<>();
        Map<String, String> bestCapabilityOf = new LinkedHashMap<>();
        for (RecallResult.Candidate c : fused.candidates()) {
            double score = fused.scores().getOrDefault(c.candidateId(), 0.0);
            for (String domain : c.domains()) {
                Double previous = byDomain.get(domain);
                if (previous == null || score > previous) {
                    byDomain.put(domain, score);
                    bestCapabilityOf.put(domain, c.candidateId());
                }
            }
        }

        List<RecallResult.DomainCandidate> domainCandidates = byDomain.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(fusion.getTopN().getDomains())
                .map(e -> new RecallResult.DomainCandidate(e.getKey(), e.getValue(), List.of()))
                .toList();

        Enums.RoutingMode mode;
        if (domainCandidates.isEmpty()) {
            mode = Enums.RoutingMode.UNKNOWN;
        } else if (multiTask.multiTask() || forceCompareMulti) {
            // 对比信号强制 MULTI：语义通道拉开两侧分数时也不能单域直出
            mode = Enums.RoutingMode.MULTI;
        } else if (domainCandidates.size() >= 2
                && domainCandidates.get(0).score() - domainCandidates.get(1).score()
                   < fusion.getThresholds().getDomainCloseMargin()
                && !sameCapability(domainCandidates, bestCapabilityOf)) {
            // 领域分数接近时保留 Top-K，不能先选唯一领域再检索（§3.2）
            mode = Enums.RoutingMode.MULTI;
        } else {
            mode = Enums.RoutingMode.SINGLE;
        }

        return new RecallResult.DomainRouting(mode, domainCandidates, mode == Enums.RoutingMode.MULTI);
    }

    /**
     * 前两个领域是不是同一张卡撑起来的。
     *
     * <p>「换卡」这张卡同时挂在 creditcard 和 account 两个领域下，两个领域的最高分必然相等。
     * 只看分数差会把它判成跨域，进而降级到慢路径——而它其实是一个再明确不过的单一意图。
     * 跨域的定义是「不同的卡分属不同领域且难分伯仲」，不是「一张卡覆盖多个领域」。
     */
    private static boolean sameCapability(List<RecallResult.DomainCandidate> domains,
                                          Map<String, String> bestCapabilityOf) {
        String first = bestCapabilityOf.get(domains.get(0).domain());
        String second = bestCapabilityOf.get(domains.get(1).domain());
        return first != null && first.equals(second);
    }

    /**
     * 按需拆解，供仲裁之后补救用。
     *
     * <p>召回阶段只在**自己**判定为多任务时才切：单句切出来没人会用，而切分要为每个片段
     * 再跑一遍规则召回，白花的是每一个请求的耗时。但模型可以独立判出 MULTI_INTENT——
     * 它读得懂「不足就别转」这类词表覆盖不到的表达。那时召回手上没有拆解结果，
     * 回复层就只能说一句「您提到了多件事」，说不出是哪几件。
     *
     * <p>切分是纯规则的，不产生模型往返，所以这条补救不占 A 线的往返预算。
     */
    public Optional<IntentPlan> split(String normalizedQuery) {
        return intentSplitter.split(normalizedQuery);
    }

    /** 跨域对比计划补救，语义同 {@link #split}。 */
    public Optional<IntentPlan> comparePlan(String normalizedQuery) {
        return comparePlanBuilder.plan(normalizedQuery);
    }

    /**
     * 召回输出。
     *
     * <p>{@code fusedScores} 单独返回而不塞进 {@code Candidate.scores}：附录 B 冻结的四个通道分里
     * 没有融合总分的位置，而仲裁的阈值判定要用它。契约不能随手加字段，
     * 所以在进程内的返回值里传递。
     *
     * @param result      契约形态的召回结果
     * @param multiTask   多任务信号，供仲裁 fail-safe 使用
     * @param intentPlan  多任务时的拆解结果。判定为多任务但切不开时为 null——
     *                    检测靠词表与召回证据，切分只有标点与连词，两者不可能永远一致
     * @param fusedScores 能力 ID → 融合总分，按降序
     */
    public record Output(RecallResult result, MultiTaskDetector.Signal multiTask,
                         IntentPlan intentPlan, Map<String, Double> fusedScores) {

        public double top1Score() {
            return fusedScores.values().stream().findFirst().orElse(0.0);
        }

        public double top2Score() {
            return fusedScores.values().stream().skip(1).findFirst().orElse(0.0);
        }

        public double margin() {
            return top1Score() - top2Score();
        }

        public RecallResult.Candidate top1() {
            return result.candidates().isEmpty() ? null : result.candidates().get(0);
        }
    }

    private record Fused(List<RecallResult.Candidate> candidates, Map<String, Double> scores) {
    }

    private record Semantic(Map<String, Double> scores, String degradedReason) {
    }

    private record Scored(CapabilityCard card, double fused, RecallResult.Scores scores,
                          List<String> evidence) {
    }

    /** 权重在通道降级时按剩余通道的原有比例重分配，保证总权重恒为 1。 */
    private record Weights(double semantic, double bm25, double rule) {

        static Weights redistribute(FusionConfig.Weights base, List<String> degraded) {
            double semantic = degraded.contains("semantic") ? 0 : base.getSemantic();
            double bm25 = degraded.contains("bm25") ? 0 : base.getBm25();
            double rule = degraded.contains("rule") ? 0 : base.getRule();
            double sum = semantic + bm25 + rule;
            if (sum <= 0) {
                // 所有通道都降级：给规则通道满权重。此时规则召回大概率也是空的，
                // 结果会是 NO_CANDIDATE——没有依据就不该给出口，这是正确的表现
                return new Weights(0, 0, 1.0);
            }
            return new Weights(semantic / sum, bm25 / sum, rule / sum);
        }
    }
}
