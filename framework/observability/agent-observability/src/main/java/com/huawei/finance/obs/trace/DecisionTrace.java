package com.huawei.finance.obs.trace;

import com.huawei.finance.stability.Spi;
import java.util.List;

/**
 * 决策链路的 trace 记录器（FP-62）。
 *
 * <p>解决的问题是：只记最终出口，事后无法回答「它当时差点选了谁」。出口分布看板能告诉你
 * 今天 CLARIFY 涨了三个点，但涨的那批请求当时召回到了什么、各通道给了多少分、
 * 融合后第一名和第二名差多少——一概查不到，于是排查只能靠猜或者线上复现。
 *
 * <p>方法刻意分成「仲裁前」「仲裁后」两次调用而不是一次交上全部：短路路径（缓存命中、
 * 强规则直出、续轮）根本没有候选集，它们只调后者。若合成一个方法，短路时就得传
 * 空候选集，而 span 上「没有候选集」和「候选集为空」是两件不同的事——
 * 前者是短路，后者是召回全军覆没。
 */
@Spi
public interface DecisionTrace {

    interface PhaseSpan extends AutoCloseable {
        PhaseSpan NOOP = new PhaseSpan() {
            @Override public void error(Throwable error) { }
            @Override public void close() { }
        };
        void error(Throwable error);
        @Override void close();
    }

    interface TraceScope extends AutoCloseable {
        TraceScope NOOP = () -> { };
        @Override void close();
    }

    /**
     * 记录仲裁前的候选集与打分。
     *
     * @param candidates      候选，需已按融合分降序。记录几条由 {@link DecisionTracePolicy} 定
     * @param totalCandidates 召回到的候选总数。与实际记录条数分开给，
     *                        否则看板上无法区分「只召回到 3 条」与「召回 40 条只记了 3 条」
     * @param degradedChannels 被摘除的召回通道（如 embedding 失败时的 {@code semantic}）。
     *                        打分异常时必须先看这个：通道少一个，融合分整体偏移，
     *                        不知情的话会误判成模型或权重出了问题
     */
    void recordCandidates(List<ScoredCandidate> candidates, int totalCandidates, List<String> degradedChannels);

    /**
     * 记录仲裁结果。
     *
     * @param decision    四出口
     * @param reasonCode  出口原因
     * @param shortCircuit 短路层级，{@code NONE} 表示真的算到了仲裁
     * @param selectedId  选中的能力标识，可为 null（拒绝或多意图时没有单一选择）
     * @param confidence  置信度
     * @param arbitratedBy 由谁定的：{@code MODEL}、{@code RULE_FALLBACK}、{@code SHORT_CIRCUIT}。
     *                    模型仲裁与规则回退产出的出口形态相同，事后光看出口分不出来，
     *                    而「今天的 CLARIFY 是模型判的还是模型挂了规则兜的」是两个处置方向
     */
    void recordDecision(String decision, String reasonCode, String shortCircuit,
                        String selectedId, double confidence, String arbitratedBy);

    /**
     * 记下本次决策所用的模型与提示词版本（FP-63）。
     *
     * <p>default 空实现：{@code @Spi} 不得往里加抽象方法，否则行内实现升级时集体编译不过。
     * 缺这两个 tag 时 APM 里「慢在哪一版模型」筛不出来，是可见的缺失，不是静默错误。
     */
    default void recordDecisionVersions(String modelVersion, String promptVersion) {
    }

    /**
     * 记录一个阶段的耗时。
     *
     * @param phase  阶段名，见 {@code AgentMetrics.TAG_PHASE}
     * @param nanos  耗时纳秒
     */
    void recordPhase(String phase, long nanos);

    /** Starts a child span at the real execution boundary for a decision phase. */
    default PhaseSpan startPhaseSpan(String phase) {
        return PhaseSpan.NOOP;
    }

    /** Captures the current parent span while an asynchronous decision graph is running. */
    default TraceScope bindParent(String traceId) {
        return TraceScope.NOOP;
    }

    /** Starts a phase span using the parent captured for this trace when execution changed threads. */
    default PhaseSpan startPhaseSpan(String phase, String traceId) {
        return startPhaseSpan(phase);
    }

    /**
     * 记录仲裁前后的候选集对照（FP-62，形态参照 §2.7.1 的
     * {@code beforeDecisionDomainList / afterDecisionDomainList}）。
     *
     * <p>{@link #recordCandidates} 记的是「当时有哪些候选」，{@link #recordDecision} 记的是
     * 「最后选了谁」。缺的是把两者接起来的那一句：**选中的那个在检索排名里是第几**。
     * 这个数区分开了两类完全不同的事故：模型选了检索的第二名（融合排序与模型判断分歧，
     * 该查权重与阈值），还是模型选了一个根本没进候选的能力（模型越界，该查提示词与输出校验）。
     * 只看出口分布，这两类都表现为「出口不对」。
     *
     * <p>是 default 方法而非抽象方法：{@code @Spi} 承诺过基线不往里加抽象方法，
     * 加了会让所有行内实现在升级时一起编译不过。代价是旧实现升级后拿不到这份对照，
     * 而那是可见的缺失（span 上少几个 tag），不是静默的错误。
     *
     * @param beforeArbitration 仲裁前的候选排名，需与传给 {@link #recordCandidates} 的是同一份
     * @param selectedId        仲裁选中的能力，可为 null（拒绝或多意图时没有单一选择）
     */
    default void recordArbitrationComparison(List<ScoredCandidate> beforeArbitration, String selectedId) {
    }

    /** 什么都不记。库模块单测与没接 APM 的环境用它，避免调用方到处判空。 */
    DecisionTrace NOOP = new DecisionTrace() {
        @Override
        public void recordCandidates(List<ScoredCandidate> candidates, int totalCandidates,
                                     List<String> degradedChannels) {
        }

        @Override
        public void recordDecision(String decision, String reasonCode, String shortCircuit,
                                   String selectedId, double confidence, String arbitratedBy) {
        }

        @Override
        public void recordPhase(String phase, long nanos) {
        }
    };
}
