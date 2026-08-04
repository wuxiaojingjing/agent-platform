package com.huawei.finance.orchestrator;

import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.orchestrator.task.TaskState;

/**
 * 中控出参。
 *
 * @param taskId      任务标识，未建任务时为 null
 * @param state       任务当前状态
 * @param unifiedTask 实际下发给领域 Agent 的任务，未执行时为 null
 * @param result      领域 Agent 返回，未执行时为 null
 * @param guardrail   护栏结论
 * @param pendingSlot 待澄清槽位
 */
public record OrchestrationOutcome(
        String taskId,
        TaskState state,
        UnifiedTask unifiedTask,
        TaskResult result,
        GuardrailCheck guardrail,
        String pendingSlot) {

    public static OrchestrationOutcome none() {
        return new OrchestrationOutcome(null, null, null, null, GuardrailCheck.pending(), null);
    }

    public boolean executed() {
        return result != null;
    }

    /** 是否存在可执行凭据。测试用它验证「确认前查不到幂等凭据」。 */
    public boolean hasExecutableCredential() {
        return unifiedTask != null && unifiedTask.executable();
    }
}
