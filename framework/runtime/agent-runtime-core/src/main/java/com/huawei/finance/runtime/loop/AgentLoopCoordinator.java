package com.huawei.finance.runtime.loop;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public class AgentLoopCoordinator {
    private final AgentLoopRepository repository;
    private final LoopCandidateRetriever candidates;
    private final AgentLoopPlanner planner;
    private final LoopActionValidator validator;
    private final LoopActionPolicyGate policy;
    private final LoopActionExecutorRouter executors;
    private final int maxCandidates;
    private final MeterRegistry meters;
    private final int maxModelCalls;
    private final int maxRepeatAction;
    private final int claimRecoverySeconds;
    private final LoopContextBuilder contexts;
    private final LoopStateMachine states;

    public AgentLoopCoordinator(AgentLoopRepository repository, LoopCandidateRetriever candidates,
                                AgentLoopPlanner planner, LoopActionValidator validator,
                                LoopActionPolicyGate policy, LoopActionExecutorRouter executors,
                                int maxCandidates) {
        this(repository, candidates, planner, validator, policy, executors, maxCandidates, 8, 1, 60, null);
    }
    public AgentLoopCoordinator(AgentLoopRepository repository, LoopCandidateRetriever candidates,
                                AgentLoopPlanner planner, LoopActionValidator validator,
                                LoopActionPolicyGate policy, LoopActionExecutorRouter executors,
                                int maxCandidates, MeterRegistry meters) {
        this(repository, candidates, planner, validator, policy, executors, maxCandidates, 8, 1, 60, meters);
    }
    public AgentLoopCoordinator(AgentLoopRepository repository, LoopCandidateRetriever candidates,
                                AgentLoopPlanner planner, LoopActionValidator validator,
                                LoopActionPolicyGate policy, LoopActionExecutorRouter executors,
                                int maxCandidates, int maxModelCalls, int maxRepeatAction,
                                MeterRegistry meters) {
        this(repository,candidates,planner,validator,policy,executors,maxCandidates,maxModelCalls,
                maxRepeatAction,60,meters);
    }
    public AgentLoopCoordinator(AgentLoopRepository repository, LoopCandidateRetriever candidates,
                                AgentLoopPlanner planner, LoopActionValidator validator,
                                LoopActionPolicyGate policy, LoopActionExecutorRouter executors,
                                int maxCandidates, int maxModelCalls, int maxRepeatAction,
                                int claimRecoverySeconds, MeterRegistry meters) {
        this.repository=repository;this.candidates=candidates;this.planner=planner;this.validator=validator;
        this.policy=policy;this.executors=executors;this.maxCandidates=maxCandidates;this.meters=meters;
        this.maxModelCalls=Math.max(1,maxModelCalls);this.maxRepeatAction=Math.max(1,maxRepeatAction);
        this.claimRecoverySeconds=Math.max(1,claimRecoverySeconds);
        this.contexts=new LoopContextBuilder();this.states=new LoopStateMachine();
    }

    public Outcome run(RequestContext context, String tenantId, String loopId, AssetBundle assets,
                       ContextLease lease, Map<String,Object> confirmedSlots, boolean accepted) {
        return run(context, tenantId, loopId, assets, lease, confirmedSlots, accepted, null);
    }

    public Outcome run(RequestContext context, String tenantId, String loopId, AssetBundle assets,
                       ContextLease lease, Map<String,Object> confirmedSlots, boolean accepted,
                       IntentContext intentContext) {
        boolean actionAccepted = accepted;
        while (true) {
        Run run=repository.find(tenantId,context.agentId(),loopId).orElseThrow();
        if(run.terminal()) return outcome(run,null,"TERMINAL");
        List<CapabilityCard> allowed=candidates.retrieve(context,run,assets,maxCandidates);
        List<Step> priorSteps = repository.steps(tenantId, context.agentId(), loopId);
        Action action=run.pendingAction();
        if (action != null) {
            int pendingIndex = run.iteration();
            Step pendingStep = priorSteps.stream().filter(step -> step.stepIndex() == pendingIndex)
                    .findFirst().orElse(null);
            if (pendingStep == null) {
                repository.transition(tenantId, context.agentId(), loopId, run.version(), run.status(),
                        Status.FAILED, "LOOP_PENDING_STEP_MISSING");
                return outcome(repository.find(tenantId, context.agentId(), loopId).orElseThrow(),
                        null, "LOOP_PENDING_STEP_MISSING");
            }
            if (pendingStep.status() == StepStatus.CLAIMED) {
                Instant now = Instant.now();
                Instant claimedBefore = run.deadline() != null && !now.isBefore(run.deadline())
                        ? now : now.minusSeconds(claimRecoverySeconds);
                if (repository.recoverClaimed(tenantId, context.agentId(), loopId, run.iteration(),
                        run.version(), claimedBefore, "LOOP_RESTART_UNKNOWN_OUTCOME")) {
                    return outcome(repository.find(tenantId, context.agentId(), loopId).orElseThrow(),
                            null, "LOOP_RESTART_UNKNOWN_OUTCOME");
                }
                return outcome(run, null, "LOOP_ACTION_IN_PROGRESS");
            }
            if (pendingStep.status() != StepStatus.PROPOSED) {
                repository.transition(tenantId, context.agentId(), loopId, run.version(), run.status(),
                        Status.FAILED, "LOOP_PENDING_STEP_INVALID");
                return outcome(repository.find(tenantId, context.agentId(), loopId).orElseThrow(),
                        null, "LOOP_PENDING_STEP_INVALID");
            }
        }
        if(action==null){
            if (priorSteps.size() >= maxModelCalls) {
                repository.transition(tenantId, context.agentId(), loopId, run.version(), run.status(),
                        Status.FAILED, "MODEL_BUDGET_EXHAUSTED");
                return outcome(repository.find(tenantId, context.agentId(), loopId).orElseThrow(),
                        null, "MODEL_BUDGET_EXHAUSTED");
            }
            try {
                LoopContext loopContext = contexts.build(run, confirmedSlots, priorSteps, allowed,
                        intentContext);
                action=validator.validate(planner.nextAction(loopContext),allowed,loopContext);
            } catch (IllegalArgumentException invalid) {
                repository.transition(tenantId, context.agentId(), loopId, run.version(), run.status(),
                        Status.FAILED, invalid.getMessage());
                return outcome(repository.find(tenantId, context.agentId(), loopId).orElseThrow(),
                        null, invalid.getMessage());
            }
            String proposedFingerprint = action.fingerprint();
            long repetitions = priorSteps.stream()
                    .map(Step::action).filter(java.util.Objects::nonNull)
                    .filter(previous -> previous.fingerprint().equals(proposedFingerprint)).count();
            long retryableFailures = priorSteps.stream()
                    .filter(step -> step.action() != null
                            && step.action().fingerprint().equals(proposedFingerprint))
                    .map(Step::observation).filter(java.util.Objects::nonNull)
                    .filter(observation -> observation.status() == ObservationStatus.FAILED
                            && observation.retryable()).count();
            boolean retryWithinBudget = repetitions > 0
                    && repetitions <= maxRepeatAction
                    && retryableFailures == repetitions;
            if (repetitions >= maxRepeatAction && !retryWithinBudget) {
                boolean retryExhausted = repetitions > 0 && retryableFailures == repetitions;
                Status terminal = retryExhausted ? Status.HANDED_OFF : Status.FAILED;
                String reason = retryExhausted ? "RETRYABLE_ACTION_EXHAUSTED" : "REPEATED_ACTION";
                repository.transition(tenantId, context.agentId(), loopId, run.version(), run.status(),
                        terminal, reason);
                return outcome(repository.find(tenantId, context.agentId(), loopId).orElseThrow(),
                        null, reason);
            }
            if(!repository.propose(tenantId,context.agentId(),loopId,run.version(),action))
                throw new IllegalStateException("LOOP_PROPOSE_CONFLICT");
            run=repository.find(tenantId,context.agentId(),loopId).orElseThrow();
        }
        Action plannedAction = action;
        CapabilityCard card=allowed.stream().filter(c->c.capabilityId().equals(plannedAction.targetId())).findFirst().orElse(null);
        ActionCheck check=policy.check(run,plannedAction,card,actionAccepted);
        if(meters!=null) meters.counter(AgentMetrics.LOOP_ACTION,AgentMetrics.TAG_MODE,
                plannedAction.actionType().name(),AgentMetrics.TAG_OUTCOME,check.verdict().name()).increment();
        Status wait=states.waitingStatus(check);
        if(wait!=null){
            boolean transitioned = wait == Status.WAITING_USER
                    ? repository.waitForInput(tenantId, context.agentId(), loopId, run.version(),
                            check.missingSlots().isEmpty() ? List.of("userResponse") : check.missingSlots(),
                            check.reasonCode())
                    : repository.transition(tenantId,context.agentId(),loopId,run.version(),
                            Status.RUNNING,wait,check.reasonCode());
            if (!transitioned) throw new IllegalStateException("LOOP_WAIT_CONFLICT");
            return outcome(repository.find(tenantId,context.agentId(),loopId).orElseThrow(),null,check.reasonCode());}
        if(!repository.claim(tenantId,context.agentId(),loopId,run.iteration(),run.version()))
            throw new IllegalStateException("LOOP_CLAIM_CONFLICT");
        Run claimed=repository.find(tenantId,context.agentId(),loopId).orElseThrow();
        Observation observation=executors.execute(context,claimed,action,card,lease,actionAccepted,assets);
        if(meters!=null) meters.counter(AgentMetrics.LOOP_OBSERVATION,AgentMetrics.TAG_MODE,
                observation.sourceType(),AgentMetrics.TAG_OUTCOME,observation.status().name()).increment();
        Status next=states.afterObservation(action,observation,claimed);
        Run completed=repository.complete(tenantId,context.agentId(),loopId,claimed.iteration(),claimed.version(),observation,next);
        if (completed.status() != Status.RUNNING) return outcome(completed,observation,observation.reasonCode());
        actionAccepted = false;
        }
    }

    private Observation lastMeaningful(String tenant, String agent, String loop) {
        var steps = repository.steps(tenant, agent, loop);
        for (int index = steps.size() - 1; index >= 0; index--) {
            Observation observation = steps.get(index).observation();
            if (meaningful(observation)) return observation;
        }
        return null;
    }

    private static boolean meaningful(Observation observation) {
        return observation != null && (!observation.facts().isEmpty()
                || !observation.displayHints().isEmpty()
                || (observation.sourceType() != null && !"LOOP".equals(observation.sourceType())));
    }

    private Outcome outcome(Run run, Observation observation, String reason) {
        Observation presented = meaningful(observation) ? observation
                : lastMeaningful(run.tenantId(), run.agentId(), run.loopId());
        return new Outcome(run.loopId(), run.status(), states.responsePhase(run.status()),
                run.pendingAction() == null ? null : run.pendingAction().proposalReasonCode(),
                presented, run.facts(), presented == null ? null : presented.taskId(), reason, run.version());
    }
}
