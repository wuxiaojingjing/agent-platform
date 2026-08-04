package com.huawei.finance.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.obs.AgentMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 兼容协议的模型网关实现。
 *
 * <p>三件事决定了这个类的形状：
 *
 * <ol>
 *   <li><b>连接必须复用</b>。实测同连接 embedding 0.35s，每次新建 1.2-2.2s，差距全在 TLS 握手。
 *       连接池由 {@link ModelGatewayConfiguration} 注入的 RestClient 提供，本类不自己建连接。
 *   <li><b>不可用是常态</b>。任何失败都转成 {@link GatewayResult#unavailable} 返回，不抛给上层。
 * </ol>
 *
 * <p>往返计数不在这里，在 {@link BudgetAwareModelGateway} 装饰器上——写在实现类里的话，
 * 换实现即失效，且测试用的假网关不计数。
 */
public class OpenAiCompatibleModelGateway implements ModelGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelGateway.class);

    private final RestClient restClient;
    private final RestClient chatRestClient;
    private final ModelGatewayProperties props;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final MeterRegistry meterRegistry;
    private final String apiKey;
    private final String chatApiKey;

    /** 主网关与 chat 共用同一 RestClient / 密钥（测试与未拆端点时的便利构造）。 */
    public OpenAiCompatibleModelGateway(RestClient restClient,
                                        ModelGatewayProperties props,
                                        CircuitBreaker circuitBreaker,
                                        Retry retry,
                                        MeterRegistry meterRegistry,
                                        String apiKey) {
        this(restClient, restClient, props, circuitBreaker, retry, meterRegistry, apiKey, apiKey);
    }

    public OpenAiCompatibleModelGateway(RestClient restClient,
                                        RestClient chatRestClient,
                                        ModelGatewayProperties props,
                                        CircuitBreaker circuitBreaker,
                                        Retry retry,
                                        MeterRegistry meterRegistry,
                                        String apiKey,
                                        String chatApiKey) {
        this.restClient = restClient;
        this.chatRestClient = chatRestClient == null ? restClient : chatRestClient;
        this.props = props;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.meterRegistry = meterRegistry;
        this.apiKey = apiKey;
        this.chatApiKey = chatApiKey;
    }

    @Override
    public boolean available() {
        boolean hasKey = hasKey(apiKey) || hasKey(chatApiKey);
        return hasKey && circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

    private static boolean hasKey(String key) {
        return key != null && !key.isBlank();
    }

    static boolean usesChatEndpoint(String purpose) {
        return "arbitration".equals(purpose) || "continuation".equals(purpose)
                || "context-rewrite".equals(purpose) || "loop-planner".equals(purpose)
                || "agent-tools".equals(purpose) || "prompt-optimization".equals(purpose);
    }

    private RestClient clientFor(String purpose) {
        return usesChatEndpoint(purpose) ? chatRestClient : restClient;
    }

    private String keyFor(String purpose) {
        return usesChatEndpoint(purpose) ? chatApiKey : apiKey;
    }

    @Override
    public GatewayResult<List<float[]>> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return GatewayResult.ok(List.of(), 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getEmbedding().getModel());
        body.put("input", inputs);

        return call("embedding", "/embeddings", body, node -> parseEmbeddings(node, inputs.size()),
                props.getEmbedding().getModel(), null);
    }

    @Override
    public GatewayResult<String> chat(ChatRequest request) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        if (request.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        applyThinkingExtras(body, request.model(), true,
                "prompt-optimization".equals(request.purpose()));

        if (props.getArbitration().isStreamTiming()) {
            body.put("stream", true);
            // 让末包带回 usage，否则 avg_token 没有分母，只能整条不打
            body.put("stream_options", Map.of("include_usage", true));
            return callStream(request.purpose(), "/chat/completions", body,
                    request.model(), request.promptVersion());
        }
        return call(request.purpose(), "/chat/completions", body, this::parseChatContent,
                request.model(), request.promptVersion());
    }

    /**
     * 带工具的多轮 Chat。
     *
     * <p>与 {@link #chat} 共用同一套熔断、重试与降级骨架，因此工具调用一旦拖垮下游，
     * 熔断保护对它同样生效——单开一条 HTTP 路径最省事，代价是这条路径没人保护。
     */
    @Override
    public GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>(request.messages().size());
        for (ToolChatRequest.ChatMessage message : request.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content() == null ? "" : message.content());
            if (message.toolCallId() != null) {
                item.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                // 多轮 ReAct：上一轮 assistant 的 tool_calls 必须原样回放，否则后续
                // tool 消息对不上 call id，模型会反复调同一个工具
                List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
                for (ToolChatRequest.ChatMessage.ToolCall call : message.toolCalls()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", call.id() == null ? "" : call.id());
                    entry.put("type", "function");
                    entry.put("function", Map.of(
                            "name", call.name() == null ? "" : call.name(),
                            "arguments", call.arguments() == null ? "" : call.arguments()));
                    calls.add(entry);
                }
                item.put("tool_calls", calls);
            }
            messages.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools());
            body.put("tool_choice", "auto");
        }
        applyThinkingExtras(body, request.model(), false, false);

        return call("agent-tools", "/chat/completions", body,
                OpenAiCompatibleModelGateway::parseToolChat, request.model(), null);
    }

    /**
     * 供应商差异：DeepSeek V4 默认开 thinking，仲裁 / 工具调用要显式关掉以免时延与
     * max_tokens 被推理吃光；硅基流动混合模型则走 {@code enable_thinking}。
     */
    private void applyThinkingExtras(Map<String, Object> body, String model, boolean arbitration,
                                     boolean thinkingEnabled) {
        if (model != null && model.toLowerCase().startsWith("deepseek")) {
            body.put("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
            return;
        }
        if (!arbitration) {
            return;
        }
        Boolean thinking = props.getArbitration().getEnableThinking();
        if (thinking != null) {
            body.put("enable_thinking", thinking);
        }
    }

    private static ToolChatReply parseToolChat(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        List<ToolChatReply.ToolCallRequest> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            calls.add(new ToolChatReply.ToolCallRequest(
                    call.path("id").asText(""),
                    call.path("function").path("name").asText(""),
                    call.path("function").path("arguments").asText("")));
        }
        return new ToolChatReply(message.path("content").asText(""), calls);
    }

    @Override
    public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
        if (!props.getRerank().isEnabled()) {
            return GatewayResult.unavailable("rerank-disabled", 0);
        }
        if (documents == null || documents.isEmpty()) {
            return GatewayResult.ok(List.of(), 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getRerank().getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topN, documents.size()));

        return call("rerank", "/rerank", body, OpenAiCompatibleModelGateway::parseRerank,
                props.getRerank().getModel(), null);
    }

    /**
     * 统一的调用骨架：熔断、连接级重试、计时、往返登记、失败转降级。
     *
     * <p>装饰顺序是熔断在外、重试在内。反过来的话，一次被重试救回来的瞬时抖动会在熔断器上
     * 记成一次失败，链路稍有波动熔断器就会误开——而它一开，整条语义通道就被摘掉了。
     * 现在熔断器看到的是「重试完仍然失败」，那才是真正该保护的信号。
     *
     * <p>各接口的读超时不在这里传，而是由 {@code PathAwareRequestFactory} 按 URI 路径决定。
     * Spring 的 RestClient 没有干净的按次设超时入口，硬塞会写出看起来生效实则无效的代码。
     */
    private <T> GatewayResult<T> call(String purpose, String path, Map<String, Object> body,
                                      ResponseParser<T> parser,
                                      String modelVersion, String promptVersion) {
        String key = keyFor(purpose);
        if (!hasKey(key)) {
            return degraded(purpose, "no-api-key", 0, modelVersion, promptVersion);
        }

        long started = System.nanoTime();

        try {
            JsonNode response = circuitBreaker.executeSupplier(
                    Retry.decorateSupplier(retry, () -> post(purpose, path, body, key)));
            T parsed = parser.parse(response);
            long elapsed = elapsedMs(started);
            recordLatency(purpose, "success", elapsed, modelVersion, promptVersion);
            return GatewayResult.ok(parsed, elapsed);

        } catch (CallNotPermittedException e) {
            // 熔断器已打开：这是保护动作，不是错误，不打 error 日志刷屏
            return degraded(purpose, "circuit-open", elapsedMs(started), modelVersion, promptVersion);
        } catch (RestClientResponseException e) {
            log.warn("模型网关返回错误 purpose={} status={} body={}",
                    purpose, e.getStatusCode(), truncate(e.getResponseBodyAsString()));
            return degraded(purpose, "http-" + e.getStatusCode().value(), elapsedMs(started),
                    modelVersion, promptVersion);
        } catch (Exception e) {
            log.warn("模型网关调用失败 purpose={} chain={}", purpose, causeChain(e));
            return degraded(purpose, e.getClass().getSimpleName(), elapsedMs(started),
                    modelVersion, promptVersion);
        }
    }

    /**
     * 流式 chat：用 SSE 拆出首帧 / 首 token / 均 token（FP-63）。
     *
     * <p>熔断与重试仍包在整次流式调用外——半截流失败整次算失败，重试从零开始，
     * 不会把已经吐出一半的字拼到下一次上（那会把 JSON 仲裁搞成乱码）。
     */
    private GatewayResult<String> callStream(String purpose, String path, Map<String, Object> body,
                                             String modelVersion, String promptVersion) {
        String key = keyFor(purpose);
        if (!hasKey(key)) {
            return degraded(purpose, "no-api-key", 0, modelVersion, promptVersion);
        }

        long started = System.nanoTime();
        try {
            ChatStreamTimings timings = circuitBreaker.executeSupplier(
                    Retry.decorateSupplier(retry, () -> postStream(purpose, path, body, key, started)));
            if (timings.content() == null || timings.content().isBlank()) {
                throw new IllegalStateException("stream chat 未产出 content");
            }
            recordLatency(purpose, "success", timings.totalMs(), modelVersion, promptVersion);
            recordStreamTimings(purpose, "success", timings, modelVersion, promptVersion);
            return GatewayResult.ok(timings.content(), timings.totalMs());
        } catch (CallNotPermittedException e) {
            return degraded(purpose, "circuit-open", elapsedMs(started), modelVersion, promptVersion);
        } catch (RestClientResponseException e) {
            log.warn("模型网关流式返回错误 purpose={} status={} body={}",
                    purpose, e.getStatusCode(), truncate(e.getResponseBodyAsString()));
            return degraded(purpose, "http-" + e.getStatusCode().value(), elapsedMs(started),
                    modelVersion, promptVersion);
        } catch (Exception e) {
            log.warn("模型网关流式调用失败 purpose={} chain={}", purpose, causeChain(e));
            return degraded(purpose, e.getClass().getSimpleName(), elapsedMs(started),
                    modelVersion, promptVersion);
        }
    }

    private ChatStreamTimings postStream(String purpose, String path, Map<String, Object> body,
                                         String key, long startedNanos) {
        InputStream stream = clientFor(purpose).post()
                .uri(path)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .body(body)
                .retrieve()
                .body(InputStream.class);
        if (stream == null) {
            throw new IllegalStateException("stream 响应体为空");
        }
        try (InputStream in = stream;
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return SseChatParser.parse(reader, startedNanos);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 SSE 流失败", e);
        }
    }

    /**
     * 摊平异常链。
     *
     * <p>只打最外层异常名对排障几乎无用：Spring 会把连接重置、响应截断、反序列化失败
     * 全部包成 RestClientException，根因藏在第三层。
     */
    static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    private JsonNode post(String purpose, String path, Map<String, Object> body, String key) {
        return clientFor(purpose).post()
                .uri(path)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private static List<float[]> parseEmbeddings(JsonNode root, int expected) {
        JsonNode data = root.path("data");
        float[][] slots = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(0);
            JsonNode vec = item.path("embedding");
            float[] v = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                v[i] = (float) vec.get(i).asDouble();
            }
            if (index >= 0 && index < expected) {
                slots[index] = v;
            }
        }
        List<float[]> result = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            if (slots[i] == null) {
                throw new IllegalStateException("embedding 返回缺少 index=" + i + " 的向量");
            }
            result.add(slots[i]);
        }
        return result;
    }

    /**
     * 解析 chat 输出，顺带把服务端回报的输入 token 数记成指标。
     *
     * <p>这个数是**真值**，而 {@code ModelArbitrator} 侧的 prompt 字符预算只是一个可离线
     * 执行的代理量——没有 Qwen 的分词器就算不出准确 token 数，而为了算 token 去引一个
     * 别家模型的 BPE 分词器，得到的是一个精确的错数。两者分工：字符预算负责在发出前拦住
     * 回归，这个指标负责事后校准字符预算定得合不合理。
     */
    private String parseChatContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("chat 返回无 choices");
        }
        int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
        if (promptTokens > 0) {
            meterRegistry.summary(AgentMetrics.GATEWAY_PROMPT_TOKENS,
                    AgentMetrics.TAG_PURPOSE, "arbitration").record(promptTokens);
        }
        return choices.get(0).path("message").path("content").asText();
    }

    private static List<RerankHit> parseRerank(JsonNode root) {
        List<RerankHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            hits.add(new RerankHit(item.path("index").asInt(), item.path("relevance_score").asDouble()));
        }
        return hits;
    }

    private void recordLatency(String purpose, String outcome, long ms,
                               String modelVersion, String promptVersion) {
        // 端到端延迟不加版本标签：purpose × outcome 的基数已经固定，
        // 版本跟模型滚动会把序列切碎，P99 看板反而看不清。版本挂在下方三段计时上。
        Timer.builder(AgentMetrics.GATEWAY_LATENCY)
                .tag(AgentMetrics.TAG_PURPOSE, purpose)
                .tag(AgentMetrics.TAG_OUTCOME, outcome)
                .register(meterRegistry)
                .record(Duration.ofMillis(ms));
    }

    private void recordStreamTimings(String purpose, String outcome, ChatStreamTimings timings,
                                     String modelVersion, String promptVersion) {
        String model = modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion;
        String prompt = promptVersion == null || promptVersion.isBlank() ? "unknown" : promptVersion;
        if (timings.firstFrameMs() >= 0) {
            Timer.builder(AgentMetrics.GATEWAY_FIRST_FRAME)
                    .tag(AgentMetrics.TAG_PURPOSE, purpose)
                    .tag(AgentMetrics.TAG_OUTCOME, outcome)
                    .tag(AgentMetrics.TAG_MODEL_VERSION, model)
                    .tag(AgentMetrics.TAG_PROMPT_VERSION, prompt)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(timings.firstFrameMs()));
        }
        if (timings.firstTokenMs() >= 0) {
            Timer.builder(AgentMetrics.GATEWAY_FIRST_TOKEN)
                    .tag(AgentMetrics.TAG_PURPOSE, purpose)
                    .tag(AgentMetrics.TAG_OUTCOME, outcome)
                    .tag(AgentMetrics.TAG_MODEL_VERSION, model)
                    .tag(AgentMetrics.TAG_PROMPT_VERSION, prompt)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(timings.firstTokenMs()));
        }
        double avg = timings.avgTokenMs();
        if (avg >= 0) {
            Timer.builder(AgentMetrics.GATEWAY_AVG_TOKEN)
                    .tag(AgentMetrics.TAG_PURPOSE, purpose)
                    .tag(AgentMetrics.TAG_OUTCOME, outcome)
                    .tag(AgentMetrics.TAG_MODEL_VERSION, model)
                    .tag(AgentMetrics.TAG_PROMPT_VERSION, prompt)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(Math.round(avg)));
        }
    }

    private <T> GatewayResult<T> degraded(String purpose, String reason, long elapsed,
                                          String modelVersion, String promptVersion) {
        recordLatency(purpose, "degraded", elapsed, modelVersion, promptVersion);
        meterRegistry.counter(AgentMetrics.DEGRADED,
                AgentMetrics.TAG_COMPONENT, "model-gateway",
                AgentMetrics.TAG_REASON, reason).increment();
        return GatewayResult.unavailable(reason, elapsed);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /** 响应解析器。解析失败抛异常，由 {@link #call} 统一转成降级。 */
    @FunctionalInterface
    private interface ResponseParser<T> {
        T parse(JsonNode node);
    }
}
