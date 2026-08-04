package com.huawei.finance.intent;

import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/**
 * 一次意图判定的执行路径摘要。
 *
 * <p>与 {@code com.huawei.finance.obs.trace.DecisionTrace} 记到 OTEL span 上的是同一批信息，
 * 投影到可进控制台 recent 的形态——控制台读不到 span，也不该再去调 Jaeger。
 *
 * <p>它跟着 {@link IntentResult#path()} 一起住在门面模块，不是随手放的：
 * {@code StabilityBoundaryTest} 要求 {@code @Api} 的公开签名里不出现未标注的 agent-platform 类型，
 * 所以 {@code path()} 的返回类型必须也是承诺面的一部分，也就必须在依赖方拿得到的模块里。
 *
 * <p><b>这里只有形状，没有投影逻辑。</b>「从引擎内部状态算出这份摘要」那段
 * （原先的包内可见 {@code from(FastPathState, RouteDecision)} 及其私有辅助）
 * 搬去了 {@code com.huawei.finance.fastpath.PathSummaries}：它要读 {@code FastPathState} 与
 * {@code ScoredCandidate}，前者是引擎内部类型，后者来自 agent-obs。
 * 留在这里的话，本模块就得依赖 agent-obs，而每个只想调门面的 Agent 都要跟着背上它。
 *
 * @param exitPath         CONTINUATION / L1_CACHE / L2_STRONG_RULE / STANDARD_ANSWER / RECALL_ARBITRATION
 * @param arbitratedBy     MODEL / RULE_FALLBACK / SHORT_CIRCUIT
 * @param phaseMs          rewrite / recall / arbitration 毫秒
 * @param topCandidates    融合分前若干条
 * @param selectedRank     选中能力在召回中的名次（1-based）；不在候选里为 0；短路为 null
 * @param overruledTop1    是否推翻召回第一名
 * @param runnerUpId       对照用的亚军
 * @param missingSlots     出口缺槽
 * @param eventType        事件分类
 * @param modelVersion     仲裁模型版本（对应 span {@code huawei.finance.agent.decision.model_version}）；投影时已把契约哨兵收成 null，前端见 null 即当未调模型
 * @param promptVersion    仲裁提示词版本（对应 span {@code huawei.finance.agent.decision.prompt_version}）
 * @param margin           头两名融合分差（对应 span {@code huawei.finance.agent.recall.margin}）；不足两条候选为 null
 * @param pipeline         改写 / 槽位等阶段输入输出，供控制台观测；短路路径也可能有改写结果
 */
@Api
public record PathSummary(
        String exitPath,
        String arbitratedBy,
        Map<String, Long> phaseMs,
        List<CandidateBrief> topCandidates,
        Integer selectedRank,
        Boolean overruledTop1,
        String runnerUpId,
        List<String> missingSlots,
        String eventType,
        String modelVersion,
        String promptVersion,
        Double margin,
        PipelineDetail pipeline) {

    public PathSummary {
        phaseMs = phaseMs == null ? Map.of() : Map.copyOf(phaseMs);
        topCandidates = topCandidates == null ? List.of() : List.copyOf(topCandidates);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    public static PathSummary empty() {
        return new PathSummary("UNKNOWN", "SHORT_CIRCUIT", Map.of(), List.of(),
                null, null, null, List.of(), null, null, null, null, null);
    }

    public record CandidateBrief(
            String candidateId,
            double fusedScore,
            double semantic,
            double rule,
            double negative) {
    }

    /**
     * 一次意图判定里观测最常要看的文本形态。
     *
     * <p>字段语义对齐快路径改写：{@code normalized} 给规则/缓存键，{@code searchText} 给 BM25，
     * {@code semanticText} 给向量与仲裁（通常接近原话，不经同义替换）。
     */
    public record PipelineDetail(
            String originalQuery,
            String standaloneQuery,
            String normalizedQuery,
            String searchText,
            String semanticText,
            List<String> terms,
            Map<String, Object> slots,
            List<String> usedContextRefs,
            List<String> unusedContextRefs,
            Long contextStateVersion,
            String contextualEventType) {

        public PipelineDetail {
            terms = terms == null ? List.of() : List.copyOf(terms);
            slots = slots == null ? Map.of() : Map.copyOf(slots);
            usedContextRefs = usedContextRefs == null ? List.of() : List.copyOf(usedContextRefs);
            unusedContextRefs = unusedContextRefs == null ? List.of() : List.copyOf(unusedContextRefs);
        }

        public PipelineDetail(String originalQuery, String normalizedQuery, String searchText,
                              String semanticText, List<String> terms, Map<String, Object> slots) {
            this(originalQuery, semanticText, normalizedQuery, searchText, semanticText,
                    terms, slots, List.of(), List.of(), null, null);
        }
    }
}
