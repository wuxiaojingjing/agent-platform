package com.huawei.finance.product.mobilebanking.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.AgentProperties;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.intent.PathSummary;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConsoleLoopExecutionProjectionTest {

    @Test
    void recentProjectsAuthoritativeLoopRunAndRedactedObservations() {
        RecentDecisions recent = new RecentDecisions();
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.START_LOOP)
                .reasonCode(ReasonCode.AFTER_OBSERVATION)
                .build();
        recent.record("trace-1", "session-1", "帮我排查", decision, "loop-1",
                "tpl.loop.handoff", false, List.of(), 120L,
                PathSummary.empty(), List.of(), List.of());

        Instant now = Instant.now();
        LoopContracts.Run run = new LoopContracts.Run(
                "default", "loop-1", "agent.mobile-banking-assistant", "session-1",
                "root-1", "trace-1", "goal", LoopContracts.Status.HANDED_OFF,
                2, 4, List.of("cap.x"), Map.of(), Map.of(), null, List.of(),
                now.plusSeconds(30), 7, now, now);
        LoopContracts.Action action = new LoopContracts.Action(
                LoopContracts.ActionType.CALL_CAPABILITY, "cap.x", Map.of(), Map.of(),
                "CHECK_PRIMARY", "fingerprint");
        LoopContracts.Observation observation = new LoopContracts.Observation(
                LoopContracts.ObservationStatus.FAILED, "CAPABILITY", "cap.x", Map.of(),
                "RETRYABLE", "RETRYABLE", "task-1", null, true, Map.of());
        LoopContracts.Step step = new LoopContracts.Step(
                "loop-1", 0, action, LoopContracts.StepStatus.FAILED, "task-1", null,
                observation, "RETRYABLE", now, now);

        AgentLoopRepository loops = new ReadOnlyLoopRepository(run, List.of(step),
                "RETRYABLE_ACTION_EXHAUSTED");

        ConsoleProperties properties = new ConsoleProperties();
        AgentProperties agent = new AgentProperties();
        agent.setId("agent.mobile-banking-assistant");
        ConsoleController controller = new ConsoleController(
                null, null, null, null, null, recent, null, properties, agent,
                null, null, null, Optional.of(loops));

        Map<String, Object> entry = controller.recent().getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) entry.get("loopExecution");
        assertThat(execution)
                .containsEntry("status", "HANDED_OFF")
                .containsEntry("reasonCode", "RETRYABLE_ACTION_EXHAUSTED")
                .containsEntry("iteration", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedStep = (Map<String, Object>)
                ((List<?>) execution.get("steps")).getFirst();
        assertThat(projectedStep)
                .containsEntry("actionType", "CALL_CAPABILITY")
                .doesNotContainKeys("parameters", "inputProvenance", "facts");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedObservation =
                (Map<String, Object>) projectedStep.get("observation");
        assertThat(projectedObservation)
                .containsEntry("status", "FAILED")
                .containsEntry("retryable", true)
                .doesNotContainKeys("facts", "displayHints");
    }

    private record ReadOnlyLoopRepository(
            LoopContracts.Run run, List<LoopContracts.Step> persistedSteps, String persistedReason)
            implements AgentLoopRepository {
        @Override public Optional<LoopContracts.Run> find(String tenantId, String agentId, String loopId) {
            return run.tenantId().equals(tenantId) && run.agentId().equals(agentId)
                    && run.loopId().equals(loopId) ? Optional.of(run) : Optional.empty();
        }
        @Override public List<LoopContracts.Step> steps(String tenantId, String agentId, String loopId) {
            return find(tenantId, agentId, loopId).isPresent() ? persistedSteps : List.of();
        }
        @Override public Optional<String> reasonCode(String tenantId, String agentId, String loopId) {
            return find(tenantId, agentId, loopId).isPresent()
                    ? Optional.ofNullable(persistedReason) : Optional.empty();
        }
        @Override public LoopContracts.Run open(LoopContracts.StartRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean propose(String tenantId, String agentId, String loopId,
                                         long expectedVersion, LoopContracts.Action action) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean claim(String tenantId, String agentId, String loopId,
                                       int stepIndex, long expectedVersion) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean recoverClaimed(String tenantId, String agentId, String loopId,
                                                int stepIndex, long expectedVersion,
                                                Instant claimedBefore, String reasonCode) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean waitForInput(String tenantId, String agentId, String loopId,
                                              long expectedVersion, List<String> pendingSlots,
                                              String reasonCode) {
            throw new UnsupportedOperationException();
        }
        @Override public LoopContracts.Run resume(String tenantId, String agentId, String loopId,
                                                  long expectedVersion,
                                                  LoopContracts.Status waitingStatus,
                                                  Map<String, Object> slotUpdates) {
            throw new UnsupportedOperationException();
        }
        @Override public LoopContracts.Run complete(String tenantId, String agentId, String loopId,
                                                    int stepIndex, long expectedVersion,
                                                    LoopContracts.Observation observation,
                                                    LoopContracts.Status nextStatus) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean transition(String tenantId, String agentId, String loopId,
                                            long expectedVersion, LoopContracts.Status from,
                                            LoopContracts.Status to, String reasonCode) {
            throw new UnsupportedOperationException();
        }
    }
}
