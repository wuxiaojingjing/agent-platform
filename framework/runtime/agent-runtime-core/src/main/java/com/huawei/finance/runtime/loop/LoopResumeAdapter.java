package com.huawei.finance.runtime.loop;

import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Resolution;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.Run;
import com.huawei.finance.orchestrator.loop.LoopContracts.Status;
import java.util.Map;

/** Applies versioned continuation events to Loop-owned state without touching platform focus records. */
public class LoopResumeAdapter {
    private final AgentLoopRepository loops;

    public LoopResumeAdapter(AgentLoopRepository loops) {
        this.loops = loops;
    }

    public Run prepare(String tenant, String agent, String loopId, long expectedVersion,
                       Status waitingStatus, Map<String,Object> slotUpdates) {
        return loops.resume(tenant, agent, loopId, expectedVersion, waitingStatus, slotUpdates);
    }

    public Run apply(String tenant, String agent, String loopId,
                     Resolution resolution, long expectedVersion) {
        Run run = loops.find(tenant, agent, loopId).orElseThrow();
        if (run.version() != expectedVersion) throw new IllegalStateException("LOOP_RESUME_CONFLICT");
        if (resolution.targetRef() != null && !loopId.equals(resolution.targetRef())) {
            throw new IllegalStateException("LOOP_RESUME_TARGET_MISMATCH");
        }
        if (resolution.event() == Event.CANCEL) {
            if (run.terminal()) throw new IllegalStateException("LOOP_RESUME_EVENT_NOT_ALLOWED");
            if (!run.terminal() && !loops.transition(tenant, agent, loopId, run.version(), run.status(),
                    Status.CANCELLED, "USER_CANCELLED")) {
                throw new IllegalStateException("LOOP_CANCEL_CONFLICT");
            }
            return loops.find(tenant, agent, loopId).orElseThrow();
        }
        if (resolution.event() == Event.CONTINUE_CURRENT && run.status() == Status.RUNNING) return run;
        Status required = switch (resolution.event()) {
            case FILL_SLOT -> Status.WAITING_USER;
            case REVIEW_ACCEPT -> Status.WAITING_REVIEW;
            case CONFIRM -> Status.WAITING_CONFIRMATION;
            default -> throw new IllegalStateException("LOOP_RESUME_EVENT_NOT_ALLOWED");
        };
        if (run.status() != required) throw new IllegalStateException("LOOP_RESUME_STATE_MISMATCH");
        if (resolution.event() == Event.FILL_SLOT && resolution.slotUpdates().isEmpty()) {
            throw new IllegalStateException("LOOP_RESUME_SLOTS_REQUIRED");
        }
        return prepare(tenant, agent, loopId, run.version(), required, resolution.slotUpdates());
    }
}
