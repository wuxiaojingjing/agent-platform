package com.huawei.finance.product.mobilebanking.console;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.intent.PathSummary;
import com.huawei.finance.common.context.RuntimeModuleStep;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 最近若干次请求的出口快照，供运营页看「刚才发生了什么」。
 *
 * <p><b>它不是审计流水，也不许被当成审计流水用。</b>只在内存里、有上限、重启即空、
 * 多实例各存各的。真正要留痕的东西在 {@code agent_task_transition} 与
 * {@code agent_conversation_turn} 里，那两处才是可回溯的。这里存在的唯一理由是：
 * 指标只能回答「REJECT 涨了」，回答不了「刚才被拒的那句话是什么」，
 * 而排障时人先要看到那句话。
 *
 * <p>{@link PathSummary} 把本来只在 OTEL span 上的候选与分段耗时投影进来，
 * 控制台才能回答「差点选了谁 / 慢在哪一段」。
 */
@Component
public class RecentDecisions {

    private static final int CAPACITY = 100;

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);

    public synchronized void record(String traceId, String sessionId, String query, RouteDecision decision,
                                    String taskId, String templateKey, boolean fellBack,
                                    List<String> degradedChannels, long elapsedMillis,
                                    PathSummary path, List<String> gatewayCalls,
                                    List<RuntimeModuleStep> moduleSteps) {
        if (entries.size() >= CAPACITY) {
            entries.removeLast();
        }
        entries.addFirst(new Entry(Instant.now(), traceId, sessionId, query,
                decision.decision().name(),
                decision.reasonCode() == null ? null : decision.reasonCode().name(),
                decision.shortCircuit() == null ? null : decision.shortCircuit().name(),
                decision.selectedCandidateId(), decision.confidence(),
                taskId, templateKey, fellBack,
                degradedChannels == null ? List.of() : List.copyOf(degradedChannels),
                elapsedMillis,
                path == null ? PathSummary.empty() : path,
                gatewayCalls == null ? List.of() : List.copyOf(gatewayCalls),
                moduleSteps == null ? List.of() : List.copyOf(moduleSteps)));
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public record Entry(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant at,
                        String traceId, String sessionId, String query,
                        String decision, String reasonCode,
                        String shortCircuit, String capabilityId, Double confidence, String taskId,
                        String templateKey, boolean fellBack, List<String> degradedChannels,
                        long elapsedMillis, PathSummary path, List<String> gatewayCalls,
                        List<RuntimeModuleStep> moduleSteps) {
    }
}
