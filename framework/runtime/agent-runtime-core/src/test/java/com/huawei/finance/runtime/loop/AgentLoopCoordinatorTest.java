package com.huawei.finance.runtime.loop;

import static com.huawei.finance.orchestrator.loop.LoopContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentLoopCoordinatorTest {
    @Test
    void invalidCandidateBecomesTerminalFailureInsteadOfEscapingRuntime() {
        MemoryRepository repository = repository();
        AgentLoopPlanner planner = context -> FallbackAgentLoopPlanner.action(
                ActionType.CALL_CAPABILITY, "cap.outside", Map.of(), "OUTSIDE");
        Outcome outcome = coordinator(repository, planner, 8, 1, new AtomicInteger())
                .run(context(), "tenant", "loop", assets(), lease(), Map.of(), false);
        assertThat(outcome.state()).isEqualTo(Status.FAILED);
        assertThat(outcome.reasonCode()).isEqualTo("LOOP_TARGET_OUTSIDE_CANDIDATES");
        assertThat(repository.steps("tenant", "agent.test", "loop")).isEmpty();
    }

    @Test
    void repeatedActionAndModelBudgetStopBeforeSecondExecution() {
        AtomicInteger calls = new AtomicInteger();
        MemoryRepository repeatedRepository = repository();
        AgentLoopPlanner repeated = context -> FallbackAgentLoopPlanner.action(
                ActionType.CALL_CAPABILITY, "cap.x", Map.of(), "SAME");
        Outcome repeatedOutcome = coordinator(repeatedRepository, repeated, 8, 1, calls)
                .run(context(), "tenant", "loop", assets(), lease(), Map.of(), false);
        assertThat(repeatedOutcome.reasonCode()).isEqualTo("REPEATED_ACTION");
        assertThat(calls).hasValue(1);

        calls.set(0);
        MemoryRepository budgetRepository = repository();
        Outcome budgetOutcome = coordinator(budgetRepository, repeated, 1, 2, calls)
                .run(context(), "tenant", "loop", assets(), lease(), Map.of(), false);
        assertThat(budgetOutcome.reasonCode()).isEqualTo("MODEL_BUDGET_EXHAUSTED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryableFailureIsObservedReplannedAndRetriedOnce() {
        AtomicInteger executions = new AtomicInteger();
        MemoryRepository repository = repository();
        AgentLoopPlanner planner = loop -> {
            Object facts = loop.run().facts().get("cap.x");
            if (facts instanceof Map<?,?> values && Boolean.TRUE.equals(values.get("ok"))) {
                return FallbackAgentLoopPlanner.action(ActionType.FINISH, null, Map.of(), "DONE");
            }
            return FallbackAgentLoopPlanner.action(ActionType.CALL_CAPABILITY, "cap.x", Map.of(), "CHECK");
        };
        LoopActionExecutorRouter executors = new LoopActionExecutorRouter(request -> {
            int attempt = executions.getAndIncrement();
            TaskResult result = attempt == 0
                    ? new TaskResult("task-1", Enums.TaskStatus.FAILED, Enums.FailureClass.RETRYABLE,
                            Map.of(), "AGENT_ENDPOINT_MISSING", GuardrailCheck.passed())
                    : new TaskResult("task-2", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                            Map.of("ok", true), null, GuardrailCheck.passed());
            return new AgentTaskOutcome(result.taskId(), result, GuardrailCheck.passed());
        });
        AgentLoopCoordinator coordinator = new AgentLoopCoordinator(repository,
                new LoopCandidateRetriever(), planner, new LoopActionValidator(),
                new LoopActionPolicyGate(), executors, 8, 8, 1, null);

        Outcome outcome = coordinator.run(context(), "tenant", "loop", assets(), lease(), Map.of(), false);

        assertThat(outcome.state()).isEqualTo(Status.COMPLETED);
        assertThat(executions).hasValue(2);
        assertThat(repository.steps("tenant", "agent.test", "loop"))
                .extracting(step -> step.action().actionType())
                .containsExactly(ActionType.CALL_CAPABILITY, ActionType.CALL_CAPABILITY, ActionType.FINISH);
        assertThat(repository.steps("tenant", "agent.test", "loop"))
                .extracting(Step::status)
                .containsExactly(StepStatus.FAILED, StepStatus.COMPLETED, StepStatus.COMPLETED);
    }

    @Test
    void retryableFailureExhaustionHandsOffAfterOneRetry() {
        AtomicInteger executions = new AtomicInteger();
        MemoryRepository repository = repository();
        AgentLoopPlanner planner = loop -> FallbackAgentLoopPlanner.action(
                ActionType.CALL_CAPABILITY, "cap.x", Map.of(), "CHECK");
        LoopActionExecutorRouter executors = new LoopActionExecutorRouter(request -> {
            int attempt = executions.incrementAndGet();
            TaskResult result = new TaskResult("task-" + attempt, Enums.TaskStatus.FAILED,
                    Enums.FailureClass.RETRYABLE, Map.of(), "AGENT_ENDPOINT_MISSING",
                    GuardrailCheck.passed());
            return new AgentTaskOutcome(result.taskId(), result, GuardrailCheck.passed());
        });
        AgentLoopCoordinator coordinator = new AgentLoopCoordinator(repository,
                new LoopCandidateRetriever(), planner, new LoopActionValidator(),
                new LoopActionPolicyGate(), executors, 8, 8, 1, null);

        Outcome outcome = coordinator.run(context(), "tenant", "loop", assets(), lease(), Map.of(), false);

        assertThat(outcome.state()).isEqualTo(Status.HANDED_OFF);
        assertThat(outcome.reasonCode()).isEqualTo("RETRYABLE_ACTION_EXHAUSTED");
        assertThat(executions).hasValue(2);
        assertThat(repository.steps("tenant", "agent.test", "loop"))
                .extracting(Step::status)
                .containsExactly(StepStatus.FAILED, StepStatus.FAILED);
    }

    @Test
    void retryableFailureCanReplanToIndependentCandidate() {
        AtomicInteger executions = new AtomicInteger();
        MemoryRepository repository = repositoryWithCandidates(List.of("cap.x", "cap.y"));
        AgentLoopPlanner planner = loop -> {
            if (loop.run().facts().containsKey("cap.y")) {
                return FallbackAgentLoopPlanner.action(ActionType.FINISH, null, Map.of(), "DONE");
            }
            if (loop.lastObservation() != null && loop.lastObservation().retryable()) {
                return FallbackAgentLoopPlanner.action(
                        ActionType.CALL_CAPABILITY, "cap.y", Map.of(), "TRY_INDEPENDENT_SOURCE");
            }
            return FallbackAgentLoopPlanner.action(
                    ActionType.CALL_CAPABILITY, "cap.x", Map.of(), "CHECK_PRIMARY");
        };
        LoopActionExecutorRouter executors = new LoopActionExecutorRouter(request -> {
            executions.incrementAndGet();
            boolean alternative = "cap.y".equals(request.capability().capabilityId());
            TaskResult result = alternative
                    ? new TaskResult("task-y", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                            Map.of("ok", true), null, GuardrailCheck.passed())
                    : new TaskResult("task-x", Enums.TaskStatus.FAILED, Enums.FailureClass.RETRYABLE,
                            Map.of(), "AGENT_ENDPOINT_MISSING", GuardrailCheck.passed());
            return new AgentTaskOutcome(result.taskId(), result, GuardrailCheck.passed());
        });
        AgentLoopCoordinator coordinator = new AgentLoopCoordinator(repository,
                new LoopCandidateRetriever(), planner, new LoopActionValidator(),
                new LoopActionPolicyGate(), executors, 8, 8, 1, null);

        Outcome outcome = coordinator.run(
                context(), "tenant", "loop", assets("cap.x", "cap.y"), lease(), Map.of(), false);

        assertThat(outcome.state()).isEqualTo(Status.COMPLETED);
        assertThat(executions).hasValue(2);
        assertThat(repository.steps("tenant", "agent.test", "loop"))
                .extracting(step -> step.action().targetId())
                .containsExactly("cap.x", "cap.y", null);
        assertThat(repository.steps("tenant", "agent.test", "loop"))
                .extracting(Step::status)
                .containsExactly(StepStatus.FAILED, StepStatus.COMPLETED, StepStatus.COMPLETED);
    }

    @Test
    void claimedStepAfterRestartBecomesUnknownAndIsNeverExecutedAgain() {
        AtomicInteger calls = new AtomicInteger();
        MemoryRepository repository = repository();
        Action action = FallbackAgentLoopPlanner.action(ActionType.CALL_CAPABILITY, "cap.x", Map.of(), "NEXT");
        Run opened = repository.find("tenant", "agent.test", "loop").orElseThrow();
        assertThat(repository.propose("tenant", "agent.test", "loop", opened.version(), action)).isTrue();
        Run proposed = repository.find("tenant", "agent.test", "loop").orElseThrow();
        assertThat(repository.claim("tenant", "agent.test", "loop", 0, proposed.version())).isTrue();

        Outcome outcome = coordinator(repository, context -> action, 8, 1, calls)
                .run(context(), "tenant", "loop", assets(), lease(), Map.of(), false);

        assertThat(outcome.state()).isEqualTo(Status.FAILED);
        assertThat(outcome.reasonCode()).isEqualTo("LOOP_RESTART_UNKNOWN_OUTCOME");
        assertThat(repository.steps("tenant", "agent.test", "loop").getFirst().status())
                .isEqualTo(StepStatus.UNKNOWN_OUTCOME);
        assertThat(calls).hasValue(0);
    }

    @Test
    void waitingUserPersistsSlotsAndReplansInsteadOfRepeatingTheOldProposal() {
        AtomicInteger executions = new AtomicInteger();
        MemoryRepository repository = repository();
        AgentLoopPlanner planner = loop -> {
            if (!loop.confirmedSlots().containsKey("cardType")) {
                return FallbackAgentLoopPlanner.action(ActionType.CALL_CAPABILITY,
                        "cap.x", Map.of(), "NEED_CARD_TYPE");
            }
            if (!loop.run().facts().containsKey("cap.x")) {
                return FallbackAgentLoopPlanner.action(ActionType.CALL_CAPABILITY, "cap.x",
                        Map.of("cardType", loop.confirmedSlots().get("cardType")), "CHECK_CARD");
            }
            return FallbackAgentLoopPlanner.action(ActionType.FINISH, null, Map.of(), "DONE");
        };
        AgentLoopCoordinator coordinator = coordinator(repository, planner, 8, 1, executions);

        Outcome waiting = coordinator.run(context(), "tenant", "loop", assetsWithRequiredSlot(),
                lease(), Map.of(), false);

        assertThat(waiting.state()).isEqualTo(Status.WAITING_USER);
        Run waitingRun = repository.find("tenant", "agent.test", "loop").orElseThrow();
        assertThat(waitingRun.pendingSlots()).containsExactly("cardType");
        assertThat(new LoopContinuationPort(repository).describe("tenant", "agent.test", "loop")
                .allowedSlotsAndValues()).containsOnlyKeys("cardType");

        Run resumed = repository.resume("tenant", "agent.test", "loop", waitingRun.version(),
                Status.WAITING_USER, Map.of("cardType", "CREDIT"));
        Outcome completed = coordinator.run(context(), "tenant", "loop", assetsWithRequiredSlot(),
                lease(), Map.of(), false);

        assertThat(resumed.confirmedSlots()).containsEntry("cardType", "CREDIT");
        assertThat(completed.state()).isEqualTo(Status.COMPLETED);
        assertThat(completed.lastObservation()).isNotNull();
        assertThat(completed.lastObservation().sourceId()).isEqualTo("cap.x");
        assertThat(completed.lastObservation().facts()).containsEntry("ok", true);
        assertThat(executions).hasValue(1);
        assertThat(repository.steps("tenant", "agent.test", "loop"))
                .extracting(Step::status)
                .containsExactly(StepStatus.CANCELLED, StepStatus.COMPLETED, StepStatus.COMPLETED);
    }

    private static AgentLoopCoordinator coordinator(MemoryRepository repository, AgentLoopPlanner planner,
                                                    int modelCalls, int repeats, AtomicInteger calls) {
        LoopActionExecutorRouter executors = new LoopActionExecutorRouter(request -> {
            calls.incrementAndGet();
            TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("ok", true), null, GuardrailCheck.passed());
            return new AgentTaskOutcome("task-1", result, GuardrailCheck.passed());
        });
        return new AgentLoopCoordinator(repository, new LoopCandidateRetriever(), planner,
                new LoopActionValidator(), new LoopActionPolicyGate(), executors,
                8, modelCalls, repeats, null);
    }

    private static MemoryRepository repository() {
        return repositoryWithCandidates(List.of("cap.x"));
    }

    private static MemoryRepository repositoryWithCandidates(List<String> candidateIds) {
        Run run = new Run("tenant", "loop", "agent.test", "session", "root", "trace", "goal",
                Status.NEW, 0, 4, candidateIds, Map.of(), null,
                Instant.now().plusSeconds(30), 0, Instant.now(), Instant.now());
        return new MemoryRepository(run);
    }

    private static AssetBundle assets() {
        return assets("cap.x");
    }

    private static AssetBundle assets(String... capabilityIds) {
        List<CapabilityCard> cards = java.util.Arrays.stream(capabilityIds)
                .map(AgentLoopCoordinatorTest::capability)
                .toList();
        return new AssetBundle("v", "v", cards, List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, null, null, null);
    }

    private static CapabilityCard capability(String capabilityId) {
        return new CapabilityCard(capabilityId, capabilityId, Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.x", List.of("x"), "", List.of(), Map.of(), Map.of(),
                List.of(), List.of(), RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "owner", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false, ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
    }

    private static AssetBundle assetsWithRequiredSlot() {
        CapabilityCard card = new CapabilityCard("cap.x", "X", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.x", List.of("x"), "", List.of(), Map.of(), Map.of(),
                List.of(), List.of(), RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "owner", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of("cardType"),
                Enums.GuardrailOwner.DOMAIN, false, ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
        return new AssetBundle("v", "v", List.of(card), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, null, null, null);
    }

    private static RequestContext context() {
        return new RequestContext("trace", "session", "user", "tenant", "agent.test", "TEST", "", "", false);
    }

    private static ContextLease lease() {
        return ContextLease.degraded("session", "goal", Instant.now().plusSeconds(30));
    }

    private static final class MemoryRepository implements AgentLoopRepository {
        private Run run;
        private final List<Step> steps = new ArrayList<>();
        private MemoryRepository(Run run) { this.run = run; }
        @Override public Run open(StartRequest request) { throw new UnsupportedOperationException(); }
        @Override public Optional<Run> find(String tenant, String agent, String loop) {
            return run.tenantId().equals(tenant) && run.agentId().equals(agent) && run.loopId().equals(loop)
                    ? Optional.of(run) : Optional.empty();
        }
        @Override public List<Step> steps(String tenant, String agent, String loop) { return List.copyOf(steps); }
        @Override public boolean propose(String tenant, String agent, String loop, long version, Action action) {
            if (run.version()!=version || (run.status()!=Status.NEW && run.status()!=Status.RUNNING)) return false;
            run=copy(Status.RUNNING,run.iteration(),action,run.version()+1,run.facts());
            steps.add(new Step(loop,run.iteration(),action,StepStatus.PROPOSED,null,null,null,null,Instant.now(),null));
            return true;
        }
        @Override public boolean claim(String tenant,String agent,String loop,int index,long version) {
            if(run.version()!=version)return false;
            Step step=steps.get(index);if(step.status()!=StepStatus.PROPOSED)return false;
            steps.set(index,new Step(loop,index,step.action(),StepStatus.CLAIMED,null,null,null,null,step.createdAt(),null));
            run=copy(run.status(),run.iteration(),run.pendingAction(),run.version()+1,run.facts());return true;
        }
        @Override public boolean recoverClaimed(String tenant,String agent,String loop,int index,long version,
                                                Instant claimedBefore,String reason) {
            if(run.version()!=version)return false;
            Step step=steps.get(index);if(step.status()!=StepStatus.CLAIMED)return false;
            steps.set(index,new Step(loop,index,step.action(),StepStatus.UNKNOWN_OUTCOME,null,null,null,reason,
                    step.createdAt(),Instant.now()));
            run=copy(Status.FAILED,run.iteration(),null,run.version()+1,run.facts());return true;
        }
        @Override public boolean waitForInput(String tenant,String agent,String loop,long version,
                                              List<String> pendingSlots,String reason) {
            if(run.version()!=version||run.status()!=Status.RUNNING)return false;
            run=copy(Status.WAITING_USER,run.iteration(),run.pendingAction(),run.version()+1,
                    run.facts(),run.confirmedSlots(),pendingSlots);return true;
        }
        @Override public Run resume(String tenant,String agent,String loop,long version,Status waitingStatus,
                                    Map<String,Object> slotUpdates) {
            if(run.version()!=version||run.status()!=waitingStatus)throw new IllegalStateException("LOOP_RESUME_CONFLICT");
            Map<String,Object> confirmed=new java.util.LinkedHashMap<>(run.confirmedSlots());
            confirmed.putAll(slotUpdates);
            if(waitingStatus==Status.WAITING_USER&&run.pendingAction()!=null){
                Step step=steps.get(run.iteration());
                steps.set(run.iteration(),new Step(loop,run.iteration(),step.action(),StepStatus.CANCELLED,
                        null,null,null,"USER_INPUT_RECEIVED",step.createdAt(),Instant.now()));
                run=copy(Status.RUNNING,run.iteration()+1,null,run.version()+1,run.facts(),confirmed,List.of());
            }else{
                run=copy(Status.RUNNING,run.iteration(),run.pendingAction(),run.version()+1,
                        run.facts(),confirmed,List.of());
            }
            return run;
        }
        @Override public Run complete(String tenant,String agent,String loop,int index,long version,
                                      Observation observation,Status next) {
            if(run.version()!=version)throw new IllegalStateException("LOOP_VERSION_CONFLICT");
            Step step=steps.get(index);StepStatus status=switch(observation.status()){
                case SUCCESS,NEED_USER->StepStatus.COMPLETED;case PARTIAL->StepStatus.UNKNOWN_OUTCOME;
                case CANCELLED->StepStatus.CANCELLED;default->StepStatus.FAILED;};
            steps.set(index,new Step(loop,index,step.action(),status,observation.taskId(),observation.delegationId(),
                    observation,observation.reasonCode(),step.createdAt(),Instant.now()));
            Map<String,Object> facts=new java.util.LinkedHashMap<>(run.facts());
            if(observation.sourceId()!=null&&!observation.facts().isEmpty())facts.put(observation.sourceId(),observation.facts());
            List<String> pendingSlots=List.of();
            if(next==Status.WAITING_USER){
                Object raw=observation.displayHints().get("missingSlots");
                if(raw instanceof List<?> values&&!values.isEmpty())pendingSlots=values.stream().map(String::valueOf).toList();
                else pendingSlots=List.of("userResponse");
            }
            run=copy(next,run.iteration()+1,null,run.version()+1,facts,run.confirmedSlots(),pendingSlots);return run;
        }
        @Override public boolean transition(String tenant,String agent,String loop,long version,
                                            Status from,Status to,String reason) {
            if(run.version()!=version||run.status()!=from)return false;
            run=copy(to,run.iteration(),run.pendingAction(),run.version()+1,run.facts());return true;
        }
        private Run copy(Status status,int iteration,Action pending,long version,Map<String,Object> facts){
            return copy(status,iteration,pending,version,facts,run.confirmedSlots(),run.pendingSlots());
        }
        private Run copy(Status status,int iteration,Action pending,long version,Map<String,Object> facts,
                         Map<String,Object> confirmed,List<String> pendingSlots){
            return new Run(run.tenantId(),run.loopId(),run.agentId(),run.sessionId(),run.rootTaskId(),run.traceId(),
                    run.goal(),status,iteration,run.maxIterations(),run.candidateIds(),confirmed,facts,pending,
                    pendingSlots,run.deadline(),
                    version,run.createdAt(),Instant.now());
        }
    }
}
