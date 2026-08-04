package com.huawei.finance.orchestrator.loop;

import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.TaskShape;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Api
public final class LoopContracts {
    private LoopContracts() {}
    public enum Status { NEW, RUNNING, WAITING_USER, WAITING_REVIEW, WAITING_CONFIRMATION,
        COMPLETED, FAILED, HANDED_OFF, EXPIRED, CANCELLED }
    public enum ActionType { SEARCH_KNOWLEDGE, RESOLVE_MENU, CALL_CAPABILITY, DELEGATE_GOAL,
        ASK_USER, FINISH, HANDOFF }
    public enum StepStatus { PROPOSED, CLAIMED, COMPLETED, FAILED, UNKNOWN_OUTCOME, CANCELLED }
    public enum ObservationStatus { SUCCESS, NEED_USER, PARTIAL, FAILED, CANCELLED }
    public enum Verdict { PROCEED, WAIT_USER, WAIT_REVIEW, WAIT_CONFIRMATION, REJECT, HANDOFF }

    public record StartRequest(String tenantId, String agentId, String sessionId, String rootTaskId,
                               String traceId, String goal, List<String> candidateIds, TaskShape taskShape,
                               Instant deadline, int maxIterations, Map<String,Object> confirmedSlots) {
        public StartRequest {
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
            confirmedSlots = confirmedSlots == null ? Map.of() : Map.copyOf(confirmedSlots);
        }
        public StartRequest(String tenantId, String agentId, String sessionId, String rootTaskId,
                            String traceId, String goal, List<String> candidateIds, TaskShape taskShape,
                            Instant deadline, int maxIterations) {
            this(tenantId, agentId, sessionId, rootTaskId, traceId, goal, candidateIds, taskShape,
                    deadline, maxIterations, Map.of());
        }
    }
    public record Run(String tenantId, String loopId, String agentId, String sessionId, String rootTaskId,
                      String traceId, String goal, Status status, int iteration, int maxIterations,
                      List<String> candidateIds, Map<String,Object> confirmedSlots,
                      Map<String,Object> facts, Action pendingAction, List<String> pendingSlots,
                      Instant deadline, long version, Instant createdAt, Instant updatedAt) {
        public Run {
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
            confirmedSlots = confirmedSlots == null ? Map.of() : Map.copyOf(confirmedSlots);
            facts = facts == null ? Map.of() : Map.copyOf(facts);
            pendingSlots = pendingSlots == null ? List.of() : List.copyOf(pendingSlots);
        }
        public Run(String tenantId, String loopId, String agentId, String sessionId, String rootTaskId,
                   String traceId, String goal, Status status, int iteration, int maxIterations,
                   List<String> candidateIds, Map<String,Object> facts, Action pendingAction,
                   Instant deadline, long version, Instant createdAt, Instant updatedAt) {
            this(tenantId, loopId, agentId, sessionId, rootTaskId, traceId, goal, status, iteration,
                    maxIterations, candidateIds, Map.of(), facts, pendingAction, List.of(), deadline,
                    version, createdAt, updatedAt);
        }
        public boolean terminal() { return switch (status) {
            case COMPLETED, FAILED, HANDED_OFF, EXPIRED, CANCELLED -> true;
            default -> false;
        }; }
    }
    public record Action(ActionType actionType, String targetId, Map<String,Object> parameters,
                         Map<String,String> inputProvenance, String proposalReasonCode,
                         String fingerprint) {
        public Action {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            inputProvenance = inputProvenance == null ? Map.of() : Map.copyOf(inputProvenance);
        }
    }
    public record ActionCheck(Verdict verdict, String reasonCode, List<String> missingSlots,
                              Map<String,Object> normalizedParameters, String riskLevel,
                              ConfirmationPolicy confirmationPolicy, boolean sideEffects) {
        public ActionCheck {
            missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
            normalizedParameters = normalizedParameters == null ? Map.of() : Map.copyOf(normalizedParameters);
        }
    }
    public record Observation(ObservationStatus status, String sourceType, String sourceId,
                              Map<String,Object> facts, String reasonCode, String failureClass,
                              String taskId, String delegationId, boolean retryable,
                              Map<String,Object> displayHints) {
        public Observation {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
            displayHints = displayHints == null ? Map.of() : Map.copyOf(displayHints);
        }
    }
    public record Step(String loopId, int stepIndex, Action action, StepStatus status,
                       String taskId, String delegationId, Observation observation,
                       String reasonCode, Instant createdAt, Instant completedAt) {}
    public record Outcome(String loopId, Status state, String responsePhase, String pendingQuestionOrReview,
                          Observation lastObservation, Map<String,Object> completedFacts,
                          String lastTaskId, String reasonCode, long stateVersion) {
        public Outcome { completedFacts = completedFacts == null ? Map.of() : Map.copyOf(completedFacts); }
    }
}
