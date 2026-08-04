package com.huawei.finance.orchestrator.continuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.SwitchMode;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanStepRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StaticPlanContinuationPortTest {

    @Test
    void waitingInputExposesOnlyRuntimeOwnedSlotAndRealVersion() {
        PlanRecord record = new PlanRecord("plan-1", "agent-1", "session-1", "trace-1",
                plan(), 1, PlanState.WAITING_USER, 7,
                new PlanRecord.PendingInteraction(null, "conditionDecision",
                        List.of("继续办理", "不办理")));
        var snapshot = new StaticPlanContinuationPort(repository(record))
                .describe("tenant-1", "agent-1", "plan-1");

        assertThat(snapshot.stateVersion()).isEqualTo(7);
        assertThat(snapshot.allowedEvents()).containsExactly(
                Event.FILL_SLOT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
        assertThat(snapshot.pendingInteraction().expectedSlot()).isEqualTo("conditionDecision");
        assertThat(snapshot.allowedSlotsAndValues())
                .containsEntry("conditionDecision", List.of("继续办理", "不办理"));
    }

    @Test
    void openPlanInputIsExposedAsModelResolvedWildcard() {
        PlanRecord record = new PlanRecord("plan-1", "agent-1", "session-1", "trace-1",
                plan(), 1, PlanState.WAITING_USER, 8,
                new PlanRecord.PendingInteraction(null, "payee", List.of()));

        var snapshot = new StaticPlanContinuationPort(repository(record))
                .describe("tenant-1", "agent-1", "plan-1");

        assertThat(snapshot.pendingInteraction().expectedSlot()).isEqualTo("payee");
        assertThat(snapshot.allowedSlotsAndValues()).containsEntry("payee", List.of("*"));
        assertThat(snapshot.allowedEvents()).contains(Event.FILL_SLOT);
    }

    @Test
    void waitingConfirmationAllowsOnlyConfirmCancelAndSwitch() {
        PlanRecord record = new PlanRecord("plan-1", "agent-1", "session-1", "trace-1",
                plan(), 1, PlanState.WAITING_CONFIRMATION, 3,
                new PlanRecord.PendingInteraction("task-transfer", null, List.of()));

        var snapshot = new StaticPlanContinuationPort(repository(record))
                .describe("tenant-1", "agent-1", "plan-1");

        assertThat(snapshot.allowedEvents()).containsExactly(
                Event.CONFIRM, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
        assertThat(snapshot.pendingInteraction().type()).isEqualTo("CONFIRM");
    }

    @Test
    void inProgressPlanCanSwitchBecauseItsCursorAndFactsAreDurable() {
        PlanRecord record = new PlanRecord("plan-1", "agent-1", "session-1", "trace-1",
                plan(), 1, PlanState.IN_PROGRESS, 4, null);

        var snapshot = new StaticPlanContinuationPort(repository(record))
                .describe("tenant-1", "agent-1", "plan-1");

        assertThat(snapshot.allowedEvents())
                .containsExactly(Event.CONTINUE_CURRENT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
        assertThat(snapshot.switchMode()).isEqualTo(SwitchMode.ALLOW_SWITCH);
    }

    @Test
    void recentPlanStepFactsAreExposedInRuntimeSnapshot() {
        PlanRecord record = new PlanRecord("plan-1", "agent-1", "session-1", "trace-1",
                plan(), 1, PlanState.IN_PROGRESS, 4, null);
        IntentPlanRepository repository = repository(record, List.of(
                new PlanStepRecord("plan-1", 0, "cap.balance", "task-1",
                        Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                        Map.of("balance", "8000"), "OK", Instant.now())));

        var snapshot = new StaticPlanContinuationPort(repository)
                .describe("tenant-1", "agent-1", "plan-1");

        assertThat(snapshot.runtimeFacts().get("recentSteps")).asList().singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("facts", Map.of("balance", "8000"));
    }

    private static IntentPlanRepository repository(PlanRecord record) {
        return repository(record, List.of());
    }

    private static IntentPlanRepository repository(PlanRecord record, List<PlanStepRecord> steps) {
        return new IntentPlanRepository() {
            @Override public PlanRecord open(String agentId, String sessionId, String traceId, IntentPlan plan) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<PlanRecord> findActiveBySession(String agentId, String sessionId) {
                return Optional.of(record);
            }
            @Override public Optional<PlanRecord> findById(String agentId, String planId) {
                return Optional.of(record);
            }
            @Override public boolean advance(String planId, int from) { return false; }
            @Override public void abandonActive(String agentId, String sessionId, String reason) { }
            @Override public List<PlanStepRecord> steps(String planId) { return steps; }
        };
    }

    private static IntentPlan plan() {
        return new IntentPlan("查余额，然后转账", List.of(
                new SubIntent(0, "查余额", "cap.balance", "余额查询",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked("cap.balance", "test")),
                new SubIntent(1, "转账", "cap.transfer", "转账",
                        Enums.IntentRelation.SEQUENTIAL, null,
                        PlanResolution.locked("cap.transfer", "test"))), IntentPlan.Source.RULE);
    }
}
