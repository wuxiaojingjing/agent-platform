package com.huawei.finance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultRuntimeRegistrationCompensatorTest {

    @Test
    void cancelsActiveTaskThroughTaskRepository() {
        AtomicReference<TaskState> transitionedTo = new AtomicReference<>();
        TaskRecord active = task("task-1", TaskState.REVIEW_PENDING);
        DefaultRuntimeRegistrationCompensator compensator = new DefaultRuntimeRegistrationCompensator(
                tasks(active, transitionedTo), plans(), Optional.empty());

        compensator.compensate(context(), RuntimeType.TASK, active.taskId(),
                "PLATFORM_REGISTRATION_FAILED");

        assertThat(transitionedTo.get()).isEqualTo(TaskState.CANCELLED);
    }

    @Test
    void cancelsActiveLoopThroughLoopRepository() {
        AtomicReference<Status> transitionedTo = new AtomicReference<>();
        Run run = new Run("tenant-a", "loop-1", "agent-a", "session-a", "pt-1", "trace-1",
                "排查工资", Status.WAITING_USER, 1, 8, List.of(), Map.of(), null,
                Instant.now().plusSeconds(30), 3, Instant.now(), Instant.now());
        AgentLoopRepository loops = loops(run, transitionedTo);
        DefaultRuntimeRegistrationCompensator compensator = new DefaultRuntimeRegistrationCompensator(
                tasks(null, new AtomicReference<>()), plans(), Optional.of(loops));

        compensator.compensate(context(), RuntimeType.AGENT_LOOP, run.loopId(),
                "PLATFORM_REGISTRATION_FAILED");

        assertThat(transitionedTo.get()).isEqualTo(Status.CANCELLED);
    }

    private static RequestContext context() {
        return new RequestContext("trace-1", "session-a", "user-a", "tenant-a", "agent-a",
                "APP", "home", "AUTH", false);
    }

    private static TaskRecord task(String id, TaskState state) {
        return new TaskRecord(id, "agent-a", "trace-1", "session-a", "user-a", "cap.card.replace",
                "card", "换卡", state, RiskLevel.R1, Enums.TaskSource.FAST_PATH, Map.of(), null,
                List.of(), 0, GuardrailCheck.pending(), null, null);
    }

    private static TaskRepository tasks(TaskRecord task, AtomicReference<TaskState> transitionedTo) {
        return new TaskRepository() {
            @Override public void insert(TaskRecord value) { }
            @Override public Optional<TaskRecord> findById(String taskId) { return Optional.ofNullable(task); }
            @Override public Optional<TaskRecord> findActiveBySession(String agentId, String sessionId) { return Optional.empty(); }
            @Override public Optional<TaskRecord> findBySourceInvocation(String agentId, Enums.InvocationOrigin origin,
                                                                         String sourceInvocationId) { return Optional.empty(); }
            @Override public boolean transition(String taskId, TaskState from, TaskState to, String reason,
                                                String traceId) { transitionedTo.set(to); return true; }
            @Override public void updateClarifyState(String taskId, Map<String,Object> parameters, String pendingSlot,
                                                     List<String> expectedAnswers, int clarifyRounds) { }
            @Override public void updateParameters(String taskId, Map<String,Object> parameters) { }
            @Override public void updateGuardrail(String taskId, GuardrailCheck check) { }
            @Override public boolean attachIdempotencyKey(String taskId, String capabilityId, String key) { return false; }
            @Override public Optional<String> idempotencyKeyOf(String taskId) { return Optional.empty(); }
            @Override public void saveResult(String taskId, TaskResult result) { }
            @Override public Optional<TaskResult> resultOf(String taskId) { return Optional.empty(); }
            @Override public List<String> transitionsOf(String taskId) { return List.of(); }
        };
    }

    private static IntentPlanRepository plans() {
        return new IntentPlanRepository() {
            @Override public PlanRecord open(String agentId, String sessionId, String traceId, IntentPlan plan) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<PlanRecord> findActiveBySession(String agentId, String sessionId) {
                return Optional.empty();
            }
            @Override public boolean advance(String planId, int from) { return false; }
            @Override public void abandonActive(String agentId, String sessionId, String reason) { }
        };
    }

    private static AgentLoopRepository loops(Run run, AtomicReference<Status> transitionedTo) {
        return new AgentLoopRepository() {
            @Override public Run open(StartRequest request) { throw new UnsupportedOperationException(); }
            @Override public Optional<Run> find(String tenantId, String agentId, String loopId) {
                return Optional.of(run);
            }
            @Override public List<Step> steps(String tenantId, String agentId, String loopId) { return List.of(); }
            @Override public boolean propose(String tenantId, String agentId, String loopId, long version,
                                             Action action) { return false; }
            @Override public boolean claim(String tenantId, String agentId, String loopId, int step,
                                           long version) { return false; }
            @Override public boolean recoverClaimed(String tenantId, String agentId, String loopId, int step,
                    long version, java.time.Instant claimedBefore, String reasonCode) { return false; }
            @Override public boolean waitForInput(String tenantId, String agentId, String loopId, long version,
                                                  List<String> pendingSlots, String reasonCode) { return false; }
            @Override public Run resume(String tenantId, String agentId, String loopId, long version,
                                        Status waitingStatus, Map<String,Object> slotUpdates) { return run; }
            @Override public Run complete(String tenantId, String agentId, String loopId, int step,
                                          long version, Observation observation, Status next) { return run; }
            @Override public boolean transition(String tenantId, String agentId, String loopId, long version,
                                                Status from, Status to, String reasonCode) {
                transitionedTo.set(to); return true;
            }
        };
    }
}
