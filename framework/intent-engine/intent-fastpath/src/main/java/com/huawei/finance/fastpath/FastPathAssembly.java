package com.huawei.finance.fastpath;

import com.huawei.finance.common.event.EventClassifier;
import com.huawei.finance.common.event.IntentSignalProbe;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.fastpath.arbitration.FailSafeGuard;
import com.huawei.finance.fastpath.arbitration.ModelArbitrator;
import com.huawei.finance.fastpath.arbitration.RuleArbitrator;
import com.huawei.finance.fastpath.arbitration.SlotGate;
import com.huawei.finance.intent.cache.DecisionCache;
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
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.contracts.port.CandidateSearch;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 从一份资产装出一整条快路径。
 *
 * <p>抽成工厂而不是只写在 {@code @Bean} 方法里，是为了让「换一份资产」有第二个调用点：
 * 资产热重载时整条链要**重建**。链上十来个组件全在构造期就把阈值、词表、规则拆进了
 * final 字段，逐个改既做不到也不该做——那会在改到一半时留下一条半新半旧的链。
 *
 * <p>不含跨资产版本的共享物：{@link ChineseAnalyzer} 的 HanLP 词典是进程级资源，
 * {@link DecisionCache} 背后是 Redis 连接，两者都由调用方传进来复用。
 * 每次重载都新建它们会分别付出重新加载词典和重建连接池的代价。
 */
public final class FastPathAssembly {

    private FastPathAssembly() {
    }

    /**
     * @param infra 跨重载复用的基础设施
     */
    public static FastPathEngine build(AssetBundle bundle, Infrastructure infra) {
        ExpressionEvaluator evaluator = new ExpressionEvaluator();
        SlotGate slotGate = new SlotGate();

        QueryRewriter rewriter = new QueryRewriter(bundle.synonyms(), infra.analyzer());
        RuleRecall ruleRecall = new RuleRecall(bundle);
        IntentSignalProbe intentProbe = new RuleBasedIntentProbe(bundle, ruleRecall);
        NegativeFilter negativeFilter = NegativeFilter.of(bundle, evaluator);
        MultiTaskDetector multiTaskDetector = new MultiTaskDetector(bundle.fusion().getMultiTask());
        StrongRuleEngine strongRules = new StrongRuleEngine(bundle.strongRules(), evaluator);

        HybridRecall recall = new HybridRecall(bundle, ruleRecall, infra.search(), negativeFilter,
                multiTaskDetector, infra.gateway(), infra.modelProps(),
                infra.meterRegistry());

        RuleArbitrator ruleArbitrator = new RuleArbitrator(bundle, slotGate);
        ModelArbitrator modelArbitrator = new ModelArbitrator(infra.gateway(), infra.modelProps(),
                bundle, infra.validator(), infra.meterRegistry());
        FailSafeGuard failSafeGuard = new FailSafeGuard(bundle, slotGate);

        return new FastPathEngine(bundle, infra.eventClassifier(), intentProbe, rewriter,
                new SlotExtractor(bundle.clarify()), recall, strongRules, infra.cache(), ruleArbitrator,
                modelArbitrator, failSafeGuard, slotGate, infra.validator(), infra.modelProps(),
                infra.meterRegistry(), infra.decisionTrace(), infra.candidatePostProcessors());
    }

    /** 与资产无关、跨重载复用的那些依赖。 */
    public record Infrastructure(ChineseAnalyzer analyzer, CandidateSearch search,
                                 ModelGatewayClient gateway, ModelGatewayProperties modelProps,
                                 MeterRegistry meterRegistry,
                                 DecisionCache cache, ContractValidator validator,
                                 EventClassifier eventClassifier, DecisionTrace decisionTrace,
                                 java.util.List<com.huawei.finance.intent.extension.CandidatePostProcessor>
                                         candidatePostProcessors) {

        public Infrastructure {
            candidatePostProcessors = candidatePostProcessors == null
                    ? java.util.List.of() : java.util.List.copyOf(candidatePostProcessors);
        }
    }
}
