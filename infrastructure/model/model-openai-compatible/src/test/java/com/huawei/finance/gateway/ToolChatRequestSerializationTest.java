package com.huawei.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * 多轮 tool 协议：assistant.tool_calls 必须写进请求体。
 *
 * <p>ReAct 第二轮若不带回上一轮的 tool_calls，后续 tool 消息对不上 call id，
 * 模型会反复调同一个工具——协议缺口在面客链路上表现为「规划永远只走出第一步」。
 */
class ToolChatRequestSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FAKE_REPLY = """
            {"choices":[{"message":{"content":"done","tool_calls":[]}}]}
            """;

    @Test
    @DisplayName("多轮 body 含 assistant.tool_calls 与后续 tool 消息")
    void multiTurnBodyKeepsAssistantToolCallsAndToolResult() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        OpenAiCompatibleModelGateway gateway = gateway(captured);

        GatewayResult<ToolChatReply> result = gateway.chatWithTools(new ToolChatRequest(
                "deepseek-v4-flash",
                List.of(
                        ToolChatRequest.ChatMessage.system("你是规划器"),
                        ToolChatRequest.ChatMessage.user("查余额再转账"),
                        ToolChatRequest.ChatMessage.assistant(
                                "",
                                List.of(new ToolChatRequest.ChatMessage.ToolCall(
                                        "call-1", "cap.balance", "{}"))),
                        ToolChatRequest.ChatMessage.toolResult("call-1", "已列入计划")),
                List.of(Map.of(
                        "type", "function",
                        "function", Map.of("name", "cap.transfer", "description", "转账"))),
                256,
                0.0));

        assertThat(result.available())
                .as("假响应应被网关正常解析；不可用说明 capture 工厂没接上")
                .isTrue();
        assertThat(captured.get()).isNotNull();

        JsonNode body = MAPPER.readTree(captured.get());
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(4);

        JsonNode assistant = messages.get(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("tool_calls")).hasSize(1);
        assertThat(assistant.path("tool_calls").get(0).path("id").asText()).isEqualTo("call-1");
        assertThat(assistant.path("tool_calls").get(0).path("type").asText()).isEqualTo("function");
        assertThat(assistant.path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("cap.balance");

        JsonNode tool = messages.get(3);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call-1");
        assertThat(tool.path("content").asText()).isEqualTo("已列入计划");
    }

    private static OpenAiCompatibleModelGateway gateway(AtomicReference<byte[]> captured) {
        ModelGatewayProperties props = new ModelGatewayProperties();
        props.setConnectRetries(0);
        Retry retry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build())
                .retry("no-retry");
        var breaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(10)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .build())
                .circuitBreaker("model-gateway");

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost")
                .requestFactory(new CapturingFactory(captured))
                .build();

        return new OpenAiCompatibleModelGateway(
                restClient, props, breaker, retry, new SimpleMeterRegistry(), "test-key");
    }

    /** 记下发出的 JSON body，并回一段合法 chat completions。 */
    private static final class CapturingFactory implements ClientHttpRequestFactory {

        private final AtomicReference<byte[]> captured;

        private CapturingFactory(AtomicReference<byte[]> captured) {
            this.captured = captured;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            return new MockClientHttpRequest(httpMethod, uri) {
                @Override
                protected MockClientHttpResponse executeInternal() {
                    captured.set(getBodyAsBytes());
                    MockClientHttpResponse response = new MockClientHttpResponse(
                            FAKE_REPLY.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                }
            };
        }
    }
}
