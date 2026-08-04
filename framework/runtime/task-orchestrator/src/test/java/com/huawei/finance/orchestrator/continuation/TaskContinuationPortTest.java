package com.huawei.finance.orchestrator.continuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.SwitchMode;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskContinuationPortTest {

    @Test
    void runningTaskCannotSwitchWithoutDurableBackgroundCompletion() {
        var snapshot = new TaskContinuationPort(repository(task(TaskState.RUNNING)))
                .describe("tenant-1", "agent-1", "task-1");

        assertThat(snapshot.allowedEvents()).containsExactly(Event.CONTINUE_CURRENT);
        assertThat(snapshot.switchMode()).isEqualTo(SwitchMode.DENY_SWITCH);
    }

    @Test
    void reviewPendingTaskCanBeSuspendedAndResumed() {
        var snapshot = new TaskContinuationPort(repository(task(TaskState.REVIEW_PENDING)))
                .describe("tenant-1", "agent-1", "task-1");

        assertThat(snapshot.allowedEvents()).containsExactly(
                Event.CORRECTION, Event.REVIEW_ACCEPT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
        assertThat(snapshot.switchMode()).isEqualTo(SwitchMode.ALLOW_SWITCH);
    }

    @Test
    void openClarifySlotIsExposedAsModelResolvedWildcard() {
        TaskRecord task = new TaskRecord("task-1", "agent-1", "trace-1", "session-1", "user-1",
                "cap.creditcard.bill.query", "creditcard", "查信用卡账单", TaskState.CLARIFY_PENDING,
                RiskLevel.R0, Enums.TaskSource.FAST_PATH, Enums.InvocationOrigin.LOCAL, Map.of(),
                "cardRef", List.of(), 1, GuardrailCheck.pending(), null, null, 3);

        var snapshot = new TaskContinuationPort(repository(task))
                .describe("tenant-1", "agent-1", "task-1");

        assertThat(snapshot.allowedSlotsAndValues()).containsEntry("cardRef", List.of("*"));
        assertThat(snapshot.allowedEvents()).contains(Event.FILL_SLOT);
    }

    @Test
    void persistedTaskResultIsExposedAsReadOnlyRuntimeFacts() {
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE, Map.of("balance", "8000"), null, null);

        var snapshot = new TaskContinuationPort(repository(task(TaskState.SUCCEEDED), Optional.of(result)))
                .describe("tenant-1", "agent-1", "task-1");

        assertThat(snapshot.runtimeFacts()).containsEntry("status", "SUCCESS")
                .containsEntry("failureClass", "NONE");
        assertThat(snapshot.runtimeFacts().get("output")).isEqualTo(Map.of("balance", "8000"));
    }

    private static TaskRecord task(TaskState state) {
        return new TaskRecord("task-1", "agent-1", "trace-1", "session-1", "user-1",
                "cap.card.replace", "card", "换卡", state, RiskLevel.R1,
                Enums.TaskSource.FAST_PATH, Enums.InvocationOrigin.LOCAL, Map.of(), null,
                List.of(), 0, GuardrailCheck.pending(), null, null, 3);
    }

    private static TaskRepository repository(TaskRecord task) {
        return repository(task, Optional.empty());
    }

    private static TaskRepository repository(TaskRecord task, Optional<TaskResult> result) {
        return new TaskRepository() {
            @Override public void insert(TaskRecord value) { }
            @Override public Optional<TaskRecord> findById(String taskId) { return Optional.of(task); }
            @Override public Optional<TaskRecord> findActiveBySession(String agentId, String sessionId) {
                return Optional.of(task);
            }
            @Override public Optional<TaskRecord> findBySourceInvocation(
                    String agentId, Enums.InvocationOrigin origin, String sourceInvocationId) {
                return Optional.empty();
            }
            @Override public boolean transition(String taskId, TaskState from, TaskState to,
                                                String reason, String traceId) { return false; }
            @Override public void updateClarifyState(String taskId, Map<String, Object> parameters,
                                                     String pendingSlot, List<String> expectedAnswers,
                                                     int clarifyRounds) { }
            @Override public void updateParameters(String taskId, Map<String, Object> parameters) { }
            @Override public void updateGuardrail(String taskId, GuardrailCheck check) { }
            @Override public boolean attachIdempotencyKey(String taskId, String capabilityId,
                                                          String idempotencyKey) { return false; }
            @Override public Optional<String> idempotencyKeyOf(String taskId) { return Optional.empty(); }
            @Override public void saveResult(String taskId, TaskResult result) { }
            @Override public Optional<TaskResult> resultOf(String taskId) { return result; }
            @Override public List<String> transitionsOf(String taskId) { return List.of(); }
        };
    }
}
