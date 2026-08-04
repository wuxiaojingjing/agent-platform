package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.product.mobilebanking.api.TenantHeaders;
import com.huawei.finance.runtime.AgentRequest;
import com.huawei.finance.runtime.AgentResponse;
import com.huawei.finance.runtime.AgentRuntime;
import org.springframework.stereotype.Service;

/**
 * 入口会话服务：渠道 DTO ↔ {@link AgentRuntime} 的薄适配。
 *
 * <p>通用流水线在 {@code agent-runtime-core}（v0.5 §17.3）。
 */
@Service
public class ChatService {

    private final AgentRuntime runtime;

    public ChatService(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /** 无租户头的调用（测试与离线回放）：按请求自身信息补一个最小租户上下文。 */
    public ChatResponseDto chat(ChatRequestDto request) {
        return chat(request, new TenantHeaders(request.userId(), "-", request.channel()));
    }

    public ChatResponseDto chat(ChatRequestDto request, TenantHeaders tenant) {
        AgentResponse response = runtime.handle(new AgentRequest(
                request.sessionId(),
                request.query(),
                tenant.userId(),
                tenant.spaceId(),
                tenant.channel(),
                request.page(),
                request.userState(), java.util.Map.of(), request.action(), null, null));
        return toDto(response);
    }

    private static ChatResponseDto toDto(AgentResponse response) {
        return new ChatResponseDto(
                response.traceId(),
                response.text(),
                response.decision(),
                response.plan(),
                response.taskId(),
                response.usedTemplate(),
                response.fellBack(),
                response.degradedChannels(),
                response.actions());
    }
}
