package com.huawei.finance.a2a.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpA2ADispatcherTraceTest {

    @Test
    void nestedDispatchKeepsCurrentW3cTrace() throws Exception {
        Tracer tracer = tracer();
        Span inbound = tracer.nextSpan().name("inbound-goal").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(inbound)) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            DelegationEnvelope envelope = envelope(inbound.context().traceId());
            String traceparent = "00-" + inbound.context().traceId() + '-'
                    + inbound.context().spanId() + "-01";
            server.expect(once(), requestTo("http://gateway:8086/a2a/v2/delegations"))
                    .andExpect(header("traceparent", traceparent))
                    .andRespond(withSuccess(new ObjectMapper().findAndRegisterModules()
                            .writeValueAsString(DelegationReceipt.succeeded(
                                    envelope.delegationId(), Map.of("targetTaskId", "task-target"))),
                            MediaType.APPLICATION_JSON));

            new HttpA2ADispatcher(properties(), builder, tracer).dispatch(envelope);
            server.verify();
        } finally {
            inbound.end();
        }
    }

    @Test
    void rejectsEnvelopeThatWouldChangeTheCurrentTrace() {
        Tracer tracer = tracer();
        Span inbound = tracer.nextSpan().name("inbound-goal").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(inbound)) {
            HttpA2ADispatcher dispatcher = new HttpA2ADispatcher(
                    properties(), RestClient.builder(), tracer);
            assertThatThrownBy(() -> dispatcher.dispatch(
                    envelope("11111111111111111111111111111111")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("TRACE_CONTEXT_MISMATCH");
        } finally {
            inbound.end();
        }
    }

    private static Tracer tracer() {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new OtelTracer(sdk.getTracer("a2a-client-test"),
                new OtelCurrentTraceContext(), event -> { });
    }

    private static RemoteA2AProperties properties() {
        RemoteA2AProperties properties = new RemoteA2AProperties();
        properties.setGatewayUrl(URI.create("http://gateway:8086"));
        return properties;
    }

    private static DelegationEnvelope envelope(String traceId) {
        return new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "tenant", "agent.finance_assistant",
                "agent.fund_service", "root", "parent", "source", "delegation", traceId,
                DelegationMode.TASK, "查询基金产品C", "cap.fund.product.query", Map.of(),
                List.of(), Instant.now().plusSeconds(30), List.of("agent.mobile-banking-assistant",
                        "agent.finance_assistant"));
    }
}
