package com.huawei.finance.runtime.multi;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.ConditionResolver;
import com.huawei.finance.intent.PlanConditionValidator;
import com.huawei.finance.intent.SlowPathProperties;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.orchestrator.plan.PlanStepRecord;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import com.huawei.finance.runtime.task.AgentTaskRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SlowPathExecutionCoordinatorTest {

    @Test
    void confirmEachWaitsForVersionedContinuationAndExecutesOneStep() {
        IntentPlan plan = plan(
                item(0, "cap.first", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.second", Enums.IntentRelation.SEQUENTIAL, null));
        RecordingPlans plans = new RecordingPlans(plan, 0);
        List<String> executed = new ArrayList<>();
        SlowPathProperties properties = new SlowPathProperties();
        properties.setExecutionMode(SlowPathProperties.ExecutionMode.CONFIRM_EACH);
        properties.setMaxAutoSteps(5);
        var coordinator = new SlowPathExecutionCoordinator(plans, request -> {
            executed.add(request.capability().capabilityId());
            return success(request, Map.of("done", true));
        }, new ConditionEvaluator(), properties);
        AssetBundle assets = assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of())));

        assertThat(coordinator.execute(context(), plan, assets, Map.of(), lease(),
                Instant.now().plusSeconds(30))).isEmpty();
        assertThat(executed).isEmpty();

        assertThat(coordinator.execute(context(), plan, assets, Map.of(), lease(),
                Instant.now().plusSeconds(30), Enums.InvocationOrigin.LOCAL,
                false, false, 0L)).isPresent();
        assertThat(executed).containsExactly("cap.first");
        assertThat(plans.cursor).hasValue(1);
        assertThat(plans.state).hasValue(PlanState.IN_PROGRESS);
    }

    @Test
    void parallelGroupRunsConcurrentlyAndRecoveredSuccessIsNotReplayed() throws Exception {
        IntentPlan plan = plan(
                item(0, "cap.first", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.second", Enums.IntentRelation.PARALLEL, null));
        RecordingPlans concurrentPlans = new RecordingPlans(plan, 0);
        CountDownLatch entered = new CountDownLatch(2);
        coordinator(concurrentPlans, request -> {
            entered.countDown();
            try { assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            return success(request, Map.of("done", true));
        }, 5).execute(context(), plan, assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of(), lease(), Instant.now().plusSeconds(30));
        assertThat(concurrentPlans.cursor).hasValue(2);

        RecordingPlans recoveryPlans = new RecordingPlans(plan, 0);
        coordinator(recoveryPlans, request -> {
            if (request.capability().capabilityId().equals("cap.first")) {
                TaskResult failed = new TaskResult("task-first", Enums.TaskStatus.FAILED,
                        Enums.FailureClass.RETRYABLE, Map.of(), request.sourceInvocationId(), GuardrailCheck.passed());
                return new AgentTaskOutcome("task-first", failed, GuardrailCheck.passed());
            }
            return success(request, Map.of("done", true));
        }, 5).execute(context(), plan, assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of(), lease(), Instant.now().plusSeconds(30));
        assertThat(recoveryPlans.cursor).hasValue(0);

        List<String> replayed = new ArrayList<>();
        coordinator(recoveryPlans, request -> {
            replayed.add(request.capability().capabilityId());
            return success(request, Map.of("done", true));
        }, 5).execute(context(), plan, assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of(), lease(), Instant.now().plusSeconds(30));
        assertThat(replayed).containsExactly("cap.first");
        assertThat(recoveryPlans.cursor).hasValue(2);
    }

    @Test
    void automaticallyExecutesReadOnlyStepsAndWaitsBeforeRisk() {
        IntentPlan plan = plan(
                item(0, "cap.balance", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.transactions", Enums.IntentRelation.SEQUENTIAL, null),
                item(2, "cap.transfer", Enums.IntentRelation.SEQUENTIAL, null));
        RecordingPlans plans = new RecordingPlans(plan, 0);
        List<AgentTaskRequest> requests = new ArrayList<>();
        var coordinator = coordinator(plans, request -> {
            requests.add(request);
            if (request.capability().riskLevel() == RiskLevel.R2) {
                return new AgentTaskOutcome("task-" + request.sourceInvocationId(), null,
                        GuardrailCheck.pending(), TaskState.CONFIRM_PENDING.name());
            }
            return success(request, Map.of("value", request.capability().capabilityId()));
        }, 5);
        AssetBundle assets = assets(Map.of(
                "cap.balance", card("cap.balance", RiskLevel.R0, false, List.of()),
                "cap.transactions", card("cap.transactions", RiskLevel.R0, false, List.of()),
                "cap.transfer", card("cap.transfer", RiskLevel.R2, true, List.of("amount"))));

        var outcome = coordinator.execute(context(), plan, assets, Map.of("amount", "10"),
                lease(), Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(requests).hasSize(3);
        assertThat(requests).allMatch(request -> request.source() == Enums.TaskSource.SLOW_PATH);
        assertThat(requests).extracting(AgentTaskRequest::sourceInvocationId)
                .containsExactly("plan-1:0:0", "plan-1:1:0", "plan-1:2:0");
        assertThat(plans.cursor).hasValue(2);
        assertThat(plans.state).hasValue(PlanState.WAITING_CONFIRMATION);
        assertThat(outcome.state()).isEqualTo(TaskState.CONFIRM_PENDING);
        assertThat(plans.steps).filteredOn(step -> step.status() == Enums.TaskStatus.SUCCESS)
                .extracting(PlanStepRecord::capabilityId)
                .contains("cap.balance", "cap.transactions");
    }

    @Test
    void stopsOnMissingSlotUndecidedConditionDeadlineAndConfiguredLimit() {
        IntentPlan missingPlan = plan(
                item(0, "cap.first", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.second", Enums.IntentRelation.SEQUENTIAL, null));
        AtomicInteger calls = new AtomicInteger();
        AssetBundle missingAssets = assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of("accountRef")),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of())));
        RecordingPlans missingPlans = new RecordingPlans(missingPlan, 0);
        assertThat(coordinator(missingPlans, request -> {
            calls.incrementAndGet();
            return success(request, Map.of());
        }, 5).execute(context(), missingPlan, missingAssets, Map.of(), lease(),
                Instant.now().plusSeconds(30))).get().satisfies(outcome ->
                        assertThat(outcome.state()).isEqualTo(TaskState.CLARIFY_PENDING));
        assertThat(missingPlans.state).hasValue(PlanState.WAITING_USER);

        IntentPlan conditional = plan(
                item(0, "cap.first", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.second", Enums.IntentRelation.CONDITIONAL, "收益不错就继续"));
        RecordingPlans conditionalPlans = new RecordingPlans(conditional, 1);
        assertThat(coordinator(conditionalPlans, request -> {
            calls.incrementAndGet();
            return success(request, Map.of());
        }, 5).execute(context(), conditional, assets(Map.of(
                        "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of(), lease(), Instant.now().plusSeconds(30))).get().satisfies(outcome ->
                        assertThat(outcome.state()).isEqualTo(TaskState.CLARIFY_PENDING));
        assertThat(conditionalPlans.state).hasValue(PlanState.WAITING_USER);

        RecordingPlans expiredPlans = new RecordingPlans(missingPlan, 0);
        assertThat(coordinator(expiredPlans, request -> {
            calls.incrementAndGet();
            return success(request, Map.of());
        }, 5).execute(context(), missingPlan, assets(Map.of(
                        "cap.first", card("cap.first", RiskLevel.R0, false, List.of()))),
                Map.of("accountRef", "default"), lease(), Instant.now().minusSeconds(1))).isEmpty();

        RecordingPlans limitedPlans = new RecordingPlans(missingPlan, 0);
        coordinator(limitedPlans, request -> {
            calls.incrementAndGet();
            return success(request, Map.of());
        }, 1).execute(context(), missingPlan, assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of("accountRef", "default"), lease(), Instant.now().plusSeconds(30));
        assertThat(limitedPlans.cursor).hasValue(1);
    }

    @Test
    void approvedConditionAsksForMissingBusinessSlotInsteadOfConditionAgain() {
        IntentPlan conditional = plan(
                item(0, "cap.balance", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.transfer", Enums.IntentRelation.CONDITIONAL, "余额足够就继续转账"));
        RecordingPlans plans = new RecordingPlans(conditional, 1);
        AssetBundle assets = assets(Map.of(
                "cap.transfer", card("cap.transfer", RiskLevel.R2, true,
                        List.of("payee", "amount"))));

        var outcome = coordinator(plans, request -> success(request, Map.of()), 5)
                .execute(context(), conditional, assets,
                        Map.of("conditionDecision", "继续办理"), lease(),
                        Instant.now().plusSeconds(30))
                .orElseThrow();

        assertThat(outcome.state()).isEqualTo(TaskState.CLARIFY_PENDING);
        assertThat(plans.state).hasValue(PlanState.WAITING_USER);
        assertThat(plans.pending.get()).isNotNull();
        assertThat(plans.pending.get().slot()).isEqualTo("payee");
        assertThat(plans.pending.get().expectedAnswers()).isEmpty();
    }

    @Test
    void deferredConditionIsCompiledValidatedAndThenEvaluated() {
        IntentPlan conditional = plan(
                item(0, "cap.query", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.action", Enums.IntentRelation.CONDITIONAL, "continue when value is high enough"));
        RecordingPlans plans = new RecordingPlans(conditional, 0);
        List<String> executed = new ArrayList<>();
        ConditionResolver resolver = request -> Optional.of(new ConditionResolver.Resolution(
                new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                        ConditionExpression.Operand.stepOutput("step-1", "/value"),
                        ConditionExpression.Operand.parameter("threshold"))),
                "test-model", "test-prompt"));
        SlowPathProperties properties = new SlowPathProperties();
        properties.setExecutionMode(SlowPathProperties.ExecutionMode.AUTO_READ_ONLY);
        var coordinator = new SlowPathExecutionCoordinator(plans, request -> {
            executed.add(request.capability().capabilityId());
            return success(request, "cap.query".equals(request.capability().capabilityId())
                    ? Map.of("value", "12.5") : Map.of("done", true));
        }, new ConditionEvaluator(), properties, null, null,
                java.util.concurrent.ForkJoinPool.commonPool(), resolver, new PlanConditionValidator());
        CapabilityCard query = cardWithSchemas("cap.query", Map.of(), Map.of(
                "type", "object", "properties", Map.of(
                        "value", Map.of("type", "string", "x-value-type", "decimal"))));
        CapabilityCard action = cardWithSchemas("cap.action", Map.of(
                "type", "object", "properties", Map.of(
                        "threshold", Map.of("type", "number"))), Map.of());

        var outcome = coordinator.execute(context(), conditional,
                assets(Map.of("cap.query", query, "cap.action", action)),
                Map.of("threshold", 10), lease(), Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(executed).containsExactly("cap.query", "cap.action");
        assertThat(outcome.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(plans.state).hasValue(PlanState.COMPLETED);
    }

    @Test
    void deferredConditionCompiledToFalseSkipsActionWithoutExecutingIt() {
        IntentPlan conditional = plan(
                item(0, "cap.query", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.action", Enums.IntentRelation.CONDITIONAL, "continue when value is high enough"));
        RecordingPlans plans = new RecordingPlans(conditional, 0);
        List<String> executed = new ArrayList<>();
        ConditionResolver resolver = request -> Optional.of(new ConditionResolver.Resolution(
                new ConditionExpression(ConditionExpression.Operator.GTE, List.of(
                        ConditionExpression.Operand.stepOutput("step-1", "/value"),
                        ConditionExpression.Operand.parameter("threshold"))),
                "test-model", "test-prompt"));
        SlowPathProperties properties = new SlowPathProperties();
        properties.setExecutionMode(SlowPathProperties.ExecutionMode.AUTO_READ_ONLY);
        var coordinator = new SlowPathExecutionCoordinator(plans, request -> {
            executed.add(request.capability().capabilityId());
            return success(request, Map.of("value", "5"));
        }, new ConditionEvaluator(), properties, null, null,
                java.util.concurrent.ForkJoinPool.commonPool(), resolver, new PlanConditionValidator());
        CapabilityCard query = cardWithSchemas("cap.query", Map.of(), Map.of(
                "type", "object", "properties", Map.of(
                        "value", Map.of("type", "string", "x-value-type", "decimal"))));
        CapabilityCard action = cardWithSchemas("cap.action", Map.of(
                "type", "object", "properties", Map.of(
                        "threshold", Map.of("type", "number"))), Map.of());

        var outcome = coordinator.execute(context(), conditional,
                assets(Map.of("cap.query", query, "cap.action", action)),
                Map.of("threshold", 10), lease(), Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(executed).containsExactly("cap.query");
        assertThat(plans.cursor).hasValue(2);
        assertThat(plans.state).hasValue(PlanState.COMPLETED);
        assertThat(outcome.result().resultPayload()).containsEntry("conditionNotMet", true);
    }

    @Test
    void restartContinuesAtPersistedCursor() {
        IntentPlan plan = plan(
                item(0, "cap.first", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.second", Enums.IntentRelation.SEQUENTIAL, null));
        RecordingPlans plans = new RecordingPlans(plan, 1);
        List<String> executed = new ArrayList<>();

        var outcome = coordinator(plans, request -> {
            executed.add(request.capability().capabilityId());
            return success(request, Map.of("done", true));
        }, 5).execute(context(), plan, assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of(), lease(), Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(executed).containsExactly("cap.second");
        assertThat(plans.cursor).hasValue(2);
        assertThat(((Map<?, ?>) outcome.result().resultPayload().get("capabilities")).keySet()
                .stream().map(String::valueOf).toList()).contains("cap.first");
    }

    @Test
    void partialFailurePreservesFactsFromCompletedSteps() {
        IntentPlan plan = plan(
                item(0, "cap.first", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.second", Enums.IntentRelation.SEQUENTIAL, null));
        RecordingPlans plans = new RecordingPlans(plan, 0);
        AtomicInteger sequence = new AtomicInteger();

        var outcome = coordinator(plans, request -> {
            if (sequence.getAndIncrement() == 0) {
                return success(request, Map.of("balance", "100"));
            }
            TaskResult partial = new TaskResult("task-2", Enums.TaskStatus.PARTIAL,
                    Enums.FailureClass.PARTIAL, Map.of("reasonCode", "BACKEND_UNKNOWN"),
                    request.sourceInvocationId(), GuardrailCheck.passed());
            return new AgentTaskOutcome("task-2", partial, GuardrailCheck.passed());
        }, 5).execute(context(), plan, assets(Map.of(
                "cap.first", card("cap.first", RiskLevel.R0, false, List.of()),
                "cap.second", card("cap.second", RiskLevel.R0, false, List.of()))),
                Map.of(), lease(), Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(((Map<?, ?>) outcome.result().resultPayload()
                .get("completedCapabilities")).keySet().stream().map(String::valueOf).toList())
                .contains("cap.first");
        assertThat(plans.cursor).hasValue(1);
    }

    @Test
    void confirmationResumesOnlyCurrentRiskStepWithSameCrashSafeInvocationId() {
        IntentPlan plan = plan(
                item(0, "cap.balance", Enums.IntentRelation.PARALLEL, null),
                item(1, "cap.transfer", Enums.IntentRelation.SEQUENTIAL, null));
        RecordingPlans plans = new RecordingPlans(plan, 1);
        List<AgentTaskRequest> requests = new ArrayList<>();
        var coordinator = coordinator(plans, request -> {
            requests.add(request);
            if (!request.confirmed()) {
                return new AgentTaskOutcome("task-transfer", null, GuardrailCheck.pending(),
                        TaskState.CONFIRM_PENDING.name());
            }
            return success(request, Map.of("serialNo", "tx-1"));
        }, 5);
        AssetBundle assets = assets(Map.of(
                "cap.balance", card("cap.balance", RiskLevel.R0, false, List.of()),
                "cap.transfer", card("cap.transfer", RiskLevel.R2, true, List.of("amount"))));

        var pending = coordinator.execute(context(), plan, assets,
                Map.of("amount", "10"), lease(), Instant.now().plusSeconds(30)).orElseThrow();
        long waitingVersion = plans.version.get();

        assertThat(pending.state()).isEqualTo(TaskState.CONFIRM_PENDING);
        assertThat(plans.state).hasValue(PlanState.WAITING_CONFIRMATION);

        var completed = coordinator.execute(context(), plan, assets,
                Map.of("amount", "10"), lease(), Instant.now().plusSeconds(30),
                Enums.InvocationOrigin.LOCAL, true, false, waitingVersion).orElseThrow();

        assertThat(completed.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(plans.state).hasValue(PlanState.COMPLETED);
        assertThat(plans.cursor).hasValue(2);
        assertThat(requests).extracting(AgentTaskRequest::confirmed).containsExactly(false, true);
        assertThat(requests).extracting(AgentTaskRequest::sourceInvocationId)
                .containsExactly("plan-1:1:0", "plan-1:1:0");
    }

    private static SlowPathExecutionCoordinator coordinator(
            IntentPlanRepository plans, com.huawei.finance.runtime.task.AgentTaskExecutor tasks,
            int maxSteps) {
        SlowPathProperties properties = new SlowPathProperties();
        properties.setExecutionMode(SlowPathProperties.ExecutionMode.AUTO_READ_ONLY);
        properties.setMaxAutoSteps(maxSteps);
        return new SlowPathExecutionCoordinator(plans, tasks, new ConditionEvaluator(), properties);
    }

    private static AgentTaskOutcome success(AgentTaskRequest request, Map<String, Object> facts) {
        String taskId = "task-" + request.sourceInvocationId();
        TaskResult result = new TaskResult(taskId, Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE, facts, request.sourceInvocationId(), GuardrailCheck.passed());
        return new AgentTaskOutcome(taskId, result, GuardrailCheck.passed());
    }

    private static AssetBundle assets(Map<String, CapabilityCard> cards) {
        return new AssetBundle("test", "test", List.copyOf(cards.values()), List.of(),
                List.of(), null, null, null, Map.of(), Map.of(), null, null, null, null, null);
    }

    private static CapabilityCard card(
            String id, RiskLevel risk, boolean sideEffect, List<String> requiredSlots) {
        return new CapabilityCard(id, id, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.test", List.of("test"), id, List.of(), Map.of(), Map.of(), List.of(),
                sideEffect ? List.of("write") : List.of(), risk, 1000, Enums.Idempotency.SUPPORTED,
                "test", "1", Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), requiredSlots,
                Enums.GuardrailOwner.DOMAIN, false);
    }

    private static CapabilityCard cardWithSchemas(
            String id, Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return new CapabilityCard(id, id, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.test", List.of("test"), id, List.of(), inputSchema, outputSchema, List.of(),
                List.of(), RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED,
                "test", "1", Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, false);
    }

    private static IntentPlan plan(SubIntent... items) {
        return new IntentPlan("multi", List.of(items), IntentPlan.Source.PLANNER);
    }

    private static SubIntent item(
            int order, String capability, Enums.IntentRelation relation, String condition) {
        return new SubIntent(order, capability, capability, capability, relation, condition,
                PlanResolution.locked(capability, "test:fixture"));
    }

    private static RequestContext context() {
        return new RequestContext("trace", "session", "principal", "tenant", "agent.test",
                "TEST", "", "", false);
    }

    private static ContextLease lease() {
        return ContextLease.degraded("session", "multi", Instant.now().plusSeconds(60));
    }

    private static final class RecordingPlans implements IntentPlanRepository {
        private final IntentPlan plan;
        private final AtomicInteger cursor;
        private final AtomicReference<PlanState> state = new AtomicReference<>(PlanState.IN_PROGRESS);
        private final AtomicReference<PlanRecord.PendingInteraction> pending = new AtomicReference<>();
        private final AtomicLong version;
        private final List<PlanStepRecord> steps = new ArrayList<>();

        private RecordingPlans(IntentPlan plan, int cursor) {
            this.plan = plan;
            this.cursor = new AtomicInteger(cursor);
            this.version = new AtomicLong(cursor);
            for (int i = 0; i < cursor; i++) {
                SubIntent item = plan.items().get(i);
                steps.add(new PlanStepRecord("plan-1", i, item.capabilityId(),
                        "task-plan-1:" + i, Enums.TaskStatus.SUCCESS,
                        Enums.FailureClass.NONE, Map.of("restored", true), null, Instant.now()));
            }
        }

        @Override
        public PlanRecord open(String agentId, String sessionId, String traceId, IntentPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlanRecord> findActiveBySession(String agentId, String sessionId) {
            if (switch (state.get()) {
                case COMPLETED, ABANDONED, FAILED, CANCELLED -> true;
                default -> false;
            }) return Optional.empty();
            return Optional.of(new PlanRecord("plan-1", agentId, sessionId, "trace", plan,
                    cursor.get(), state.get(), version.get(), pending.get()));
        }

        @Override
        public Optional<PlanRecord> findById(String agentId, String planId) {
            return Optional.of(new PlanRecord("plan-1", agentId, "session", "trace", plan,
                    cursor.get(), state.get(), version.get(), pending.get()));
        }

        @Override
        public boolean advance(String planId, int from) {
            if (state.get() != PlanState.IN_PROGRESS || !cursor.compareAndSet(from, from + 1)) return false;
            version.incrementAndGet();
            if (cursor.get() >= plan.items().size()) state.set(PlanState.COMPLETED);
            return true;
        }

        @Override
        public boolean transition(String planId, PlanState from, PlanState to, long expectedVersion) {
            if (version.get() != expectedVersion || !state.compareAndSet(from, to)) return false;
            pending.set(null);
            version.incrementAndGet();
            return true;
        }

        @Override
        public boolean waitFor(String planId, long expectedVersion, PlanState waitingState,
                               String taskId, String pendingSlot, List<String> expectedAnswers) {
            if (version.get() != expectedVersion
                    || !state.compareAndSet(PlanState.IN_PROGRESS, waitingState)) return false;
            pending.set(new PlanRecord.PendingInteraction(taskId, pendingSlot, expectedAnswers));
            version.incrementAndGet();
            return true;
        }

        @Override
        public List<PlanStepRecord> steps(String planId) {
            return List.copyOf(steps);
        }

        @Override
        public void saveStep(PlanStepRecord step) {
            steps.removeIf(existing -> existing.stepIndex() == step.stepIndex());
            steps.add(step);
        }

        @Override
        public boolean saveStepAndAdvance(PlanStepRecord step, int from) {
            if (!advance(step.planId(), from)) return false;
            saveStep(step);
            return true;
        }

        @Override
        public void abandonActive(String agentId, String sessionId, String reason) {
        }
    }
}
