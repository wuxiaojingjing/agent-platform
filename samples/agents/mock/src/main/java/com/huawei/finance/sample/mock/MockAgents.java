package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.util.Map;

/** Mock 领域 Agent 共用的契约守门。 */
final class MockAgents {

    private MockAgents() {
    }

    static TaskResult missingIdempotency(UnifiedTask task) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL,
                Map.of("error", "MISSING_IDEMPOTENCY_KEY"), null, task.guardrailCheck());
    }

    static TaskResult success(UnifiedTask task, Map<String, Object> payload) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                payload, task.idempotencyKey(), task.guardrailCheck());
    }

    static TaskResult failed(UnifiedTask task, String error) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL,
                Map.of("error", error), task.idempotencyKey(), task.guardrailCheck());
    }
}
