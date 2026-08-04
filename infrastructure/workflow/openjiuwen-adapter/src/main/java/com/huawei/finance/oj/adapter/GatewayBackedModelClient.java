package com.huawei.finance.oj.adapter;

import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ToolChatReply;
import com.huawei.finance.gateway.ToolChatRequest;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * OJ 侧的模型客户端，实际调用落到本工程的模型网关。
 *
 * <p>它不发 HTTP，也不持有密钥——两者都在 {@link ModelGatewayClient} 的实现里。
 * 这正是本适配层的意义：OJ 的组件按它自己的接口调模型，而流量走的是我们那条带
 * 往返预算、熔断与审计的通道。
 */
final class GatewayBackedModelClient extends BaseModelClient {

    private static final int DEFAULT_MAX_TOKENS = 1024;
    private final ModelGatewayClient gateway;

    GatewayBackedModelClient(ModelRequestConfig modelConfig, ModelClientConfig clientConfig,
                             ModelGatewayClient gateway) {
        super(modelConfig, clientConfig);
        this.gateway = gateway;
    }

    @Override
    protected String getClientName() {
        return "agent-platform gateway client";
    }

    /**
     * 不校验 apiKey / apiBase。
     *
     * <p>基类要求这两项非空，那是为直连厂商的客户端准备的。本客户端一个凭据都不该持有：
     * 密钥只存在于网关实现里，在这儿再配一份等于把密钥多散一处。
     */
    @Override
    protected void validateConfig() {
        // 故意留空，理由见方法注释
    }

