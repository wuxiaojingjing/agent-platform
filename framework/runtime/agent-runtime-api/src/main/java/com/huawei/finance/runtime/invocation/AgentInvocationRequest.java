package com.huawei.finance.runtime.invocation;

import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.ResolvedPrincipal;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Api
public record AgentInvocationRequest(
        String tenantId,
        String sourceAgentId,
        String targetAgentId,
        String targetSessionId,
        String rootTaskId,
        String parentTaskId,
        String sourceTaskId,
        List<String> delegationPath,
        String traceId,
        DelegationMode mode,
        ResolvedPrincipal principal,
        String goal,
        String capabilityId,
        Map<String, Object> parameters,
        List<Map<String, Object>> confirmedFacts,
        Instant deadline,
        Enums.TaskSource intentPath,
        Enums.InvocationOrigin invocationOrigin,
        String sourceInvocationId,
        SubtaskContextEnvelope subtaskContext) {

    public AgentInvocationRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        delegationPath = delegationPath == null ? List.of() : List.copyOf(delegationPath);
        invocationOrigin = invocationOrigin == null
                ? Enums.InvocationOrigin.A2A : invocationOrigin;
    }

    public AgentInvocationRequest(
            String tenantId, String sourceAgentId, String targetAgentId,
            String targetSessionId, String rootTaskId, String parentTaskId,
            String sourceTaskId, List<String> delegationPath, String traceId,
            DelegationMode mode, ResolvedPrincipal principal, String goal,
            String capabilityId, Map<String, Object> parameters,
            List<Map<String, Object>> confirmedFacts, Instant deadline,
            Enums.TaskSource intentPath, Enums.InvocationOrigin invocationOrigin,
            String sourceInvocationId) {
        this(tenantId, sourceAgentId, targetAgentId, targetSessionId, rootTaskId,
                parentTaskId, sourceTaskId, delegationPath, traceId, mode, principal,
                goal, capabilityId, parameters, confirmedFacts, deadline, intentPath,
                invocationOrigin, sourceInvocationId, null);
    }

    public AgentInvocationRequest(
            String tenantId, String sourceAgentId, String targetAgentId,
            String targetSessionId, String rootTaskId, String traceId,
            DelegationMode mode, ResolvedPrincipal principal, String goal,
            String capabilityId, Map<String, Object> parameters,
            List<Map<String, Object>> confirmedFacts, Instant deadline,
            String sourceInvocationId) {
        this(tenantId, sourceAgentId, targetAgentId, targetSessionId, rootTaskId, traceId,
                mode, principal, goal, capabilityId, parameters, confirmedFacts, deadline,
                mode == DelegationMode.TASK ? Enums.TaskSource.FAST_PATH : null,
                Enums.InvocationOrigin.A2A, sourceInvocationId);
    }

    public AgentInvocationRequest(
            String tenantId, String sourceAgentId, String targetAgentId,
            String targetSessionId, String rootTaskId, String traceId,
            DelegationMode mode, ResolvedPrincipal principal, String goal,
            String capabilityId, Map<String, Object> parameters,
            List<Map<String, Object>> confirmedFacts, Instant deadline,
            Enums.TaskSource intentPath, Enums.InvocationOrigin invocationOrigin,
            String sourceInvocationId) {
        this(tenantId, sourceAgentId, targetAgentId, targetSessionId, rootTaskId,
                rootTaskId, rootTaskId, List.of(), traceId, mode, principal, goal,
                capabilityId, parameters, confirmedFacts, deadline, intentPath,
                invocationOrigin, sourceInvocationId, null);
    }
}
