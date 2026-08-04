package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.runtime.DefaultAgentRuntime;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

/**
 * traceId 的来源。
 *
 * <p>这条看着琐碎，但它决定了排障时给运维的那串 id 有没有用：自己生成的 id 在行内 APM 里
 * 查不到，上游经 W3C traceparent 传下来的链路也断在这里。
 */
class TraceIdSourceTest {

    private static Tracer otelBackedTracer() {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new OtelTracer(sdk.getTracer("agent-platform-test"), new OtelCurrentTraceContext(), event -> {
        });
    }

    @Test
    void traceIdComesFromCurrentSpan() {
        Tracer tracer = otelBackedTracer();
        Span span = tracer.nextSpan().name("chat").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String traceId = DefaultAgentRuntime.traceIdOf(tracer);
            assertThat(traceId).isEqualTo(span.context().traceId());
            // OTEL 的 traceId 是 32 位十六进制，不带自造前缀
            assertThat(traceId).hasSize(32).doesNotStartWith("trace-");
        } finally {
            span.end();
        }
    }

    /** 没有 HTTP 入口时（测试、离线回放）回落到随机 id，此时本就没有上下游可对。 */
    @Test
    void fallsBackWhenNoSpanIsActive() {
        assertThat(DefaultAgentRuntime.traceIdOf(otelBackedTracer())).startsWith("trace-");
    }

    @Test
    void fallsBackWhenTracingIsAbsent() {
        assertThat(DefaultAgentRuntime.traceIdOf(null)).startsWith("trace-");
    }
}
