package com.huawei.finance.runtime.loop;

import com.huawei.finance.orchestrator.loop.LoopContracts.*;

/** Owns pure Loop state decisions; repository CAS remains the transition authority. */
public class LoopStateMachine {
    public Status waitingStatus(ActionCheck check) {
        return switch (check.verdict()) {
            case WAIT_USER -> Status.WAITING_USER;
            case WAIT_REVIEW -> Status.WAITING_REVIEW;
            case WAIT_CONFIRMATION -> Status.WAITING_CONFIRMATION;
            case HANDOFF -> Status.HANDED_OFF;
            case REJECT -> Status.FAILED;
            default -> null;
        };
    }

    public Status afterObservation(Action action, Observation observation, Run run) {
        if (action.actionType() == ActionType.FINISH) return Status.COMPLETED;
        return switch (observation.status()) {
            case NEED_USER -> Status.WAITING_USER;
            case PARTIAL -> Status.FAILED;
            case FAILED -> observation.retryable() && run.iteration() + 1 < run.maxIterations()
                    ? Status.RUNNING : Status.FAILED;
            case CANCELLED -> Status.CANCELLED;
            case SUCCESS -> run.iteration() + 1 >= run.maxIterations()
                    ? Status.FAILED : Status.RUNNING;
        };
    }

    public String responsePhase(Status status) {
        return switch (status) {
            case WAITING_USER -> "CLARIFY";
            case WAITING_REVIEW -> "REVIEW";
            case WAITING_CONFIRMATION -> "CONFIRM";
            case COMPLETED -> "FINAL";
            case HANDED_OFF, FAILED, EXPIRED -> "ERROR";
            default -> "PROGRESS";
        };
    }
}
