package com.huawei.finance.runtime.loop;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.runtime.task.*;
import java.util.List;
import java.util.Map;
import com.huawei.finance.registry.asset.AssetBundle;

public class LoopActionExecutorRouter {
    private final AgentTaskExecutor tasks;
    private final LoopGoalDelegator goals;
    private final int maxDelegationDepth;
    private final LoopObservationNormalizer observations;
    public LoopActionExecutorRouter(AgentTaskExecutor tasks) {
        this(tasks, LoopGoalDelegator.UNAVAILABLE, 3, new LoopObservationNormalizer());
    }
    public LoopActionExecutorRouter(AgentTaskExecutor tasks, LoopGoalDelegator goals) {
        this(tasks, goals, 3, new LoopObservationNormalizer());
    }
    public LoopActionExecutorRouter(AgentTaskExecutor tasks, LoopGoalDelegator goals, int maxDelegationDepth) {
        this(tasks, goals, maxDelegationDepth, new LoopObservationNormalizer());
    }
    public LoopActionExecutorRouter(AgentTaskExecutor tasks, LoopGoalDelegator goals, int maxDelegationDepth,
                                    LoopObservationNormalizer observations) {
        this.tasks = tasks; this.goals = goals == null ? LoopGoalDelegator.UNAVAILABLE : goals;
        this.maxDelegationDepth = Math.max(1, maxDelegationDepth);
        this.observations = observations == null ? new LoopObservationNormalizer() : observations;
    }
    public Observation execute(RequestContext context, Run run, Action action, CapabilityCard card,
                               ContextLease lease, boolean accepted, AssetBundle assets) {
        if (action.actionType() == ActionType.FINISH)
            return new Observation(ObservationStatus.SUCCESS,"LOOP",run.loopId(),Map.of(),"FINISHED",null,null,null,false,Map.of());
        if (action.actionType() == ActionType.SEARCH_KNOWLEDGE) {
            return assets.standardQa().match(run.goal())
                    .map(entry -> observations.knowledgeSuccess(entry.getId(), entry.getAnswer()))
                    .orElseGet(() -> observations.failure("KNOWLEDGE", null,
                            "KNOWLEDGE_NOT_FOUND", "FATAL", false));
        }
        if (action.actionType() == ActionType.RESOLVE_MENU) {
            return assets.menus().findByCapabilityId(action.targetId())
                    .map(menu -> observations.navigationSuccess(menu.getMenuId(), menu.getFinalName(), menu.getPath()))
                    .orElseGet(() -> observations.failure("NAVIGATION", action.targetId(),
                            "MENU_NOT_FOUND", "FATAL", false));
        }
        if (action.actionType() == ActionType.DELEGATE_GOAL) {
            String blocked = null;
            if (context.agentId().equals(action.targetId())) blocked = "A2A_SELF_DELEGATION";
            else if (context.lineage() != null
                    && context.lineage().delegationPath().contains(action.targetId())) blocked = "A2A_CYCLE_DETECTED";
            else if (context.lineage() != null
                    && context.lineage().delegationPath().size() >= maxDelegationDepth) blocked = "A2A_DEPTH_EXCEEDED";
            if (blocked != null) {
                return observations.failure("A2A", action.targetId(), blocked, "FATAL", false);
            }
            return observations.normalizeExternal(goals.delegate(context, run, action, card));
        }
        if (action.actionType() != ActionType.CALL_CAPABILITY || card == null)
            return observations.failure("LOOP", action.targetId(), "UNSUPPORTED_ACTION", "FATAL", false);
        Decision route = card.type() == Enums.CapabilityType.WORKFLOW
                ? Decision.START_WORKFLOW : Decision.EXECUTE_CAPABILITY;
        RouteTarget.Type targetType = card.type() == Enums.CapabilityType.WORKFLOW
                ? RouteTarget.Type.WORKFLOW : RouteTarget.Type.CAPABILITY;
        RouteDecision decision = RouteDecision.builder().decision(route)
                .candidateIds(List.of(card.capabilityId())).target(new RouteTarget(targetType,card.capabilityId()))
                .taskShape(TaskShape.SINGLE_ACTION).confidence(1).reasonCode(ReasonCode.HIGH_CONFIDENCE).build();
        AgentTaskOutcome outcome = tasks.execute(new AgentTaskRequest(context,decision,card,action.parameters(),run.goal(),
                accepted,List.of(),lease,Enums.TaskSource.SLOW_PATH,Enums.InvocationOrigin.LOCAL,
                run.loopId()+":"+run.iteration()));
        return observations.normalizeTask(card, outcome);
    }
}
