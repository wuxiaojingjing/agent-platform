package com.huawei.finance.gateway;

import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/**
 * 带工具的多轮 Chat 参数。
 *
 * <p>与 {@link ChatRequest} 分开而不是加字段：那个是快路径仲裁在用的**单轮、定参、
 * 结构化输出**调用，解码参数固定是版本的一部分（v0.7 §3.3）。往上加多轮消息与工具列表，
 * 会让仲裁那条路径也带上它用不到的自由度，而参数一旦可变，「同一版本必得同一结果」
 * 就不再成立。
 *
 * @param model       模型标识
 * @param messages    完整消息序列。Agent 循环里观察结果会不断追加，必须整段送出
 * @param tools       OpenAI function-calling 形态的工具声明，为空表示本轮不给工具
 * @param maxTokens   输出上限
 * @param temperature 解码温度
 */
@Api
public record ToolChatRequest(
        String model,
        List<ChatMessage> messages,
        List<Map<String, Object>> tools,
        int maxTokens,
        double temperature) {

    public ToolChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 一条消息。
     *
     * @param role       system / user / assistant / tool
     * @param content    文本内容
     * @param toolCallId 当 role 为 tool 时，这条消息回应的是哪次工具调用
     * @param toolCalls  当 role 为 assistant 且本轮发起了工具调用时，OpenAI 形态的
     *                   {@code tool_calls}；多轮 ReAct 回放上一轮 assistant 时必须带上，
     *                   否则模型对不上后续的 tool 结果
     */
    @Api
    public record ChatMessage(
            String role,
            String content,
            String toolCallId,
            List<ToolCall> toolCalls) {

        public ChatMessage {
            toolCalls = toolCalls == null || toolCalls.isEmpty()
                    ? List.of() : List.copyOf(toolCalls);
        }

        /** 一次助手侧工具调用（序列化进 OpenAI {@code tool_calls}）。 */
        @Api
        public record ToolCall(String id, String name, String arguments) {
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, null, List.of());
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, null, List.of());
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content, null, List.of());
        }

        public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", content, null, toolCalls);
        }

        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content, toolCallId, List.of());
        }
    }
}
