package com.huawei.finance.obs.trace;

import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 把决策链路写到当前 span，同时把分段耗时记成指标。
 *
 * <p>一个实现同时落 trace 与 metrics 是有意的：分段耗时两处都要。看板与告警只能吃指标
 * （trace 是抽样的，按 span 属性算 P99 会得到一个抽样偏差未知的数），而排查具体某一次
 * 慢请求只能看 span。少任何一头，「今天 P99 涨了」与「这一笔为什么慢」就有一个答不上来。
 *
 * <p>{@code Tracer} 允许为 null：库模块单测与未接 APM 的部署都没有 Tracer Bean，
 * 那时只落指标、不落 trace。这里判空而不是让调用方判，是因为漏判的后果是
 * 上下文启动即失败——Boot 4 升级时 {@code ChatService} 就因为 Tracer 没 Bean 炸过一次，
 * 而那种失败只在完整上下文启动时暴露，库模块的单测一个都不会红。
 */
public class MicrometerDecisionTrace implements DecisionTrace {

    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final DecisionTracePolicy policy;
    private final ConcurrentMap<String, Span> asynchronousParents = new ConcurrentHashMap<>();

    public MicrometerDecisionTrace(Tracer tracer, MeterRegistry meterRegistry, DecisionTracePolicy policy) {
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.policy = policy;
    }

    @Override
    public void recordCandidates(List<ScoredCandidate> candidates, int totalCandidates,
                                 List<String> degradedChannels) {
        Span span = currentSpan();
        if (span == null) {
            return;
        }
        span.tag("huawei.finance.agent.recall.total", String.valueOf(totalCandidates));
        if (degradedChannels != null && !degradedChannels.isEmpty()) {
            // 只在非空时打：通道齐全是常态，给常态也打一个 "" 会让「有没有摘通道」
            // 这个筛选条件在 APM 里失效
            span.tag("huawei.finance.agent.recall.degraded_channels", String.join(",", degradedChannels));
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        // 头两名的分差。排序事故里这个数比任何单条打分都直接：差值贴近 0 说明
        // 融合根本没能把两条候选分开，那时选中谁基本是打分噪声决定的
        if (candidates.size() >= 2) {
            double margin = candidates.get(0).fusedScore() - candidates.get(1).fusedScore();
            span.tag("huawei.finance.agent.recall.margin", fmt(margin));
        }

        List<ScoredCandidate> traced = select(candidates);
        if (!traced.isEmpty()) {
            span.tag("huawei.finance.agent.recall.candidates", render(traced));
        }
    }

    /**
     * 挑出要写进 span 的候选：融合分前 N，再补上被负向压过、却掉出前 N 的那些。
     *
     * <p>补被压掉的那批是关键。「明明该选它却没选」多半是负向规则打压过头，
     * 而它一旦被压到榜外，只记前 N 就等于把第一嫌疑人从现场删掉了——
     * 事后再看只剩「没召回到它」这一个假象，而这两种原因的修法完全不同。
     */
    private List<ScoredCandidate> select(List<ScoredCandidate> candidates) {
        int cap = policy.maxCandidates();
        if (cap <= 0) {
            return List.of();
        }
        Set<ScoredCandidate> picked = new LinkedHashSet<>(candidates.subList(0, Math.min(cap, candidates.size())));
        if (policy.includeSuppressed()) {
            int extra = 0;
            for (ScoredCandidate c : candidates) {
                if (extra >= cap) {
                    break;
                }
                if (c.negative() != 0.0 && picked.add(c)) {
                    extra++;
                }
            }
        }
        return new ArrayList<>(picked);
    }

    /**
     * 渲染成一行，形如 {@code cap.a=0.831(s0.800,r0.900); cap.b=0.402(s0.500,r0.000,n0.100)}。
     *
     * <p>压成单个属性而不是每条候选每个字段各占一个：span 属性数在多数 APM 后端是有上限的，
     * 超限的行为往往是静默丢弃整个 span——那时丢的不是候选集，是整条链路，
     * 而这种丢失恰恰在请求量最大、最需要 trace 的时候发生。
     *
     * <p>排名靠序列位置表达，不再单独打 rank：这一行本身就是有序的。
     */
    private String render(List<ScoredCandidate> traced) {
        StringBuilder sb = new StringBuilder();
        for (ScoredCandidate c : traced) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(c.candidateId()).append('=').append(fmt(c.fusedScore()))
                    .append("(s").append(fmt(c.semantic()))
                    .append(",r").append(fmt(c.rule()));
            if (c.negative() != 0.0) {
                sb.append(",n").append(fmt(c.negative()));
            }
            sb.append(')');
            if (policy.includeEvidence() && !c.evidence().isEmpty()) {
                sb.append('[').append(String.join("|", c.evidence())).append(']');
            }
        }
        return sb.toString();
    }

    @Override
    public void recordDecision(String decision, String reasonCode, String shortCircuit,
                               String selectedId, double confidence, String arbitratedBy) {
        Span span = currentSpan();
        if (span == null) {
            return;
        }
        span.tag("huawei.finance.agent.decision", String.valueOf(decision));
        span.tag("huawei.finance.agent.decision.reason_code", String.valueOf(reasonCode));
        span.tag("huawei.finance.agent.decision.short_circuit", String.valueOf(shortCircuit));
        span.tag("huawei.finance.agent.decision.confidence", fmt(confidence));
        span.tag("huawei.finance.agent.decision.arbitrated_by", String.valueOf(arbitratedBy));
        if (selectedId != null) {
            span.tag("huawei.finance.agent.decision.selected", selectedId);
        }
    }

