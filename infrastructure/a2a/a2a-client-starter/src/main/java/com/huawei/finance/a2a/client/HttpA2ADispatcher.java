package com.huawei.finance.a2a.client;

import com.huawei.finance.a2a.A2ADispatcher;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.web.client.RestClient;

/** 只负责把结构化信封投递到独立网关，不解释意图或执行任务。 */
public final class HttpA2ADispatcher implements A2ADispatcher {

    private final RestClient client;
    private final Tracer tracer;

    public HttpA2ADispatcher(RemoteA2AProperties properties) {
        this(properties, RestClient.builder(), null);
    }

    public HttpA2ADispatcher(RemoteA2AProperties properties, RestClient.Builder builder) {
        this(properties, builder, null);
    }

    public HttpA2ADispatcher(
            RemoteA2AProperties properties, RestClient.Builder builder, Tracer tracer) {
        this.client = builder.baseUrl(properties.getGatewayUrl().toString()).build();
        this.tracer = tracer;
    }

    @Override
    public DelegationReceipt dispatch(DelegationEnvelope envelope) {
        RestClient.RequestBodySpec request = client.post().uri("/a2a/v2/delegations");
        Span current = tracer == null ? null : tracer.currentSpan();
        if (current != null) {
            TraceContext context = current.context();
            if (envelope.traceId() != null && !envelope.traceId().equals(context.traceId())) {
                throw new IllegalStateException("TRACE_CONTEXT_MISMATCH");
            }
            request.header("traceparent", traceparent(context));
        }
        DelegationReceipt receipt = request.body(envelope)
                .retrieve()
                .body(DelegationReceipt.class);
        if (receipt == null) {
            throw new IllegalStateException("A2A Gateway 返回空回执");
        }
        return DelegationReceipt.requireValidEnvelope(receipt, envelope.delegationId());
    }

    private static String traceparent(TraceContext context) {
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + '-' + context.spanId() + '-' + flags;
    }
}
