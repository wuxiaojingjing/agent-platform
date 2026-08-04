package com.huawei.finance.domain.finance;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.util.Map;

/** 本域叶子共用的契约守门。 */
final class DomainAgents {

    private DomainAgents() {
    }

    static TaskResult missingIdempotency(UnifiedTask task) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL,
                Map.of("error", "MISSING_IDEMPOTENCY_KEY"), null, task.guardrailCheck());
    }

    static TaskResult success(UnifiedTask task, Map<String, Object> payload) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                payload, task.idempotencyKey(), task.guardrailCheck());
    }
}
