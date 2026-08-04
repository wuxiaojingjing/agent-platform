package com.huawei.finance.runtime.bootstrap;

import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.a2a.*;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.runtime.loop.LoopGoalDelegator;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class A2ALoopGoalDelegator implements LoopGoalDelegator {
    private final DelegationClient client;
    public A2ALoopGoalDelegator(DelegationClient client) { this.client = client; }

    @Override public Observation delegate(RequestContext context, Run run, Action action, CapabilityCard card) {
        var lineage = context.lineage();
        List<String> path = lineage == null ? List.of() : lineage.delegationPath();
        Instant deadline = run.deadline();
        var state = context.principal();
        PrincipalContext principal = state.verified()
                ? new PrincipalContext(state.subjectRef(), state.authLevel(), state.channel(), context.sessionId())
                : PrincipalContext.anonymous(context.channel(), context.sessionId());
        DelegationReceipt receipt = client.delegate(new DelegationClient.DelegationRequest(
                context.spaceId(), context.agentId(), run.rootTaskId(), run.loopId(), run.loopId(),
                context.traceId(), DelegationMode.GOAL, Enums.TaskSource.SLOW_PATH, principal,
                run.goal(), null, action.parameters(), List.of(), deadline,
                card == null ? 5000 : card.timeoutMs(), path), List.of(action.targetId()));
        ObservationStatus status = switch (receipt.outcome()) {
            case SUCCEEDED -> ObservationStatus.SUCCESS;
            case NEED_USER -> ObservationStatus.NEED_USER;
            case PARTIAL -> ObservationStatus.PARTIAL;
            case NOT_MINE, DOMAIN_NOT_OPEN, FATAL -> ObservationStatus.FAILED;
        };
        return new Observation(status, "A2A", action.targetId(), receipt.facts(), receipt.reasonCode(),
                receipt.outcome().name(), null, receipt.delegationId(), receipt.outcome() == DelegationOutcome.NOT_MINE,
                Map.of("missingSlots", receipt.missingSlots()));
    }
}
