package com.huawei.finance.runtime.loop;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;

@FunctionalInterface
public interface LoopGoalDelegator {
    Observation delegate(RequestContext context, Run run, Action action, CapabilityCard card);

    LoopGoalDelegator UNAVAILABLE = (context, run, action, card) -> new Observation(
            ObservationStatus.FAILED, "A2A", action.targetId(), java.util.Map.of(),
            "A2A_UNAVAILABLE", "RETRYABLE", null, null, true, java.util.Map.of());
}
