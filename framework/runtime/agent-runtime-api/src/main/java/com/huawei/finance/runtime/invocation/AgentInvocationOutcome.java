package com.huawei.finance.runtime.invocation;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

@Api
public record AgentInvocationOutcome(
        String taskId,
        String state,
        TaskResult result,
        Map<String, Object> facts,
        List<String> missingSlots,
        String reasonCode,
        Enums.TaskSource intentPath,
        Enums.InvocationOrigin invocationOrigin,
        ContextDelta contextDelta) {

    public AgentInvocationOutcome {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    public AgentInvocationOutcome(
            String taskId, String state, TaskResult result, Map<String, Object> facts,
            List<String> missingSlots, String reasonCode, Enums.TaskSource intentPath,
            Enums.InvocationOrigin invocationOrigin) {
        this(taskId, state, result, facts, missingSlots, reasonCode, intentPath,
                invocationOrigin, null);
    }

    public AgentInvocationOutcome(
            String taskId, String state, TaskResult result, Map<String, Object> facts,
            List<String> missingSlots, String reasonCode) {
        this(taskId, state, result, facts, missingSlots, reasonCode, null,
                Enums.InvocationOrigin.A2A, null);
    }

    public static AgentInvocationOutcome rejected(String reasonCode) {
        return new AgentInvocationOutcome(null, "REJECTED", null, Map.of(), List.of(), reasonCode,
                null, Enums.InvocationOrigin.A2A);
    }
}
