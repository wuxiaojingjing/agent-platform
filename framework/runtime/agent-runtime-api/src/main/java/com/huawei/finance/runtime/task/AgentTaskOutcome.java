package com.huawei.finance.runtime.task;

import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.stability.Api;

/** 子任务经过中控后的稳定结果视图，不暴露仓储状态或内部任务记录。 */
@Api
public record AgentTaskOutcome(
        String taskId,
        TaskResult result,
        GuardrailCheck guardrail,
        String orchestrationState) {

    public AgentTaskOutcome(String taskId, TaskResult result, GuardrailCheck guardrail) {
        this(taskId, result, guardrail, null);
    }

    public boolean executed() {
        return result != null;
    }
}
