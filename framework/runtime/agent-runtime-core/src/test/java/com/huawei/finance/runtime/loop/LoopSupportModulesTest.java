package com.huawei.finance.runtime.loop;

import static com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import static com.huawei.finance.orchestrator.loop.LoopContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoopSupportModulesTest {
    @Test
    void contextBuilderCombinesLoopOwnedAndCurrentTurnSlotsWithinBudget() {
        Observation observation = new Observation(ObservationStatus.SUCCESS, "KNOWLEDGE", "qa-1",
                Map.of("knowledgeId", "qa-1"), "KNOWLEDGE_MATCHED", null,
                null, null, false, Map.of("answer", "这段展示文本不得进入 Planner"));
        Step prior = new Step("loop", 0, action(ActionType.SEARCH_KNOWLEDGE),
                StepStatus.COMPLETED, null, null, observation, null, Instant.now(), Instant.now());
        Run run = run(Status.RUNNING, 2, 7, Map.of("cardType", "CREDIT"), List.of(), null);

        LoopContext context = new LoopContextBuilder().build(run,
                Map.of("branch", "001", "cardType", "DEBIT"), List.of(prior), List.of());

        assertThat(context.confirmedSlots()).containsEntry("branch", "001")
                .containsEntry("cardType", "DEBIT");
        assertThat(context.lastObservation().facts()).containsEntry("knowledgeId", "qa-1");
        assertThat(context.lastObservation().displayHints()).isEmpty();
        assertThat(context.remainingIterations()).isEqualTo(5);
    }

    @Test
    void stateMachineMapsWaitsObservationsAndResponsePhases() {
        LoopStateMachine machine = new LoopStateMachine();

        assertThat(machine.waitingStatus(check(Verdict.WAIT_USER))).isEqualTo(Status.WAITING_USER);
        assertThat(machine.waitingStatus(check(Verdict.WAIT_REVIEW))).isEqualTo(Status.WAITING_REVIEW);
        assertThat(machine.waitingStatus(check(Verdict.WAIT_CONFIRMATION)))
                .isEqualTo(Status.WAITING_CONFIRMATION);
        assertThat(machine.afterObservation(action(ActionType.CALL_CAPABILITY),
                observation(ObservationStatus.NEED_USER), run(Status.RUNNING, 0, 3, Map.of(), List.of(), null)))
                .isEqualTo(Status.WAITING_USER);
        assertThat(machine.afterObservation(action(ActionType.FINISH),
                observation(ObservationStatus.SUCCESS), run(Status.RUNNING, 0, 3, Map.of(), List.of(), null)))
                .isEqualTo(Status.COMPLETED);
        assertThat(machine.afterObservation(action(ActionType.CALL_CAPABILITY),
                observation(ObservationStatus.SUCCESS), run(Status.RUNNING, 2, 3, Map.of(), List.of(), null)))
                .isEqualTo(Status.FAILED);
        Observation retryableFailure = new Observation(ObservationStatus.FAILED, "CAPABILITY", "cap.x",
                Map.of(), "DEPENDENCY_UNAVAILABLE", "RETRYABLE", null, null, true, Map.of());
        assertThat(machine.afterObservation(action(ActionType.CALL_CAPABILITY), retryableFailure,
                run(Status.RUNNING, 0, 3, Map.of(), List.of(), null))).isEqualTo(Status.RUNNING);
        assertThat(machine.afterObservation(action(ActionType.CALL_CAPABILITY), retryableFailure,
                run(Status.RUNNING, 2, 3, Map.of(), List.of(), null))).isEqualTo(Status.FAILED);
        assertThat(machine.responsePhase(Status.WAITING_REVIEW)).isEqualTo("REVIEW");
        assertThat(machine.responsePhase(Status.COMPLETED)).isEqualTo("FINAL");
    }

    @Test
    void continuationViewExposesOnlyStateValidEventsAndPendingSlotWhitelist() {
        MutableRepository repository = new MutableRepository(
                run(Status.WAITING_USER, 1, 4, Map.of(), List.of("cardType"), null));
        LoopContinuationViewProvider views = new LoopContinuationViewProvider(repository);

        Snapshot waiting = views.describe("tenant", "agent", "loop");
        assertThat(waiting.allowedEvents()).containsExactly(Event.FILL_SLOT, Event.CANCEL,
                Event.SWITCH_TO_NEW_GOAL);
        assertThat(waiting.allowedSlotsAndValues()).containsOnlyKeys("cardType");
        assertThat(waiting.pendingInteraction().expectedSlot()).isEqualTo("cardType");
        assertThat(waiting.switchMode()).isEqualTo(SwitchMode.ALLOW_SWITCH);

        repository.run = run(Status.RUNNING, 1, 4, Map.of(), List.of(), null);
        Snapshot running = views.describe("tenant", "agent", "loop");
        assertThat(running.allowedEvents()).containsExactly(Event.CONTINUE_CURRENT, Event.CANCEL);
        assertThat(running.allowedEvents()).doesNotContain(Event.SWITCH_TO_NEW_GOAL);
        assertThat(running.switchMode()).isEqualTo(SwitchMode.DENY_SWITCH);

        repository.run = run(Status.WAITING_REVIEW, 1, 4, Map.of(), List.of(), null);
        assertThat(views.describe("tenant", "agent", "loop").allowedEvents())
                .containsExactly(Event.REVIEW_ACCEPT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
        repository.run = run(Status.WAITING_CONFIRMATION, 1, 4, Map.of(), List.of(), null);
        assertThat(views.describe("tenant", "agent", "loop").allowedEvents())
                .containsExactly(Event.CONFIRM, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL);
    }

    @Test
    void resumeAdapterAppliesFillReviewConfirmAndCancelWithCas() {
        assertResume(Status.WAITING_USER, Event.FILL_SLOT, Map.of("cardType", "CREDIT"));
        assertResume(Status.WAITING_REVIEW, Event.REVIEW_ACCEPT, Map.of());
        assertResume(Status.WAITING_CONFIRMATION, Event.CONFIRM, Map.of());

        MutableRepository cancelRepository = new MutableRepository(
                run(Status.WAITING_REVIEW, 1, 4, Map.of(), List.of(), null));
        Run cancelled = new LoopResumeAdapter(cancelRepository).apply("tenant", "agent", "loop",
                resolution(Event.CANCEL, Map.of()), 7);
        assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);

        MutableRepository staleRepository = new MutableRepository(
                run(Status.WAITING_USER, 1, 4, Map.of(), List.of("cardType"), null));
        assertThatThrownBy(() -> new LoopResumeAdapter(staleRepository).apply(
                "tenant", "agent", "loop", resolution(Event.FILL_SLOT, Map.of("cardType", "CREDIT")), 6))
                .isInstanceOf(IllegalStateException.class).hasMessage("LOOP_RESUME_CONFLICT");
        assertThatThrownBy(() -> new LoopResumeAdapter(staleRepository).apply(
                "tenant", "agent", "loop", resolution(Event.CONFIRM, Map.of()), 7))
                .isInstanceOf(IllegalStateException.class).hasMessage("LOOP_RESUME_STATE_MISMATCH");
    }

    private static void assertResume(Status status, Event event, Map<String,Object> slots) {
        MutableRepository repository = new MutableRepository(run(status, 1, 4, Map.of(),
                status == Status.WAITING_USER ? List.of("cardType") : List.of(), null));
        Run resumed = new LoopResumeAdapter(repository).apply("tenant", "agent", "loop",
                resolution(event, slots), 7);
        assertThat(resumed.status()).isEqualTo(Status.RUNNING);
        assertThat(resumed.version()).isEqualTo(8);
        if (!slots.isEmpty()) assertThat(resumed.confirmedSlots()).containsAllEntriesOf(slots);
    }

    private static Resolution resolution(Event event, Map<String,Object> slots) {
        return new Resolution(event, "loop", slots, null, 1, "TEST");
    }

    private static Action action(ActionType type) {
        return new Action(type, null, Map.of(), Map.of(), "TEST", type.name());
    }

    private static ActionCheck check(Verdict verdict) {
        return new ActionCheck(verdict, "TEST", List.of(), Map.of(), "R0", null, false);
    }

    private static Observation observation(ObservationStatus status) {
        return new Observation(status, "TEST", "source", Map.of(), "TEST", null,
                null, null, false, Map.of());
    }

    private static Run run(Status status, int iteration, int maxIterations,
                           Map<String,Object> confirmedSlots, List<String> pendingSlots, Action pendingAction) {
        Instant now = Instant.now();
        return new Run("tenant", "loop", "agent", "session", "root", "trace", "goal",
                status, iteration, maxIterations, List.of("cap.x"), confirmedSlots, Map.of(), pendingAction,
                pendingSlots, now.plusSeconds(30), 7, now, now);
    }

    private static final class MutableRepository implements AgentLoopRepository {
        private Run run;
        private MutableRepository(Run run) { this.run = run; }
        @Override public Run open(StartRequest request) { throw new UnsupportedOperationException(); }
        @Override public Optional<Run> find(String tenant, String agent, String loop) {
            return run.tenantId().equals(tenant) && run.agentId().equals(agent) && run.loopId().equals(loop)
                    ? Optional.of(run) : Optional.empty();
        }
        @Override public List<Step> steps(String tenant, String agent, String loop) { return List.of(); }
        @Override public boolean propose(String tenant, String agent, String loop, long version, Action action) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean claim(String tenant, String agent, String loop, int step, long version) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean recoverClaimed(String tenant, String agent, String loop, int step,
                                                long version, Instant before, String reason) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean waitForInput(String tenant, String agent, String loop, long version,
                                              List<String> slots, String reason) {
            throw new UnsupportedOperationException();
        }
        @Override public Run resume(String tenant, String agent, String loop, long version,
                                    Status waitingStatus, Map<String,Object> slots) {
            if (run.version() != version || run.status() != waitingStatus) {
                throw new IllegalStateException("LOOP_RESUME_CONFLICT");
            }
            var confirmed = new java.util.LinkedHashMap<>(run.confirmedSlots());
            confirmed.putAll(slots);
            run = copy(Status.RUNNING, confirmed, List.of(), null, run.version() + 1);
            return run;
        }
        @Override public Run complete(String tenant, String agent, String loop, int step, long version,
                                      Observation observation, Status nextStatus) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean transition(String tenant, String agent, String loop, long version,
                                            Status from, Status to, String reason) {
            if (run.version() != version || run.status() != from) return false;
            run = copy(to, run.confirmedSlots(), run.pendingSlots(), run.pendingAction(), version + 1);
            return true;
        }
        private Run copy(Status status, Map<String,Object> confirmed, List<String> pendingSlots,
                         Action pendingAction, long version) {
            return new Run(run.tenantId(), run.loopId(), run.agentId(), run.sessionId(), run.rootTaskId(),
                    run.traceId(), run.goal(), status, run.iteration(), run.maxIterations(), run.candidateIds(),
                    confirmed, run.facts(), pendingAction, pendingSlots, run.deadline(), version,
                    run.createdAt(), Instant.now());
        }
    }
}
