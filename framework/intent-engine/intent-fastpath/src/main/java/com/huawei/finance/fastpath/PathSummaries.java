package com.huawei.finance.fastpath;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.intent.PathSummary;
import com.huawei.finance.obs.trace.ScoredCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 把一次快路径执行的内部状态投影成 {@link PathSummary}。
 *
 * <p>这段逻辑原先是 {@code PathSummary} 自己的包内可见 {@code from(...)}。门面独立成
 * intent-engine-api 之后它留不下来：它读 {@link FastPathState}（引擎内部类型）与
 * {@link ScoredCandidate}（agent-obs），而门面模块刻意只依赖契约、公共类型与承诺面标注。
 * 摘要的**形状**是承诺面，**怎么算出来的**是实现细节——这个类就是那条线。
 *
 * <p>包内可见，不对外。调用点只有 {@link FastPathState}。
 */
final class PathSummaries {

    private static final int TOP_N = 5;

    private PathSummaries() {
    }

    static PathSummary from(FastPathState state, RouteDecision decision) {
        Map<String, Long> phaseMs = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : state.phaseNanos().entrySet()) {
            phaseMs.put(e.getKey(), TimeUnit.NANOSECONDS.toMillis(e.getValue()));
        }

        List<ScoredCandidate> ranked = state.rankedCandidates();
        List<PathSummary.CandidateBrief> top = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, ranked.size()); i++) {
            ScoredCandidate c = ranked.get(i);
            top.add(new PathSummary.CandidateBrief(c.candidateId(), round3(c.fusedScore()),
                    round3(c.semantic()), round3(c.rule()), round3(c.negative())));
        }

        String selectedId = decision.selectedCandidateId();
        Integer selectedRank = null;
        Boolean overruled = null;
        String runnerUp = null;
        if (!ranked.isEmpty()) {
            String top1 = ranked.get(0).candidateId();
            selectedRank = 0;
            for (int i = 0; i < ranked.size(); i++) {
                if (ranked.get(i).candidateId().equals(selectedId)) {
                    selectedRank = i + 1;
                    break;
                }
            }
            if (selectedId != null) {
                overruled = !top1.equals(selectedId);
            }
            if (ranked.size() >= 2) {
                runnerUp = top1.equals(selectedId)
                        ? ranked.get(1).candidateId()
                        : ranked.get(0).candidateId();
            }
        }

        String eventType = state.event() == null ? null : state.event().event().name();

        Double margin = null;
        if (ranked.size() >= 2) {
            margin = round3(ranked.get(0).fusedScore() - ranked.get(1).fusedScore());
        }

        PathSummary.PipelineDetail pipeline = null;
        if (state.rewrite() != null) {
            var rewrite = state.rewrite();
            Map<String, Object> slots = state.slots();
            if (slots == null || slots.isEmpty()) {
                slots = state.turnSlots() == null ? Map.of() : state.turnSlots();
            }
            pipeline = new PathSummary.PipelineDetail(
                    rewrite.original(),
                    state.request().contextualQuery() == null
                            ? rewrite.original() : state.request().contextualQuery().standaloneQuery(),
                    rewrite.normalized(),
                    rewrite.searchText(),
                    rewrite.semanticText(),
                    rewrite.terms(),
                    slots,
                    state.request().contextualQuery() == null
                            ? List.of() : state.request().contextualQuery().usedContextRefs(),
                    state.request().contextualQuery() == null
                            ? List.of() : state.request().contextualQuery().unusedContextRefs(),
                    state.request().contextualQuery() == null
                            ? null : state.request().contextualQuery().stateVersion(),
                    state.request().contextualQuery() == null
                            ? null : state.request().contextualQuery().eventType().name());
        }

        return new PathSummary(
                exitPath(decision),
                arbitratedBy(decision),
                phaseMs,
                top,
                selectedRank,
                overruled,
                runnerUp,
                decision.missingSlots() == null ? List.of() : decision.missingSlots(),
                eventType,
                blankToNull(decision.modelVersion()),
                blankToNull(decision.promptVersion()),
                margin,
                pipeline);
    }

    /** 把契约哨兵 {@code none} / 空串收成 null，前端少一层特判。 */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || RouteDecision.VERSION_NONE.equals(value)) {
            return null;
        }
        return value;
    }

    private static String exitPath(RouteDecision decision) {
        ShortCircuitLevel level = decision.shortCircuit();
        if (level == null || level == ShortCircuitLevel.NONE || level == ShortCircuitLevel.L3_MODEL) {
            return "RECALL_ARBITRATION";
        }
        return switch (level) {
            case CONTINUATION -> "CONTINUATION";
            case L1_CACHE -> "L1_CACHE";
            case L2_STRONG_RULE -> "L2_STRONG_RULE";
            case STANDARD_ANSWER_RULE -> "STANDARD_ANSWER";
            default -> level.name();
        };
    }

    private static String arbitratedBy(RouteDecision decision) {
        ShortCircuitLevel level = decision.shortCircuit();
        // L3_MODEL 表示真的走过模型仲裁，不是缓存/强规则那种短路；
        // 不能和 CONTINUATION / L1 / L2 挤在同一个 SHORT_CIRCUIT 桶里。
        if (level != null && level != ShortCircuitLevel.NONE && level != ShortCircuitLevel.L3_MODEL) {
            return "SHORT_CIRCUIT";
        }
        return decision.decidedByModel() ? "MODEL" : "RULE_FALLBACK";
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
