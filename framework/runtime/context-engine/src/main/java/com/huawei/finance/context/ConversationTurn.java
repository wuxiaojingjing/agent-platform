package com.huawei.finance.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的落库形态。
 *
 * <p>判定、结构化事实和当时实际展示的消息分开保存。助手消息必须保存精确渲染文本、
 * 可见卡片数据和操作按钮，不能在下一轮按新版模板重建，否则模型所见会偏离用户所见。
 *
 * <p>{@code facts} 只承载可追溯的执行事实；自由文本只允许出现在用户/助手消息中，
 * 不能因为出现在对话里就自动成为可执行参数（FP-28）。
 *
 * <p>标 {@link Api} 是因为它出现在 {@link TurnStore} 的签名上：换一个存储实现的人必须
 * 照着同一份字段写，改字段就是改所有实现方的契约。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record ConversationTurn(
        String tenantId,
        String agentId,
        String sessionId,
        long seq,
        String traceId,
        String taskId,
        String userText,
        Decision decision,
        ReasonCode reasonCode,
        String capabilityId,
        Enums.ToolOutcome outcome,
        Enums.PendingAction pending,
        List<String> pendingOptions,
        Map<String, Object> facts,
        Instant at,
        List<Message> messages) {

    public ConversationTurn {
        tenantId = tenantId == null || tenantId.isBlank()
                ? RequestContext.SPACE_UNSCOPED : tenantId;
        agentId = agentId == null || agentId.isBlank() ? RequestContext.AGENT_ENTRY : agentId;
        pendingOptions = pendingOptions == null ? List.of() : List.copyOf(pendingOptions);
        facts = facts == null ? Map.of() : new LinkedHashMap<>(facts);
        messages = messages == null ? List.of() : List.copyOf(messages);
        pending = pending == null ? Enums.PendingAction.NONE : pending;
        at = at == null ? Instant.now() : at;

        if (seq < 0) {
            throw new IllegalArgumentException("轮次序号不得为负：" + seq);
        }
    }

    /** Offline/test compatibility; online callers must pass tenantId explicitly. */
    public ConversationTurn(
            String tenantId, String agentId, String sessionId, long seq, String traceId,
            String taskId, String userText, Decision decision, ReasonCode reasonCode,
            String capabilityId, Enums.ToolOutcome outcome, Enums.PendingAction pending,
            List<String> pendingOptions, Map<String, Object> facts, Instant at) {
        this(tenantId, agentId, sessionId, seq, traceId, taskId, userText, decision, reasonCode,
                capabilityId, outcome, pending, pendingOptions, facts, at, List.of());
    }

    public ConversationTurn(
            String agentId, String sessionId, long seq, String traceId, String taskId,
            String userText, Decision decision, ReasonCode reasonCode, String capabilityId,
            Enums.ToolOutcome outcome, Enums.PendingAction pending, List<String> pendingOptions,
            Map<String, Object> facts, Instant at) {
        this(RequestContext.SPACE_UNSCOPED, agentId, sessionId, seq, traceId, taskId, userText,
                decision, reasonCode, capabilityId, outcome, pending, pendingOptions, facts, at,
                List.of());
    }

    public enum MessageRole { USER, ASSISTANT, TOOL, AGENT }
    public enum MessageType { TEXT, TOOL_CALL, TOOL_RESULT, AGENT_RESULT }

    /** Append-only conversation item. User-visible content must always be model-visible. */
    public record Message(String messageId, MessageRole role, MessageType type, String callId,
                          String name, String text, Map<String, Object> data,
                          boolean userVisible, boolean modelVisible) {
        public Message {
            data = data == null ? Map.of() : Map.copyOf(data);
            if (userVisible && !modelVisible) {
                throw new IllegalArgumentException("USER_VISIBLE_MESSAGE_MUST_BE_MODEL_VISIBLE");
            }
        }
    }

    /** 该轮是否留下了待办。留了待办的轮次在裁剪时优先保留——它是续轮判断的依据。 */
    public boolean hasPending() {
        return pending != Enums.PendingAction.NONE;
    }

    /** 该轮是否产出过可被后续引用的工具结论。 */
    public boolean hasToolConclusion() {
        return outcome != null;
    }
}
