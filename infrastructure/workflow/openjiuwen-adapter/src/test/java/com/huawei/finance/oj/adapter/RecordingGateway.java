package com.huawei.finance.oj.adapter;

import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.gateway.ToolChatReply;
import com.huawei.finance.gateway.ToolChatRequest;
import java.util.ArrayList;
import java.util.List;

/** 记下每一次调用，用来验「OJ 侧发出的请求确实落到了这条通道上」。 */
class RecordingGateway implements ModelGatewayClient {

    final List<ChatRequest> chats = new ArrayList<>();
    final List<List<String>> embedInputs = new ArrayList<>();
    final List<ToolChatRequest> toolChats = new ArrayList<>();

    /** 下一次 chatWithTools 要回的工具调用，为空则回一句文本。 */
    List<ToolChatReply.ToolCallRequest> nextToolCalls = List.of();

    private final boolean available;
    private final String chatReply;

    RecordingGateway() {
        this(true, "好的");
    }

    RecordingGateway(boolean available, String chatReply) {
        this.available = available;
        this.chatReply = chatReply;
    }

    @Override
    public GatewayResult<List<float[]>> embed(List<String> inputs) {
        embedInputs.add(List.copyOf(inputs));
        if (!available) {
            return GatewayResult.unavailable("测试设定为不可用", 1);
        }
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            vectors.add(new float[] {0.1f * (i + 1), 0.2f});
        }
        return GatewayResult.ok(vectors, 1);
    }

    @Override
    public GatewayResult<String> chat(ChatRequest request) {
        chats.add(request);
        return available ? GatewayResult.ok(chatReply, 1) : GatewayResult.unavailable("测试设定为不可用", 1);
    }

    @Override
    public GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
        toolChats.add(request);
        if (!available) {
            return GatewayResult.unavailable("测试设定为不可用", 1);
        }
        return GatewayResult.ok(new ToolChatReply(chatReply, nextToolCalls), 1);
    }

    @Override
    public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
        return GatewayResult.unavailable("测试不涉及重排", 0);
    }

    @Override
    public boolean available() {
        return available;
    }
}
