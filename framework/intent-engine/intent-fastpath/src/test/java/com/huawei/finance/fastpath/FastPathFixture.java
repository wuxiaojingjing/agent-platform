package com.huawei.finance.fastpath;

import com.huawei.finance.common.event.EventClassifier;
import com.huawei.finance.common.event.EventClassifierProperties;
import com.huawei.finance.common.event.IntentSignalProbe;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.fastpath.arbitration.FailSafeGuard;
import com.huawei.finance.fastpath.arbitration.ModelArbitrator;
import com.huawei.finance.fastpath.arbitration.RuleArbitrator;
import com.huawei.finance.fastpath.arbitration.SlotGate;
import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.fastpath.event.RuleBasedIntentProbe;
import com.huawei.finance.fastpath.recall.HybridRecall;
import com.huawei.finance.fastpath.recall.MultiTaskDetector;
import com.huawei.finance.fastpath.recall.NegativeFilter;
import com.huawei.finance.fastpath.recall.RuleRecall;
import com.huawei.finance.fastpath.rewrite.ChineseAnalyzer;
import com.huawei.finance.fastpath.rewrite.QueryRewriter;
import com.huawei.finance.fastpath.rewrite.SlotExtractor;
import com.huawei.finance.fastpath.rule.ExpressionEvaluator;
import com.huawei.finance.fastpath.rule.StrongRuleEngine;
import com.huawei.finance.gateway.BudgetAwareModelGateway;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.obs.trace.ScoredCandidate;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.contracts.port.CandidateHit;
import com.huawei.finance.contracts.port.CandidateSearch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单测用的快路径装配。
 *
 * <p>接的是**真实资产**，只把两个外部依赖换成替身：模型网关（返回不可用，触发规则仲裁回退）
 * 与出口缓存（内存实现）。用假能力卡跑出来的四出口判定没有说服力——
 * 真正要验证的正是这批线上要用的卡与规则能不能分流到位。
 *
 * <p>检索默认不可用（{@link CandidateSearch#searchable()} 为假），权重按比例落到规则通道。
 * 这同时顺带验证了「检索挂了快路径仍能工作」。
 */
final class FastPathFixture {

    /** HanLP 词典加载以秒计，全体用例共用一个实例，否则每个用例都要重新加载一遍。 */
    private static final ChineseAnalyzer ANALYZER = new ChineseAnalyzer();

    /**
     * 钉死的「今天」，供仲裁 prompt 的日期基准表使用。
     *
     * <p>取 3 月 15 日是为了让「上月」落在 2 月：月末那一天是 28 号还是 29 号要真算，
     * 用一个 31 天的月份做基准，算错了也看不出来。用真实时钟则更糟——用例会在跨月那天红一次，
     * 而人第一反应是「测试不稳定」。
     */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private FastPathFixture() {
    }

    static AssetBundle assets() {
        return new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    static Built build(ModelGatewayClient rawGateway) {
        return build(rawGateway, CandidateSearch.unavailable());
    }

    /**
     * 语义通道打开的装配：索引标为就绪且含向量，检索本身返回空。
     *
     * <p>用于验证「两条通道都开时到底打了几次网关」——默认装配里检索不可用，
     * embedding 根本不会被调用，那种状态下断言往返数等于只断言了降级路径。
     * 检索返回空不影响这件事：{@code semanticRecall} 是先调 embedding 再拿向量去查，
     * 往返在查之前就已经发生。
     */
    static Built buildWithSemanticChannel(ModelGatewayClient rawGateway) {
        return buildWithBm25Hits(rawGateway, List.of());
    }

    /**
     * 语义通道打开，且 BM25 固定命中指定的能力。
     *
     * <p>用于需要「候选不止一个」的场景。默认装配只有规则通道，多数查询只召回一个能力，
     * 在那上面验「候选裁剪」会静默变成空跑——断言照过，但被测分支从未执行。
     *
     * @param capabilityIds 必须是 assets 里真实存在的能力，否则融合阶段查不到卡
     */
    static Built buildWithBm25Hits(ModelGatewayClient rawGateway, List<String> capabilityIds) {
        return buildWithBm25Hits(rawGateway, capabilityIds, false);
    }

    /**
     * @param tied 命中项是否同分。跨域判定问的是「两个领域的最高分差是否小于
     *             {@code domainCloseMargin}」，用递减分永远造不出这个局面，
     *             那样「跨域该判 MULTI」的用例会静默地只覆盖 SINGLE 分支
     */
    static Built buildWithBm25Hits(ModelGatewayClient rawGateway, List<String> capabilityIds,
                                   boolean tied) {
        return build(rawGateway, new StubCandidateSearch(capabilityIds, tied));
    }

    /**
     * 装配入口。{@code search} 传 {@link CandidateSearch#unavailable()} 表示不接检索。
     *
     * <p>包内可见而非私有，是为了让 {@link FastPathLiveFixture} 能塞进真的检索实现。
     */
    static Built build(ModelGatewayClient rawGateway, CandidateSearch search) {
        return build(assets(), rawGateway, search, List.of());
    }

    static Built build(ModelGatewayClient rawGateway, CandidateSearch search,
                       List<CandidatePostProcessor> candidatePostProcessors) {
        return build(assets(), rawGateway, search, candidatePostProcessors);
    }

    /**
     * 换掉某一块资产的装配。
     *
     * <p>用于生产资产刻意留空的那几块——标准问答库与合规话题都归业务填，工程不得塞样例进去
     * （见 {@code assets/standard-qa.yaml} 的说明）。用例要验这条链路，就得在这里造资产，
     * 而不是往生产目录里加两条「示例」。
     */
    static Built build(AssetBundle bundle, ModelGatewayClient rawGateway, CandidateSearch search) {
        return build(bundle, rawGateway, search, List.of());
    }

    static Built build(AssetBundle bundle, ModelGatewayClient rawGateway, CandidateSearch search,
                       List<CandidatePostProcessor> candidatePostProcessors) {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        ContractValidator validator = new ContractValidator();
        ModelGatewayProperties modelProps = new ModelGatewayProperties();

        // 与生产装配一致地套上往返预算装饰器。不套的话假网关不计数，
        // 往返序列就还是没有任何用例守着
        ModelGatewayClient gateway = new BudgetAwareModelGateway(rawGateway, meterRegistry);

        ExpressionEvaluator evaluator = new ExpressionEvaluator();
        RuleRecall ruleRecall = new RuleRecall(bundle);

        HybridRecall recall = new HybridRecall(bundle, ruleRecall, search,
                NegativeFilter.of(bundle, evaluator),
                new MultiTaskDetector(bundle.fusion().getMultiTask()),
                gateway, modelProps, meterRegistry);

        SlotGate slotGate = new SlotGate();
        RecordingCache cache = new RecordingCache();
        IntentSignalProbe probe = new RuleBasedIntentProbe(bundle, ruleRecall);
        RecordingDecisionTrace trace = new RecordingDecisionTrace();

        FastPathEngine engine = new FastPathEngine(
                bundle,
                new EventClassifier(new EventClassifierProperties()),
                probe,
                new QueryRewriter(bundle.synonyms(), ANALYZER),
                new SlotExtractor(bundle.clarify()),
                recall,
                new StrongRuleEngine(bundle.strongRules(), evaluator),
                cache,
                new RuleArbitrator(bundle, slotGate),
                new ModelArbitrator(gateway, modelProps, bundle, validator, meterRegistry, CLOCK),
                new FailSafeGuard(bundle, slotGate),
                slotGate,
                validator,
                modelProps,
                meterRegistry,
                trace,
                candidatePostProcessors);

        return new Built(engine, bundle, cache, meterRegistry, modelProps, trace);
    }

    static Built build() {
        return build(new UnavailableGateway());
    }

    record Built(FastPathEngine engine, AssetBundle bundle, RecordingCache cache,
                 MeterRegistry meterRegistry, ModelGatewayProperties modelProps,
                 RecordingDecisionTrace trace) {
    }

    /**
     * 把 FP-62 交出去的内容留在内存里，供用例断言。
     *
     * <p>不用 mock 而是自己记：要断言的是「引擎交了什么」，而 mock 的调用记录断言写出来
     * 读的是调用形状，不是内容。这里存下的是内容本身，用例可以直接问「第二名是谁」。
     */
    static final class RecordingDecisionTrace implements DecisionTrace {
        final List<List<ScoredCandidate>> candidateRounds = new ArrayList<>();
        final List<String> degradedChannels = new ArrayList<>();
        final Map<String, Long> phaseNanos = new LinkedHashMap<>();
        String decision;
        String reasonCode;
        String shortCircuit;
        String selectedId;
        String arbitratedBy;
        double confidence;

        @Override
        public void recordCandidates(List<ScoredCandidate> candidates, int totalCandidates,
                                     List<String> degraded) {
            candidateRounds.add(List.copyOf(candidates));
            if (degraded != null) {
                degradedChannels.addAll(degraded);
            }
        }

        @Override
        public void recordDecision(String decision, String reasonCode, String shortCircuit,
                                   String selectedId, double confidence, String arbitratedBy) {
            this.decision = decision;
            this.reasonCode = reasonCode;
            this.shortCircuit = shortCircuit;
            this.selectedId = selectedId;
            this.confidence = confidence;
            this.arbitratedBy = arbitratedBy;
        }

        @Override
        public void recordPhase(String phase, long nanos) {
            phaseNanos.put(phase, nanos);
        }

        /** 用一个计数区分「交了一份空的」与「压根没调」——短路路径属于后者。 */
        int comparisonRounds;
        List<ScoredCandidate> comparisonBefore = List.of();
        String comparisonSelected;

        @Override
        public void recordArbitrationComparison(List<ScoredCandidate> before, String selectedId) {
            comparisonRounds++;
            comparisonBefore = before == null ? List.of() : List.copyOf(before);
            comparisonSelected = selectedId;
        }

        /** 最后一轮候选集。短路路径下为空列表——那时引擎压根没调 recordCandidates。 */
        List<ScoredCandidate> lastCandidates() {
            return candidateRounds.isEmpty() ? List.of() : candidateRounds.get(candidateRounds.size() - 1);
        }
    }

    /** 记录读写次数的内存缓存，用于验证澄清重试确实绕过了一级缓存。 */
    static final class RecordingCache implements DecisionCache {

        private final Map<String, RouteDecision> store = new HashMap<>();
        int reads;
        int writes;

        @Override
        public Optional<RouteDecision> get(String key) {
            reads++;
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(String key, RouteDecision decision) {
            writes++;
            store.put(key, decision);
        }

        Map<String, RouteDecision> store() {
            return store;
        }
    }

    /** 不接 OpenSearch 的检索替身：BM25 返回预设命中，kNN 返回空，通道标为可用。 */
    static final class StubCandidateSearch implements CandidateSearch {

        private final List<String> capabilityIds;
        private final boolean tied;

        StubCandidateSearch(List<String> capabilityIds, boolean tied) {
            this.capabilityIds = capabilityIds;
            this.tied = tied;
        }

        @Override
        public List<CandidateHit> bm25(String query, List<String> terms, int size) {
            List<CandidateHit> hits = new ArrayList<>();
            double score = 10.0;
            for (String id : capabilityIds) {
                hits.add(new CandidateHit(id, score));
                if (!tied) {
                    score -= 1.0;
                }
            }
            return hits.size() <= size ? hits : hits.subList(0, size);
        }

        @Override
        public List<CandidateHit> knn(float[] vector, int k) {
            return List.of();
        }

        @Override
        public boolean searchable() {
            return true;
        }

        @Override
        public boolean semanticAvailable() {
            return true;
        }
    }

    /** 本地无模型网关时的表现：一律不可用，快路径必须能靠规则仲裁走完。 */
    static final class UnavailableGateway implements ModelGatewayClient {

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
