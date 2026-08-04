package com.huawei.finance.runtime.loop;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Resolution;
import com.huawei.finance.registry.asset.AssetBundle;
import java.time.Instant;
import java.util.Map;

public class AgentLoopStarter {
    private final AgentLoopRepository repository;
    private final AgentLoopCoordinator coordinator;
    private final LoopResumeAdapter resumes;
    private final int maxIterations;
    private final int deadlineSeconds;
    public AgentLoopStarter(AgentLoopRepository repository,AgentLoopCoordinator coordinator,int maxIterations){
        this(repository,coordinator,maxIterations,30);}
    public AgentLoopStarter(AgentLoopRepository repository,AgentLoopCoordinator coordinator,int maxIterations,
                            int deadlineSeconds){
        this.repository=repository;this.coordinator=coordinator;this.maxIterations=maxIterations;
        this.resumes=new LoopResumeAdapter(repository);
        this.deadlineSeconds=Math.max(1,deadlineSeconds);}
    public Outcome start(String tenantId,RequestContext context,String rootTaskId,String goal,RouteDecision decision,
                         AssetBundle assets,ContextLease lease,Map<String,Object> slots,Instant deadline){
        return start(tenantId, context, rootTaskId, goal, decision, assets, lease, slots, deadline, null);
    }
    public Outcome start(String tenantId,RequestContext context,String rootTaskId,String goal,RouteDecision decision,
                         AssetBundle assets,ContextLease lease,Map<String,Object> slots,Instant deadline,
                         IntentContext intentContext){
        if(decision.decision()!=Decision.START_LOOP)throw new IllegalArgumentException("START_LOOP_REQUIRED");
        Instant localDeadline=Instant.now().plusSeconds(deadlineSeconds);
        Instant effectiveDeadline=deadline==null||localDeadline.isBefore(deadline)?localDeadline:deadline;
        Run run=repository.open(new StartRequest(tenantId,context.agentId(),context.sessionId(),rootTaskId,
                context.traceId(),goal,decision.candidateIds(),decision.taskShape(),effectiveDeadline,maxIterations,
                slots));
        return coordinator.run(context,tenantId,run.loopId(),assets,lease,slots,false,intentContext);
    }
    public Outcome resume(String tenantId,RequestContext context,String loopId,AssetBundle assets,
                          ContextLease lease,Map<String,Object> slots,boolean accepted,
                          Event event,long expectedVersion){
        return resume(tenantId, context, loopId, assets, lease, slots, accepted, event,
                expectedVersion, null);
    }
    public Outcome resume(String tenantId,RequestContext context,String loopId,AssetBundle assets,
                          ContextLease lease,Map<String,Object> slots,boolean accepted,
                          Event event,long expectedVersion,IntentContext intentContext){
        resumes.apply(tenantId, context.agentId(), loopId,
                new Resolution(event, loopId, slots, null, 1, "CONTINUATION"), expectedVersion);
        return coordinator.run(context,tenantId,loopId,assets,lease,Map.of(),accepted,intentContext);
    }
    public Outcome cancel(String tenantId, RequestContext context, String loopId, long expectedVersion) {
        resumes.apply(tenantId, context.agentId(), loopId,
                new Resolution(Event.CANCEL, loopId, Map.of(), null, 1, "USER_CANCELLED"),
                expectedVersion);
        Run cancelled = repository.find(tenantId, context.agentId(), loopId).orElseThrow();
        return new Outcome(cancelled.loopId(), cancelled.status(), "FINAL", null, null,
                cancelled.facts(), null, "USER_CANCELLED", cancelled.version());
    }
}
