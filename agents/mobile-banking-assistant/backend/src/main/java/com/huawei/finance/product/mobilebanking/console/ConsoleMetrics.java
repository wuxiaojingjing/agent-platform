package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 把 Micrometer 里的打点折成运营页要的那几张表。
 *
 * <p>为什么不让前端直接读 `/actuator/prometheus`：那是给采集器的格式，前端要在浏览器里
 * 解析文本、自己按标签分组、自己算比例，等于把聚合口径复制一份到前端。口径复制之后就会
 * 漂移——看板上的「降级率」和这里说的不再是同一个数，而没人会发现。
 *
 * <p>只读取，不新增打点。这个类若开始自己 `increment`，它就从视图变成了第二个数据源。
 */
@Component
public class ConsoleMetrics {

    private final MeterRegistry registry;

    public ConsoleMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("exits", countsBy(AgentMetrics.ARBITRATION_DECISION, AgentMetrics.TAG_DECISION));
        view.put("reasons", countsBy(AgentMetrics.ARBITRATION_DECISION, AgentMetrics.TAG_REASON_CODE));
        view.put("shortCircuit", countsBy(AgentMetrics.SHORT_CIRCUIT, AgentMetrics.TAG_LEVEL));
        view.put("degraded", countsBy(AgentMetrics.DEGRADED, AgentMetrics.TAG_REASON));
        view.put("templates", countsBy(AgentMetrics.TEMPLATE_RENDER, AgentMetrics.TAG_OUTCOME));
        view.put("arbitrationVsRecall", countsBy(AgentMetrics.ARBITRATION_VS_RECALL, AgentMetrics.TAG_OUTCOME));
        view.put("taskTransitions", countsBy(AgentMetrics.TASK_TRANSITION, AgentMetrics.TAG_TO));

        view.put("latency", timers(AgentMetrics.FASTPATH_LATENCY, null));
        view.put("phaseLatency", timers(AgentMetrics.PHASE_LATENCY, AgentMetrics.TAG_PHASE));
        view.put("gatewayLatency", timers(AgentMetrics.GATEWAY_LATENCY, AgentMetrics.TAG_PURPOSE));
        // FP-63：流式仲裁三段耗时。按 purpose 聚合即可，版本维度留给 Prometheus / APM
        view.put("gatewayFirstFrame", timers(AgentMetrics.GATEWAY_FIRST_FRAME, AgentMetrics.TAG_PURPOSE));
        view.put("gatewayFirstToken", timers(AgentMetrics.GATEWAY_FIRST_TOKEN, AgentMetrics.TAG_PURPOSE));
        view.put("gatewayAvgToken", timers(AgentMetrics.GATEWAY_AVG_TOKEN, AgentMetrics.TAG_PURPOSE));

        view.put("promptChars", summary(AgentMetrics.ARBITRATION_PROMPT_CHARS));
        view.put("roundTrips", summary(AgentMetrics.GATEWAY_ROUND_TRIPS));

        // 这几条不是流量指标，非零就要有人看。摊在总数里会被淹掉，所以单列一组。
        // 网关往返次数不再有「超预算」告警位（ADR-003），分布看 roundTrips summary
        Map<String, Double> alarms = new LinkedHashMap<>();
        alarms.put("contextDegraded", total(AgentMetrics.CONTEXT_DEGRADED));
        alarms.put("sideEffectBlocked", total(AgentMetrics.CONTEXT_SIDE_EFFECT_BLOCKED));
        alarms.put("answerAuditBlocked", total(AgentMetrics.ANSWER_AUDIT));
        alarms.put("tenantHeaderRejected", total(AgentMetrics.TENANT_HEADER_REJECTED));
        alarms.put("agentTimeout", total(AgentMetrics.AGENT_TIMEOUT));
        alarms.put("timeoutClamped", total(AgentMetrics.AGENT_TIMEOUT_CLAMPED));
        view.put("alarms", alarms);

        return view;
    }

    /** 按某个标签分组求和。标签缺失的计数归到 {@code (none)}，不静默丢弃。 */
    private Map<String, Double> countsBy(String meterName, String tag) {
        Map<String, Double> grouped = new TreeMap<>();
        for (Counter counter : registry.find(meterName).counters()) {
            String key = counter.getId().getTag(tag);
            grouped.merge(key == null ? "(none)" : key, counter.count(), Double::sum);
        }
        return grouped;
    }

    private double total(String meterName) {
        return registry.find(meterName).counters().stream().mapToDouble(Counter::count).sum();
    }

    /**
     * 计时器摘要。
     *
     * <p>给的是 count / mean / max 而不是 P95：Micrometer 的分位数要在打点侧预先声明才有，
     * 这里现算出来的会是错的。宁可少给一个数，不给一个看起来对的错数。
     */
    private List<Map<String, Object>> timers(String meterName, String tag) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Timer timer : registry.find(meterName).timers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (tag != null) {
                row.put("key", timer.getId().getTag(tag));
            }
            row.put("count", timer.count());
            row.put("meanMs", round(timer.mean(TimeUnit.MILLISECONDS)));
            row.put("maxMs", round(timer.max(TimeUnit.MILLISECONDS)));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> summary(String meterName) {
        Map<String, Object> row = new LinkedHashMap<>();
        long count = 0;
        double mean = 0;
        double max = 0;
        for (DistributionSummary s : registry.find(meterName).summaries()) {
            count += s.count();
            mean = Math.max(mean, s.mean());
            max = Math.max(max, s.max());
        }
        row.put("count", count);
        row.put("mean", round(mean));
        row.put("max", round(max));
        return row;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