    @Override
    public void recordDecisionVersions(String modelVersion, String promptVersion) {
        Span span = currentSpan();
        if (span == null) {
            return;
        }
        // 只在非空时打：短路路径上的版本常常是哨兵或缓存里的旧值，
        // 打成 "" 会让「这一版模型的 CLARIFY」这种筛选失效
        if (modelVersion != null && !modelVersion.isBlank()) {
            span.tag("huawei.finance.agent.decision.model_version", modelVersion);
        }
        if (promptVersion != null && !promptVersion.isBlank()) {
            span.tag("huawei.finance.agent.decision.prompt_version", promptVersion);
        }
    }

    /**
     * 仲裁前后的对照。
     *
     * <p>三个 tag 各回答一个问题：选中的在检索排名里第几（{@code selected_rank}）、
     * 差点选上的是谁（{@code runner_up}）、模型有没有推翻检索第一名（{@code overruled_top1}）。
     *
     * <p>{@code selected_rank=0} 是一个专门留出来的取值：**选中的能力不在候选集里**。
     * 这不是排序问题而是越界，处置方向完全不同——该查提示词与输出校验，不是查融合权重。
     * 用 0 而不是 -1 或 null，是为了让它在 APM 的数值筛选里也能被一条 {@code < 1} 捞出来。
     */
    @Override
    public void recordArbitrationComparison(List<ScoredCandidate> beforeArbitration, String selectedId) {
        if (beforeArbitration == null || beforeArbitration.isEmpty()) {
            return;
        }

        int rank = 0;
        for (int i = 0; i < beforeArbitration.size(); i++) {
            if (beforeArbitration.get(i).candidateId().equals(selectedId)) {
                rank = i + 1;
                break;
            }
        }

        String top1 = beforeArbitration.get(0).candidateId();
        boolean overruled = selectedId != null && !top1.equals(selectedId);

        // 这条计数是本组数据里唯一进指标的：比例才有看板意义，而单次的排名只在排障时看。
        // 标签只用 AGREED / OVERRULED 两个值，不带能力 id——按能力展开会让基数随卡数增长，
        // 而「今天模型推翻检索的比例涨了」这个判断并不需要按卡拆
        meterRegistry.counter(AgentMetrics.ARBITRATION_VS_RECALL,
                AgentMetrics.TAG_OUTCOME, overruled ? "OVERRULED" : "AGREED").increment();

        Span span = currentSpan();
        if (span == null) {
            return;
        }
        span.tag("huawei.finance.agent.arbitration.selected_rank", String.valueOf(rank));
        if (overruled) {
            span.tag("huawei.finance.agent.arbitration.overruled_top1", top1);
        }
        // 亚军只在确实有第二名时打。候选只有一个时「差点选了谁」没有答案，
        // 打一个空串会让这个 tag 在 APM 里既不能筛也不能忽略
        if (beforeArbitration.size() >= 2) {
            ScoredCandidate runnerUp = beforeArbitration.get(0).candidateId().equals(selectedId)
                    ? beforeArbitration.get(1)
                    : beforeArbitration.get(0);
            span.tag("huawei.finance.agent.arbitration.runner_up",
                    runnerUp.candidateId() + "=" + fmt(runnerUp.fusedScore()));
        }
    }

    @Override
    public void recordPhase(String phase, long nanos) {
        meterRegistry.timer(AgentMetrics.PHASE_LATENCY, AgentMetrics.TAG_PHASE, phase)
                .record(nanos, TimeUnit.NANOSECONDS);
        Span span = currentSpan();
        if (span != null) {
            span.tag("huawei.finance.agent.phase." + phase + ".ms", fmt(nanos / 1_000_000.0));
        }
    }

    @Override
    public PhaseSpan startPhaseSpan(String phase) {
        return startPhaseSpan(phase, null);
    }

    @Override
    public TraceScope bindParent(String traceId) {
        if (tracer == null || traceId == null || traceId.isBlank()) return TraceScope.NOOP;
        Span parent = tracer.currentSpan();
        if (parent == null) return TraceScope.NOOP;
        Span previous = asynchronousParents.put(traceId, parent);
        return () -> {
            if (previous == null) asynchronousParents.remove(traceId, parent);
            else asynchronousParents.replace(traceId, parent, previous);
        };
    }

    @Override
    public PhaseSpan startPhaseSpan(String phase, String traceId) {
        if (tracer == null || phase == null || phase.isBlank()) {
            return PhaseSpan.NOOP;
        }
        Span parent = tracer.currentSpan();
        if (parent == null && traceId != null) parent = asynchronousParents.get(traceId);
        if (parent == null) return PhaseSpan.NOOP;
        String operation = "arbitration".equals(phase) ? "arbitrate" : phase;
        Span span = tracer.nextSpan(parent).name("agent.intent." + operation).start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        return new PhaseSpan() {
            @Override
            public void error(Throwable error) {
                if (error != null) span.error(error);
            }

            @Override
            public void close() {
                try {
                    scope.close();
                } finally {
                    span.end();
                }
            }
        };
    }

    private Span currentSpan() {
        return tracer == null ? null : tracer.currentSpan();
    }

    /** 固定 3 位小数并钉 {@link Locale#ROOT}：跟随默认 locale 会在部分环境把小数点写成逗号。 */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
