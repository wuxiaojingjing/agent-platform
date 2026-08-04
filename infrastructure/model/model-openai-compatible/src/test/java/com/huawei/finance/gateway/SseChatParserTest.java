package com.huawei.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.obs.AgentMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * FP-63：SSE 解析与流式三段计时。
 */
class SseChatParserTest {

    @Test
    @DisplayName("拼 content，并钉下首帧 / 首 token；usage 给出 avg 分母")
    void parsesDeltasAndUsage() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant"},"index":0}]}

                data: {"choices":[{"delta":{"content":"{\\"d\\""},"index":0}]}

                data: {"choices":[{"delta":{"content":":1}"},"index":0}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}

                data: [DONE]

                """;
        long started = System.nanoTime();
        ChatStreamTimings t = SseChatParser.parse(new StringReader(sse), started);

        assertThat(t.content()).isEqualTo("{\"d\":1}");
        assertThat(t.firstFrameMs()).isGreaterThanOrEqualTo(0);
        assertThat(t.firstTokenMs()).isGreaterThanOrEqualTo(t.firstFrameMs());
        assertThat(t.completionTokens()).isEqualTo(4);
        assertThat(t.avgTokenMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("没有 usage 时不算 avg_token，避免用字符数冒充")
    void skipsAvgWithoutUsage() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        ChatStreamTimings t = SseChatParser.parse(new StringReader(sse), System.nanoTime());
        assertThat(t.content()).isEqualTo("hi");
        assertThat(t.completionTokens()).isZero();
        assertThat(t.avgTokenMs()).isEqualTo(-1);
    }

    @Test
    @DisplayName("流式成功时打出首帧 / 首 token / 均 token，并带模型与提示词版本")
    void gatewayRecordsStreamTimersWithVersions() {
        String sse = """
                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: {"usage":{"completion_tokens":2}}

                data: [DONE]

                """;
        MeterRegistry meters = new SimpleMeterRegistry();
        ModelGatewayProperties props = new ModelGatewayProperties();
        props.getArbitration().setStreamTiming(true);

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost")
                .requestFactory(fixedSse(sse))
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("gw", CircuitBreakerConfig.ofDefaults());
        Retry retry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()).retry("r");

        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                restClient, props, breaker, retry, meters, "k");

        GatewayResult<String> result = gateway.chat(new ChatRequest(
                "Qwen/test-model", "sys", "user", 32, 0.0, true, "arb-skill-v3"));

        assertThat(result.available()).isTrue();
        assertThat(result.value()).isEqualTo("ok");

        assertThat(timer(meters, AgentMetrics.GATEWAY_FIRST_FRAME).count()).isEqualTo(1);
        assertThat(timer(meters, AgentMetrics.GATEWAY_FIRST_TOKEN).count()).isEqualTo(1);
        assertThat(timer(meters, AgentMetrics.GATEWAY_AVG_TOKEN).count()).isEqualTo(1);

        Timer first = meters.find(AgentMetrics.GATEWAY_FIRST_TOKEN)
                .tag(AgentMetrics.TAG_MODEL_VERSION, "Qwen/test-model")
                .tag(AgentMetrics.TAG_PROMPT_VERSION, "arb-skill-v3")
                .timer();
        assertThat(first).isNotNull();
        assertThat(first.count()).isEqualTo(1);
    }

    private static Timer timer(MeterRegistry meters, String name) {
        Timer t = meters.find(name).timer();
        assertThat(t).as(name).isNotNull();
        return t;
    }

    private static ClientHttpRequestFactory fixedSse(String body) {
        return new ClientHttpRequestFactory() {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
                return new MockClientHttpRequest(httpMethod, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() {
                        return new MockClientHttpResponse(
                                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                                200);
                    }
                };
            }
        };
    }
}
