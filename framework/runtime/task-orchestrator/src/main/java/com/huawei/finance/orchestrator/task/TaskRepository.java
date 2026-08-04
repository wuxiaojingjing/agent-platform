package com.huawei.finance.orchestrator.task;

import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.stability.Spi;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Agent 本地任务状态端口；实现必须保证条件状态迁移和幂等键唯一性。 */
@Spi
public interface TaskRepository {
    void insert(TaskRecord task);
    Optional<TaskRecord> findById(String taskId);
    Optional<TaskRecord> findActiveBySession(String agentId, String sessionId);
    Optional<TaskRecord> findBySourceInvocation(String agentId,
                                                com.huawei.finance.contracts.model.Enums.InvocationOrigin origin,
                                                String sourceInvocationId);
    boolean transition(String taskId, TaskState from, TaskState to, String reason, String traceId);
    void updateClarifyState(String taskId, Map<String, Object> parameters, String pendingSlot,
                            List<String> expectedAnswers, int clarifyRounds);
    void updateParameters(String taskId, Map<String, Object> parameters);
    void updateGuardrail(String taskId, GuardrailCheck check);
    boolean attachIdempotencyKey(String taskId, String capabilityId, String idempotencyKey);
    Optional<String> idempotencyKeyOf(String taskId);
    void saveResult(String taskId, TaskResult result);
    Optional<TaskResult> resultOf(String taskId);
    List<String> transitionsOf(String taskId);
}
