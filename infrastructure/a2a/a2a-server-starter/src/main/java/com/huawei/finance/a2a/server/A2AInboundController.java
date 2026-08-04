package com.huawei.finance.a2a.server;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.obs.AgentLogContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/a2a/v2")
public class A2AInboundController {

    private static final Logger log = LoggerFactory.getLogger(A2AInboundController.class);

    private final LocalAgentNodeRegistry nodes;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public A2AInboundController(LocalAgentNodeRegistry nodes,
                                MeterRegistry meterRegistry, Tracer tracer) {
        this.nodes = nodes;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @PostMapping("/inbound")
    public DelegationReceipt inbound(@RequestBody DelegationEnvelope envelope) {
        Span current = tracer == null ? null : tracer.currentSpan();
        if (current != null && envelope.traceId() != null
                && !envelope.traceId().equals(current.context().traceId())) {
            return DelegationReceipt.fatal(envelope.delegationId(), "TRACE_CONTEXT_MISMATCH",
                    "W3C traceparent 与 A2A 信封 traceId 不一致");
        }

        long started = System.nanoTime();
        Span span = tracer == null ? null : tracer.nextSpan().name("agent.a2a.server.execute")
                .tag("a2a.source.agent", envelope.sourceAgentId())
                .tag("a2a.target.agent", envelope.targetAgentId())
                .tag("a2a.mode", envelope.mode().name())
                .tag("a2a.delegation.id", envelope.delegationId()).start();
        DelegationReceipt receipt;
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span);
             AgentLogContext logContext = AgentLogContext.open(java.util.Map.of(
                     "traceId", value(envelope.traceId()),
                     "taskId", value(envelope.rootTaskId()),
                     "delegationId", value(envelope.delegationId()),
                     "sourceAgent", value(envelope.sourceAgentId()),
                     "targetAgent", value(envelope.targetAgentId())))) {
            receipt = nodes.find(envelope.targetAgentId())
                    .map(node -> node.handle(envelope))
                    .orElseGet(() -> DelegationReceipt.fatal(envelope.delegationId(),
                            "AGENT_NOT_LOCAL", "目标 Agent 不在本进程：" + envelope.targetAgentId()));
            MDC.put("outcome", receipt.outcome().name());
            if (receipt.reasonCode() != null) {
                MDC.put("reasonCode", receipt.reasonCode());
            }
            log.info("A2A 入站执行完成 target={} outcome={} reason={}",
                    envelope.targetAgentId(), receipt.outcome(), receipt.reasonCode());
        }

        meterRegistry.timer(AgentMetrics.A2A_SEGMENT_LATENCY,
                AgentMetrics.TAG_SEGMENT, "server",
                AgentMetrics.TAG_OUTCOME, receipt.outcome().name())
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        if (receipt.reasonCode() != null) {
            meterRegistry.counter(AgentMetrics.A2A_FAILURE,
                    AgentMetrics.TAG_SEGMENT, "server",
                    AgentMetrics.TAG_REASON, receipt.reasonCode(),
                    AgentMetrics.TAG_OUTCOME, receipt.outcome().name()).increment();
        }
        if (span != null) {
            span.tag("a2a.outcome", receipt.outcome().name());
            if (receipt.reasonCode() != null) {
                span.tag("a2a.reason_code", receipt.reasonCode());
            }
            span.end();
        }
        return receipt;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
