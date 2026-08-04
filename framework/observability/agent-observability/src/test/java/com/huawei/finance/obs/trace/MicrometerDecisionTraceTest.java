package com.huawei.finance.obs.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.obs.ObsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-62 记录器的行为。
 *
 * <p>用真的 OTEL SDK 收 span 而不是 mock {@code Span}：要验的是属性真的落到了导出的 span 上。
 * mock 只能证明我们调了 {@code tag()}，而属性名写错、值被覆盖、span 根本没在作用域内
 * 这几类问题，mock 一个都拦不住。
 */
class MicrometerDecisionTraceTest {

    private InMemorySpanExporter exporter;
    private Tracer tracer;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        tracer = new OtelTracer(sdk.getTracer("test"), new OtelCurrentTraceContext(), event -> {
        });
        meterRegistry = new SimpleMeterRegistry();
    }

    private MicrometerDecisionTrace traceWith(DecisionTracePolicy policy) {
        return new MicrometerDecisionTrace(tracer, meterRegistry, policy);
    }

    private MicrometerDecisionTrace defaults() {
        return traceWith(new PropertyBackedTracePolicy(new ObsProperties()));
    }

    /** 在一个 span 作用域里跑，返回该 span 导出后的属性表。 */
    private Map<String, String> tagsOf(Runnable body) {
        Span span = tracer.nextSpan().name("fastpath").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            body.run();
        } finally {
            span.end();
        }
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        return spans.get(0).getAttributes().asMap().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getKey(), e -> String.valueOf(e.getValue())));
    }

    private static ScoredCandidate candidate(String id, double fused, double negative) {
        return new ScoredCandidate(id, fused, fused, 0.0, negative, List.of("keyword:余额"));
    }

    @Nested
    @DisplayName("候选集对照")
    class Candidates {

        @Test
        @DisplayName("按序记下候选与打分，并给出头两名分差")
        void recordsRankedCandidatesAndMargin() {
            Map<String, String> tags = tagsOf(() -> defaults().recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.b", 0.4, 0.0)),
                    2, List.of()));

            assertThat(tags.get("huawei.finance.agent.recall.candidates"))
                    .contains("cap.a=0.900")
                    .contains("cap.b=0.400");
            // 0.9 - 0.4；这个数是排序事故的第一诊断位
            assertThat(tags.get("huawei.finance.agent.recall.margin")).isEqualTo("0.500");
            assertThat(tags.get("huawei.finance.agent.recall.total")).isEqualTo("2");
        }

        @Test
        @DisplayName("召回总数与实际记录条数分开，避免把「只召回到 2 条」误读成「被截断了」")
        void keepsTotalSeparateFromTraced() {
            DecisionTracePolicy onlyOne = policy(1, false, false);
            Map<String, String> tags = tagsOf(() -> traceWith(onlyOne).recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.b", 0.4, 0.0),
                            candidate("cap.c", 0.1, 0.0)),
                    3, List.of()));

            assertThat(tags.get("huawei.finance.agent.recall.candidates")).contains("cap.a").doesNotContain("cap.b");
            assertThat(tags.get("huawei.finance.agent.recall.total")).isEqualTo("3");
        }

        @Test
        @DisplayName("被负向压掉、掉出 topN 的候选仍要记——它是排序事故的第一嫌疑人")
        void keepsSuppressedCandidateEvenOutsideTopN() {
            // cap.z 被压到最低分，若只记 top1 就会从现场消失
            Map<String, String> tags = tagsOf(() -> traceWith(policy(1, false, true)).recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.z", 0.05, 0.7)),
                    2, List.of()));

            assertThat(tags.get("huawei.finance.agent.recall.candidates"))
                    .contains("cap.a")
                    .contains("cap.z")
                    .contains("n0.700");
        }

        @Test
        @DisplayName("关掉 includeSuppressed 时不额外补，尊重使用方的取舍")
        void respectsSuppressedOff() {
            Map<String, String> tags = tagsOf(() -> traceWith(policy(1, false, false)).recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.z", 0.05, 0.7)),
                    2, List.of()));

            assertThat(tags.get("huawei.finance.agent.recall.candidates")).doesNotContain("cap.z");
        }

        @Test
        @DisplayName("maxCandidates 为 0 时一条候选都不记")
        void recordsNothingWhenCapZero() {
            Map<String, String> tags = tagsOf(() -> traceWith(policy(0, true, true)).recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of()));

            assertThat(tags).doesNotContainKey("huawei.finance.agent.recall.candidates");
            // 总数仍要记：候选集不记，不等于连「召回到了几条」都不许知道
            assertThat(tags.get("huawei.finance.agent.recall.total")).isEqualTo("1");
        }

        @Test
        @DisplayName("证据串默认不进 span，打开才进")
        void evidenceIsOptOut() {
            Map<String, String> off = tagsOf(() -> defaults().recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of()));
            assertThat(off.get("huawei.finance.agent.recall.candidates")).doesNotContain("keyword:余额");

            setUp();
            Map<String, String> on = tagsOf(() -> traceWith(policy(5, true, true)).recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of()));
            assertThat(on.get("huawei.finance.agent.recall.candidates")).contains("keyword:余额");
        }

        @Test
        @DisplayName("通道齐全时不打 degraded_channels，否则该筛选条件在 APM 里失效")
        void omitsDegradedChannelsWhenNone() {
            Map<String, String> none = tagsOf(() -> defaults().recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of()));
            assertThat(none).doesNotContainKey("huawei.finance.agent.recall.degraded_channels");

            setUp();
            Map<String, String> degraded = tagsOf(() -> defaults().recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of("semantic")));
            assertThat(degraded.get("huawei.finance.agent.recall.degraded_channels")).isEqualTo("semantic");
        }

        @Test
        @DisplayName("只有一条候选时不打 margin：没有第二名，分差无从谈起")
        void noMarginWithSingleCandidate() {
            Map<String, String> tags = tagsOf(() -> defaults().recordCandidates(
                    List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of()));
            assertThat(tags).doesNotContainKey("huawei.finance.agent.recall.margin");
        }
    }

    @Nested
    @DisplayName("出口与分段耗时")
    class DecisionAndPhases {

        @Test
        @DisplayName("记下出口、原因、短路层级与「由谁定」")
        void recordsDecision() {
            Map<String, String> tags = tagsOf(() -> defaults().recordDecision(
                    "CLARIFY", "MISSING_SLOT", "NONE", "cap.transfer", 0.62, "MODEL"));

            assertThat(tags).containsEntry("huawei.finance.agent.decision", "CLARIFY")
                    .containsEntry("huawei.finance.agent.decision.reason_code", "MISSING_SLOT")
                    .containsEntry("huawei.finance.agent.decision.short_circuit", "NONE")
                    .containsEntry("huawei.finance.agent.decision.selected", "cap.transfer")
                    .containsEntry("huawei.finance.agent.decision.confidence", "0.620")
                    .containsEntry("huawei.finance.agent.decision.arbitrated_by", "MODEL");
        }

        @Test
        @DisplayName("没有单一选中能力时不打 selected，而不是打一个 \"null\"")
        void omitsSelectedWhenAbsent() {
            Map<String, String> tags = tagsOf(() -> defaults().recordDecision(
                    "HANDOFF", "POLICY_BLOCK", "L2_STRONG_RULE", null, 1.0, "SHORT_CIRCUIT"));
            assertThat(tags).doesNotContainKey("huawei.finance.agent.decision.selected");
        }

        @Test
        @DisplayName("模型与提示词版本进 span，空值不打（FP-63）")
        void recordsDecisionVersions() {
            Map<String, String> tags = tagsOf(() -> {
                DecisionTrace trace = defaults();
                trace.recordDecision("EXECUTE_CAPABILITY", "HIGH_CONFIDENCE", "L3_MODEL",
                        "cap.a", 0.9, "MODEL");
                trace.recordDecisionVersions("Qwen/test", "arb-skill-v3");
                // 空串与 null 不得覆盖已写下的版本，也不得另打空 tag
                trace.recordDecisionVersions("", null);
            });
            assertThat(tags)
                    .containsEntry("huawei.finance.agent.decision.model_version", "Qwen/test")
                    .containsEntry("huawei.finance.agent.decision.prompt_version", "arb-skill-v3");
        }

        @Test
        @DisplayName("分段耗时同时落指标与 span：看板要指标，单请求排查要 span")
        void phaseGoesToBothMetricAndSpan() {
            Map<String, String> tags = tagsOf(() -> defaults()
                    .recordPhase("recall", TimeUnit.MILLISECONDS.toNanos(120)));

            assertThat(tags.get("huawei.finance.agent.phase.recall.ms")).isEqualTo("120.000");
            assertThat(meterRegistry.find(AgentMetrics.PHASE_LATENCY)
                    .tag(AgentMetrics.TAG_PHASE, "recall").timer()).isNotNull();
        }

        @Test
        @DisplayName("召回和仲裁阶段可以生成真实子 Span，不只是父 Span 上的耗时 tag")
        void phaseCreatesAChildSpan() {
            Span parent = tracer.nextSpan().name("agent.intent.recognize").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent);
                 DecisionTrace.PhaseSpan phase = defaults().startPhaseSpan("recall")) {
                // The phase scope is the behavior under test.
            } finally {
                parent.end();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).extracting(SpanData::getName)
                    .containsExactlyInAnyOrder("agent.intent.recognize", "agent.intent.recall");
            SpanData parentData = spans.stream()
                    .filter(span -> span.getName().equals("agent.intent.recognize"))
                    .findFirst().orElseThrow();
            SpanData childData = spans.stream()
                    .filter(span -> span.getName().equals("agent.intent.recall"))
                    .findFirst().orElseThrow();
            assertThat(childData.getParentSpanId()).isEqualTo(parentData.getSpanId());
        }

        @Test
        @DisplayName("异步决策图换线程后仍以 recognize 为父 Span，绑定结束后不泄漏")
        void asynchronousPhaseUsesBoundParentAndScopeIsCleaned() {
            MicrometerDecisionTrace trace = defaults();
            Span parent = tracer.nextSpan().name("agent.intent.recognize").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent);
                 DecisionTrace.TraceScope binding = trace.bindParent("trace-async")) {
                CompletableFuture.runAsync(() -> {
                    try (DecisionTrace.PhaseSpan phase =
                                 trace.startPhaseSpan("recall", "trace-async")) {
                        // The asynchronous phase scope is the behavior under test.
                    }
                }).join();
            } finally {
                parent.end();
            }
            CompletableFuture.runAsync(() -> {
                try (DecisionTrace.PhaseSpan phase =
                             trace.startPhaseSpan("arbitrate-after-close", "trace-async")) {
                    // A closed binding must not create another span.
                }
            }).join();

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).extracting(SpanData::getName)
                    .containsExactlyInAnyOrder("agent.intent.recognize", "agent.intent.recall");
            SpanData parentData = spans.stream()
                    .filter(span -> span.getName().equals("agent.intent.recognize"))
                    .findFirst().orElseThrow();
            SpanData childData = spans.stream()
                    .filter(span -> span.getName().equals("agent.intent.recall"))
                    .findFirst().orElseThrow();
            assertThat(childData.getParentSpanId()).isEqualTo(parentData.getSpanId());
        }

        @Test
        @DisplayName("arbitration 指标阶段映射为契约规定的 agent.intent.arbitrate Span")
        void arbitrationPhaseUsesContractedSpanName() {
            Span parent = tracer.nextSpan().name("agent.intent.recognize").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent);
                 DecisionTrace.PhaseSpan phase = defaults().startPhaseSpan("arbitration")) {
                // Span operation naming is the behavior under test.
            } finally {
                parent.end();
            }

            assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                    .containsExactlyInAnyOrder("agent.intent.recognize", "agent.intent.arbitrate");
        }
    }

    /**
     * 仲裁前后的对照。
     *
     * <p>这一组要守的核心是 {@code selected_rank} 的两个特殊取值：1 表示模型认可检索排序，
     * 0 表示模型选了一个不在候选里的能力。后者是越界不是排序问题，处置方向完全不同，
     * 而只看出口分布这两类都表现为「出口不对」。
     */
    @Nested
    @DisplayName("仲裁前后对照")
    class ArbitrationComparison {

        @Test
        @DisplayName("模型认可检索第一名：rank=1，不打 overruled，亚军仍记下来")
        void agreesWithRecallTop1() {
            Map<String, String> tags = tagsOf(() -> defaults().recordArbitrationComparison(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.b", 0.85, 0.0)), "cap.a"));

            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.selected_rank", "1");
            assertThat(tags).doesNotContainKey("huawei.finance.agent.arbitration.overruled_top1");
            // 「差点选了谁」正是这份数据存在的理由：0.9 与 0.85 分不出高下时，
            // 选中谁基本由打分噪声决定，而事后只看出口是看不出这件事的
            assertThat(tags.get("huawei.finance.agent.arbitration.runner_up")).isEqualTo("cap.b=0.850");
            assertThat(meterRegistry.get(AgentMetrics.ARBITRATION_VS_RECALL)
                    .tag(AgentMetrics.TAG_OUTCOME, "AGREED").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("模型推翻检索第一名：记下被推翻的是谁，并计入 OVERRULED")
        void recordsOverruledTop1() {
            Map<String, String> tags = tagsOf(() -> defaults().recordArbitrationComparison(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.b", 0.6, 0.0)), "cap.b"));

            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.selected_rank", "2");
            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.overruled_top1", "cap.a");
            // 选中的是第二名时，亚军位上要显示的是被它挤下去的那个第一名
            assertThat(tags.get("huawei.finance.agent.arbitration.runner_up")).isEqualTo("cap.a=0.900");
            assertThat(meterRegistry.get(AgentMetrics.ARBITRATION_VS_RECALL)
                    .tag(AgentMetrics.TAG_OUTCOME, "OVERRULED").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("选中的能力不在候选集里：rank=0，这是越界不是排序分歧")
        void rankZeroMeansOutOfCandidates() {
            Map<String, String> tags = tagsOf(() -> defaults().recordArbitrationComparison(
                    List.of(candidate("cap.a", 0.9, 0.0)), "cap.never.recalled"));

            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.selected_rank", "0");
            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.overruled_top1", "cap.a");
        }

        @Test
        @DisplayName("没有单一选中能力（拒绝或多意图）时不算推翻")
        void noSelectionIsNotAnOverrule() {
            Map<String, String> tags = tagsOf(() -> defaults().recordArbitrationComparison(
                    List.of(candidate("cap.a", 0.9, 0.0), candidate("cap.b", 0.8, 0.0)), null));

            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.selected_rank", "0");
            assertThat(tags).doesNotContainKey("huawei.finance.agent.arbitration.overruled_top1");
            assertThat(meterRegistry.get(AgentMetrics.ARBITRATION_VS_RECALL)
                    .tag(AgentMetrics.TAG_OUTCOME, "AGREED").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("只有一条候选时不打亚军：没有第二名，「差点选了谁」没有答案")
        void noRunnerUpWithSingleCandidate() {
            Map<String, String> tags = tagsOf(() -> defaults().recordArbitrationComparison(
                    List.of(candidate("cap.a", 0.9, 0.0)), "cap.a"));

            assertThat(tags).doesNotContainKey("huawei.finance.agent.arbitration.runner_up");
            assertThat(tags).containsEntry("huawei.finance.agent.arbitration.selected_rank", "1");
        }

        @Test
        @DisplayName("短路路径没有候选集：一个 tag 都不打，也不计数")
        void shortCircuitRecordsNothing() {
            defaults().recordArbitrationComparison(List.of(), "cap.a");
            defaults().recordArbitrationComparison(null, "cap.a");

            assertThat(meterRegistry.find(AgentMetrics.ARBITRATION_VS_RECALL).counters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("退化行为")
    class Degradation {

        @Test
        @DisplayName("没有 Tracer 时只落指标不炸——观测缺失不该拖垮请求")
        void survivesWithoutTracer() {
            MicrometerDecisionTrace noTracer = new MicrometerDecisionTrace(
                    null, meterRegistry, new PropertyBackedTracePolicy(new ObsProperties()));

            noTracer.recordCandidates(List.of(candidate("cap.a", 0.9, 0.0)), 1, List.of());
            noTracer.recordDecision("EXECUTE_CAPABILITY", "HIGH_CONFIDENCE", "NONE", "cap.a", 1.0, "MODEL");
            noTracer.recordPhase("recall", 1000L);

            assertThat(meterRegistry.find(AgentMetrics.PHASE_LATENCY)
                    .tag(AgentMetrics.TAG_PHASE, "recall").timer()).isNotNull();
        }

        @Test
        @DisplayName("不在任何 span 作用域内时静默跳过")
        void survivesOutsideSpanScope() {
            defaults().recordDecision("EXECUTE_CAPABILITY", "HIGH_CONFIDENCE", "NONE", "cap.a", 1.0, "MODEL");
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }

        @Test
        @DisplayName("负数的 maxCandidates 按 0 处理，不让配置写错变成启动失败")
        void clampsNegativeCap() {
            ObsProperties props = new ObsProperties();
            props.setMaxTracedCandidates(-3);
            assertThat(new PropertyBackedTracePolicy(props).maxCandidates()).isZero();
        }
    }

    private static DecisionTracePolicy policy(int max, boolean evidence, boolean suppressed) {
        return new DecisionTracePolicy() {
            @Override
            public int maxCandidates() {
                return max;
            }

            @Override
            public boolean includeEvidence() {
                return evidence;
            }

            @Override
            public boolean includeSuppressed() {
                return suppressed;
            }
        };
    }
}