    @Override
    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP,
                                   String model, Integer maxTokens, String stop,
                                   BaseOutputParser outputParser, Float timeout,
                                   Map<String, Object> kwargs) {
        if (tools instanceof List<?> toolList && !toolList.isEmpty()) {
            return invokeWithTools(messages, toolList, temperature, model, maxTokens);
        }

        String system = roleContent(messages, "system");
        String user = roleContent(messages, "user");
        if (user.isBlank()) {
            // 没有用户消息还去调模型，只会拿回一段与本次请求无关的话
            throw new IllegalArgumentException("消息里没有 user 角色的内容，无法构造请求");
        }

        ChatRequest request = new ChatRequest(
                model == null || model.isBlank() ? modelName() : model,
                system,
                user,
                positiveMaxTokens(maxTokens),
                temperature == null ? 0.0 : temperature,
                jsonMode(kwargs));

        GatewayResult<String> result = gateway.chat(request);
        if (!result.available()) {
            // 网关把不可用表达成返回值（GatewayResult 的类注释），但 OJ 这一侧只认异常。
            // 转成异常是必要的：返回一个空 AssistantMessage 会让 Agent 把「模型没答」
            // 当成「模型答了个空」，继续往下走
            throw new IllegalStateException("模型网关不可用：" + result.reason());
        }
        return new AssistantMessage(result.value());
    }

    /**
     * 带工具的调用：走网关的 Agent 通道。
     *
     * <p>与无工具那条路分开，是因为两者的约束不同：仲裁那条是单轮定参、计入 A 线预算；
     * 这条是多轮、不计预算（理由见 {@code BudgetAwareModelGateway#chatWithTools}）。
     */
    private AssistantMessage invokeWithTools(Object messages, List<?> tools, Float temperature,
                                             String model, Integer maxTokens) {
        ToolChatRequest request = new ToolChatRequest(
                model == null || model.isBlank() ? modelName() : model,
                toChatMessages(messages),
                toToolSchemas(tools),
                positiveMaxTokens(maxTokens),
                temperature == null ? 0.0 : temperature);

        GatewayResult<ToolChatReply> result = gateway.chatWithTools(request);
        if (!result.available()) {
            throw new IllegalStateException("模型网关的工具调用通道不可用：" + result.reason());
        }

        ToolChatReply reply = result.value();
        AssistantMessage message = new AssistantMessage(reply.content());
        if (reply.wantsTools()) {
            message.setToolCalls(reply.toolCalls().stream()
                    .map(call -> ToolCall.builder()
                            .id(call.id())
                            .type("function")
                            .name(call.name())
                            .arguments(call.arguments())
                            .build())
                    .toList());
            message.setFinishReason("tool_calls");
        }
        return message;
    }

    private static int positiveMaxTokens(Integer maxTokens) {
        return maxTokens == null || maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
    }

    /**
     * 保持消息顺序地摊平。
     *
     * <p>与无工具那条路的「按角色拼接」不同：Agent 循环里工具结果与助手消息必须交替出现，
     * 顺序错了模型就对不上「哪个结果回应哪次调用」，表现是它反复调同一个工具。
     */
    private static List<ToolChatRequest.ChatMessage> toChatMessages(Object messages) {
        List<ToolChatRequest.ChatMessage> flat = new ArrayList<>();
        flatten(messages, flat);
        return flat;
    }

    private static void flatten(Object messages, List<ToolChatRequest.ChatMessage> out) {
        switch (messages) {
            case null -> {
            }
            case String s -> out.add(ToolChatRequest.ChatMessage.user(s));
            case ToolMessage tool ->
                    out.add(ToolChatRequest.ChatMessage.toolResult(tool.getToolCallId(),
                            tool.getContentAsString()));
            case AssistantMessage assistant -> out.add(flattenAssistant(assistant));
            case BaseMessage message ->
                    out.add(new ToolChatRequest.ChatMessage(message.getRole(),
                            message.getContentAsString(), null, List.of()));
            case Map<?, ?> map ->
                    out.add(new ToolChatRequest.ChatMessage(
                            String.valueOf(map.get("role")),
                            String.valueOf(map.get("content")),
                            map.get("tool_call_id") == null ? null
                                    : String.valueOf(map.get("tool_call_id")),
                            List.of()));
            case Iterable<?> items -> items.forEach(item -> flatten(item, out));
            default -> throw new IllegalArgumentException(
                    "无法识别的消息形态：" + messages.getClass().getName());
        }
    }

    /**
     * 助手消息：若带了 toolCalls，必须透传到网关的 {@code tool_calls}。
     *
     * <p>摊成「只有 content」会让第二轮请求丢掉上一轮调用意图，模型对不上后续
     * tool 结果——多轮 ReAct 从第一轮工具调用起就断了。
     */
    private static ToolChatRequest.ChatMessage flattenAssistant(AssistantMessage assistant) {
        List<ToolCall> ojCalls = assistant.getToolCalls();
        if (ojCalls == null || ojCalls.isEmpty()) {
            return ToolChatRequest.ChatMessage.assistant(assistant.getContentAsString());
        }
        List<ToolChatRequest.ChatMessage.ToolCall> calls = ojCalls.stream()
                .map(call -> new ToolChatRequest.ChatMessage.ToolCall(
                        call.getId(), call.getName(), call.getArguments()))
                .toList();
        return ToolChatRequest.ChatMessage.assistant(assistant.getContentAsString(), calls);
    }

    /** OJ 的 {@code ToolInfo} → OpenAI function 声明。 */
    private static List<Map<String, Object>> toToolSchemas(List<?> tools) {
        List<Map<String, Object>> schemas = new ArrayList<>(tools.size());
        for (Object tool : tools) {
            if (tool instanceof ToolInfo info) {
                schemas.add(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", info.getName(),
                                "description", info.getDescription(),
                                "parameters", info.getParameters())));
            } else if (tool instanceof Map<?, ?> map) {
                // 已经是 API 形态，原样透传
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) map;
                schemas.add(raw);
            } else {
                throw new IllegalArgumentException(
                        "无法识别的工具声明：" + tool.getClass().getName());
            }
        }
        return schemas;
    }

    /**
     * 单块返回，不做真流式。
     *
     * <p>面客回复由 {@code response-engine} 依模板生成，留一条能流式吐自由文本的路径等于
     * 留了一条绕过模板与护栏直接面客的路（WP8）。OJ 内部若以流式方式驱动循环，
     * 拿到一个完整块也能正常工作，只是没有增量。
     */
    @Override
    public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
                                                  String model, Integer maxTokens, String stop,
                                                  BaseOutputParser outputParser, Float timeout,
                                                  Map<String, Object> kwargs) {
        AssistantMessage full = invoke(messages, tools, temperature, topP, model, maxTokens, stop,
                outputParser, timeout, kwargs);
        AssistantMessageChunk chunk = AssistantMessageChunk.builder()
                .role("assistant")
                .content(full.getContentAsString())
                .finishReason("stop")
                .build();
        return List.of(chunk).iterator();
    }

    @Override
    public ImageGenerationResponse generateImage(List<UserMessage> messages, String model, String size,
                                                 String negativePrompt, int n, boolean promptExtend,
                                                 boolean watermark, int seed, Map<String, Object> kwargs) {
        throw multimodalUnsupported("图像生成");
    }

    @Override
    public AudioGenerationResponse generateSpeech(List<UserMessage> messages, String model, String voice,
                                                  String languageType, Map<String, Object> kwargs) {
        throw multimodalUnsupported("语音合成");
    }

    @Override
    public VideoGenerationResponse generateVideo(List<UserMessage> messages, String imgUrl, String audioUrl,
                                                 String model, String size, String resolution,
                                                 int duration, boolean promptExtend, boolean watermark,
                                                 String negativePrompt, Integer seed,
                                                 Map<String, Object> kwargs) {
        throw multimodalUnsupported("视频生成");
    }

    /**
     * 多模态一律抛错，不返回空对象。
     *
     * <p>返回空响应会让调用方以为生成成功但内容为空，排查方向会跑到模型侧去。
     */
    private static UnsupportedOperationException multimodalUnsupported(String what) {
        return new UnsupportedOperationException(
                "agent-platform 模型通道不提供" + what + "。本行的模型接入只覆盖文本推理与向量化");
    }

    private String modelName() {
        return modelConfig == null ? "" : modelConfig.getModelName();
    }

    /**
     * 是否要求 JSON 输出。
     *
     * <p>仲裁调用靠 {@code response_format=json_object} 保证产出可解析（v0.7 §3.3），
     * 这个意图从 OJ 侧传下来时在 kwargs 里，丢掉它模型就会回一段带解释的自然语言。
     */
    private static boolean jsonMode(Map<String, Object> kwargs) {
        if (kwargs == null) {
            return false;
        }
        Object format = kwargs.get("response_format");
        return format != null && String.valueOf(format).contains("json");
    }

    /**
     * 取某个角色的全部内容。
     *
     * <p>OJ 传下来的 {@code messages} 可能是一个字符串、一条消息，或一串消息；
     * 我们的 {@link ChatRequest} 只有 system 与 user 两格，因此同角色多条按顺序拼接。
     * 拼接而不是只取最后一条：多条 user 消息在 Agent 循环里是常态（观察结果会追加成新消息），
     * 只取最后一条等于把前面的观察全丢了。
     */
    private static String roleContent(Object messages, String role) {
        StringJoiner joiner = new StringJoiner("\n");
        collect(messages, role, joiner);
        return joiner.toString();
    }

    private static void collect(Object messages, String role, StringJoiner joiner) {
        switch (messages) {
            case null -> {
            }
            case String s -> {
                // 裸字符串按用户输入处理，这是 OJ 里常见的简写形态
                if ("user".equals(role) && !s.isBlank()) {
                    joiner.add(s);
                }
            }
            case BaseMessage message -> {
                if (role.equals(message.getRole())) {
                    joiner.add(message.getContentAsString());
                }
            }
            case Map<?, ?> map -> {
                if (role.equals(String.valueOf(map.get("role")))) {
                    joiner.add(String.valueOf(map.get("content")));
                }
            }
            case Iterable<?> items -> items.forEach(item -> collect(item, role, joiner));
            default -> throw new IllegalArgumentException(
                    "无法识别的消息形态：" + messages.getClass().getName());
        }
    }
}
