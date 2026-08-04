package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * FP-63：OTLP 导出按需启用。
 *
 * <p>依赖已在 classpath，但没配 endpoint 时不得创建导出 Bean——否则本地会往
 * {@code localhost:4318} 重试刷屏。配了才装上，接行内 collector 只动环境变量。
 */
class OtlpExportConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class));

    @Test
    @DisplayName("未配 endpoint 时不创建 OTLP 导出器")
    void noExporterWithoutEndpoint() {
        runner.withPropertyValues("spring.application.name=agent-platform-otlp-test")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OtlpHttpSpanExporter.class));
    }

    @Test
    @DisplayName("配了 endpoint 即创建 OTLP HTTP 导出器")
    void exporterWhenEndpointSet() {
        runner.withPropertyValues(
                        "spring.application.name=agent-platform-otlp-test",
                        "management.opentelemetry.tracing.export.otlp.endpoint="
                                + "http://127.0.0.1:4318/v1/traces")
                .run(ctx -> assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class));
    }
}
