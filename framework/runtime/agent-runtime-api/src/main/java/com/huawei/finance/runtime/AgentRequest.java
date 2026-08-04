package com.huawei.finance.runtime;

import com.huawei.finance.common.context.InvocationLineage;
import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.stability.Api;
import java.util.Map;

/**
 * 进入 {@link AgentRuntime} 的一轮用户请求（渠道无关）。
 */
@Api
public record AgentRequest(
        String sessionId,
        String query,
        String userId,
        String spaceId,
        String channel,
        String page,
        String userState,
        Map<String, String> attributes,
        ActionEvent action,
        PrincipalState principal,
        InvocationLineage lineage,
        SubtaskContextEnvelope subtaskContext) {

    public AgentRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        userId = userId == null ? "" : userId;
        spaceId = spaceId == null ? "-" : spaceId;
        channel = channel == null ? "" : channel;
        page = page == null ? "" : page;
        userState = userState == null ? "" : userState;
    }

    public AgentRequest(
            String sessionId, String query, String userId, String spaceId,
            String channel, String page, String userState, Map<String, String> attributes) {
        this(sessionId, query, userId, spaceId, channel, page, userState, attributes,
                null, null, null, null);
    }

    public AgentRequest(
            String sessionId, String query, String userId, String spaceId,
            String channel, String page, String userState, Map<String, String> attributes,
            PrincipalState principal, InvocationLineage lineage) {
        this(sessionId, query, userId, spaceId, channel, page, userState,
                attributes, null, principal, lineage, null);
    }

    public static AgentRequest of(String sessionId, String query, String userId,
                                  String spaceId, String channel, String page, String userState) {
        return new AgentRequest(sessionId, query, userId, spaceId, channel, page, userState,
                Map.of(), null, null, null, null);
    }

    public AgentRequest(
            String sessionId, String query, String userId, String spaceId,
            String channel, String page, String userState, Map<String, String> attributes,
            ActionEvent action, PrincipalState principal, InvocationLineage lineage) {
        this(sessionId, query, userId, spaceId, channel, page, userState, attributes,
                action, principal, lineage, null);
    }
}
