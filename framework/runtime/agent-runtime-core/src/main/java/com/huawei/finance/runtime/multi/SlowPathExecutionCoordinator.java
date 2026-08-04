package com.huawei.finance.runtime.multi;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanCondition;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.ConditionResolver;
import com.huawei.finance.intent.PlanConditionValidator;
import com.huawei.finance.intent.SlowPathProperties;
import com.huawei.finance.orchestrator.OrchestrationOutcome;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanStepRecord;
import com.huawei.finance.orchestrator.plan.PlanConditionResolutionRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.runtime.task.AgentTaskExecutor;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import com.huawei.finance.runtime.task.AgentTaskRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.core.instrument.MeterRegistry;
import com.huawei.finance.obs.AgentMetrics;

/** 自动推进参数完备的 R0 只读计划步骤。 */
public final class SlowPathExecutionCoordinator {

    private final IntentPlanRepository plans;
    private final AgentTaskExecutor tasks;
    private final ConditionEvaluator conditions;
    private final SlowPathProperties properties;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final Executor parallelExecutor;
    private final ConditionResolver conditionResolver;
    private final PlanConditionValidator conditionValidator;

    public SlowPathExecutionCoordinator(
            IntentPlanRepository plans, AgentTaskExecutor tasks,
            ConditionEvaluator conditions, SlowPathProperties properties) {
        this(plans, tasks, conditions, properties, null, null, ForkJoinPool.commonPool());
    }

    public SlowPathExecutionCoordinator(
            IntentPlanRepository plans, AgentTaskExecutor tasks,
            ConditionEvaluator conditions, SlowPathProperties properties, Tracer tracer) {
        this(plans, tasks, conditions, properties, tracer, null, ForkJoinPool.commonPool());
    }

    public SlowPathExecutionCoordinator(IntentPlanRepository plans, AgentTaskExecutor tasks,
            ConditionEvaluator conditions, SlowPathProperties properties, Tracer tracer,
            MeterRegistry meters) {
        this(plans,tasks,conditions,properties,tracer,meters,ForkJoinPool.commonPool());
    }

    public SlowPathExecutionCoordinator(IntentPlanRepository plans, AgentTaskExecutor tasks,
            ConditionEvaluator conditions, SlowPathProperties properties, Tracer tracer,
            MeterRegistry meters, Executor parallelExecutor) {
        this(plans, tasks, conditions, properties, tracer, meters, parallelExecutor,
                ConditionResolver.NONE, new PlanConditionValidator());
    }

    public SlowPathExecutionCoordinator(IntentPlanRepository plans, AgentTaskExecutor tasks,
            ConditionEvaluator conditions, SlowPathProperties properties, Tracer tracer,
            MeterRegistry meters, Executor parallelExecutor, ConditionResolver conditionResolver,
            PlanConditionValidator conditionValidator) {
        this.plans = plans; this.tasks = tasks; this.conditions = conditions;
        this.properties = properties; this.tracer = tracer; this.meters = meters;
        this.parallelExecutor = parallelExecutor == null ? ForkJoinPool.commonPool() : parallelExecutor;
        this.conditionResolver = conditionResolver == null ? ConditionResolver.NONE : conditionResolver;
        this.conditionValidator = conditionValidator == null
                ? new PlanConditionValidator() : conditionValidator;
    }

    public Optional<OrchestrationOutcome> execute(
            RequestContext context, IntentPlan plan, AssetBundle assets,
            Map<String, Object> parameters, ContextLease lease, Instant deadline) {
        return execute(context, plan, assets, parameters, lease, deadline,
                Enums.InvocationOrigin.LOCAL);
    }

    public Optional<OrchestrationOutcome> execute(
            RequestContext context, IntentPlan plan, AssetBundle assets,
            Map<String, Object> parameters, ContextLease lease, Instant deadline,
            Enums.InvocationOrigin invocationOrigin) {
        return execute(context, plan, assets, parameters, lease, deadline,
                invocationOrigin, false, false, null);
    }

    public Optional<OrchestrationOutcome> execute(
            RequestContext context, IntentPlan plan, AssetBundle assets,
            Map<String, Object> parameters, ContextLease lease, Instant deadline,
            Enums.InvocationOrigin invocationOrigin, boolean confirmed, boolean cancelled,
            Long expectedPlanVersion) {
        SlowPathProperties.ExecutionMode mode = properties.getExecutionMode();
        if (plan == null || mode == SlowPathProperties.ExecutionMode.DISABLED) {
            return Optional.empty();
        }
        // CONFIRM_EACH 首轮只展示计划；只有带 Runtime 版本的明确续办才执行当前步骤。
        if (mode == SlowPathProperties.ExecutionMode.CONFIRM_EACH && expectedPlanVersion == null) {
            return Optional.empty();
        }
        PlanRecord record = plans.findActiveBySession(context.agentId(), context.sessionId()).orElse(null);
        if (record == null) {
            return Optional.empty();
        }
        if (cancelled) {
            return cancel(context, record, assets, parameters, lease, invocationOrigin,
                    expectedPlanVersion == null ? record.stateVersion() : expectedPlanVersion);
        }
        if (record.state() != PlanState.IN_PROGRESS) {
            boolean inputResume = record.state() == PlanState.WAITING_USER
                    && expectedPlanVersion != null && parameters != null && !parameters.isEmpty();
            boolean approvalResume = confirmed && (record.state() == PlanState.WAITING_REVIEW
                    || record.state() == PlanState.WAITING_CONFIRMATION);
            if (!inputResume && !approvalResume) {
                return Optional.empty();
            }
            long expected = expectedPlanVersion == null ? record.stateVersion() : expectedPlanVersion;
            if (!plans.transition(record.planId(), record.state(), PlanState.IN_PROGRESS, expected)) {
                throw new IllegalStateException("STATIC_PLAN_VERSION_CONFLICT:" + record.planId());
            }
            record = plans.findById(context.agentId(), record.planId()).orElseThrow();
        }

        Map<String, Map<String, Object>> facts = restoredFacts(record);
        String lastTaskId = null;
        GuardrailCheck lastGuardrail = GuardrailCheck.pending();
        int cursor = record.cursor();
        int confirmedIndex = confirmed ? cursor : -1;
        int executed = 0;
        int maxSteps = mode == SlowPathProperties.ExecutionMode.CONFIRM_EACH
                ? 1 : properties.getMaxAutoSteps();
        Map<String, Object> conditionStop = null;
        while (cursor < record.plan().items().size() && executed < maxSteps) {
            if (deadline != null && Instant.now().isAfter(deadline)) {
                break;
            }
            PlanStepRecord recovered = successfulStep(record.planId(), cursor);
            if (recovered != null) {
                if (!plans.advance(record.planId(), cursor)) break;
                facts.put(record.plan().items().get(cursor).stepId(), recovered.facts());
                lastTaskId = recovered.taskId();
                cursor++; executed++;
                continue;
            }

            List<PreparedStep> batch = new ArrayList<>();
            Preparation firstPreparation = prepare(
                    context, record, cursor, assets, parameters, facts);
            PreparedStep first = firstPreparation.step();
            if (first == null) {
                SubIntent blocked = record.plan().items().get(cursor);
                CapabilityCard blockedCard = blocked.capabilityId() == null
                        ? null : assets.capability(blocked.capabilityId());
                ConditionEvaluator.Evaluation evaluation = firstPreparation.evaluation() == null
                        ? conditions.explainPlan(blocked, facts, parameters)
                        : firstPreparation.evaluation();
                if (blockedCard != null && (evaluation.verdict() == ConditionEvaluator.Verdict.STOP
                        || conditionRejected(parameters))) {
                    Map<String,Object> skipped = Map.of(
                            "skipped", true, "reasonCode", "CONDITION_NOT_MET");
                    PlanStepRecord step = new PlanStepRecord(record.planId(), cursor,
                            blockedCard.capabilityId(), null, Enums.TaskStatus.CANCELLED,
                            Enums.FailureClass.CANCELLED, skipped, "CONDITION_NOT_MET", Instant.now());
                    if (!plans.saveStepAndAdvance(step, cursor)) break;
                    facts.put(blocked.stepId(), skipped);
                    conditionStop = Map.of(
                            "condition", blocked.condition(),
                            "taskSummary", blocked.summary());
                    cursor++; executed++;
                    continue;
                }
                PlanRecord current = plans.findById(context.agentId(), record.planId()).orElse(record);
                boolean conditionNeedsDecision = blocked != null && blocked.condition() != null
                        && !conditionApproved(parameters) && !conditionRejected(parameters);
                String pendingSlot = conditionNeedsDecision
                        ? "conditionDecision" : firstMissingSlot(blockedCard, parameters);
                List<String> expected = "conditionDecision".equals(pendingSlot)
                        ? List.of("继续办理", "不办理") : List.of();
                if (!plans.waitFor(record.planId(), current.stateVersion(), PlanState.WAITING_USER,
                        null, pendingSlot, expected)) {
                    throw new IllegalStateException("STATIC_PLAN_WAIT_CONFLICT:" + record.planId());
                }
                recordConditionBreak(context, cursor, blocked, evaluation);
                return Optional.of(new OrchestrationOutcome(null, TaskState.CLARIFY_PENDING,
                        null, null, GuardrailCheck.pending(), "condition"));
            }
            batch.add(first);
            int next = cursor + 1;
            int remaining = maxSteps - executed;
            while (next < record.plan().items().size() && batch.size() < remaining
                    && record.plan().items().get(next).relation() == Enums.IntentRelation.PARALLEL
                    && successfulStep(record.planId(), next) == null) {
                Preparation parallelPreparation = prepare(
                        context, record, next, assets, parameters, facts);
                PreparedStep parallel = parallelPreparation.step();
                if (parallel == null || !autoReadOnly(parallel.card())) break;
                batch.add(parallel); next++;
            }

            String planId = record.planId();
            List<StepExecution> results = executeBatch(context, planId, batch, parameters,
                    lease, invocationOrigin, confirmedIndex);
            // 并行批次已经全部返回；即使前序步骤失败，后序成功事实也必须先持久化，
            // 重启后等游标走到该步骤时可直接恢复，不能再次调用外部系统。
            results.stream().filter(execution -> execution.outcome().result() != null
                            && execution.outcome().result().status() == Enums.TaskStatus.SUCCESS)
                    .forEach(execution -> plans.saveStep(stepRecord(planId, execution.index(),
                            execution.card().capabilityId(), execution.outcome().taskId(),
                            execution.outcome().result())));

            for (StepExecution execution : results) {
                lastTaskId = execution.outcome().taskId();
                lastGuardrail = execution.outcome().guardrail();
                TaskResult result = execution.outcome().result();
                TaskState taskState = taskState(execution.outcome());
                PlanState waiting = waitingState(taskState, result);
                if (waiting != null) {
                    PlanRecord current = plans.findById(context.agentId(), record.planId()).orElse(record);
                    String pendingSlot = waiting == PlanState.WAITING_USER
                            ? pendingSlot(result) : null;
                    if (!plans.waitFor(record.planId(), current.stateVersion(), waiting,
                            lastTaskId, pendingSlot, List.of())) {
                        throw new IllegalStateException("STATIC_PLAN_WAIT_CONFLICT:" + record.planId());
                    }
                    return Optional.of(new OrchestrationOutcome(lastTaskId, taskState,
                            null, result, lastGuardrail,
                            taskState == TaskState.CLARIFY_PENDING ? "plan-input" : null));
                }
                if (result == null || result.status() != Enums.TaskStatus.SUCCESS) {
                    plans.saveStep(stepRecord(record.planId(), execution.index(),
                            execution.card().capabilityId(), lastTaskId, result));
                    if (result == null || result.failureClass() != Enums.FailureClass.RETRYABLE) {
                        PlanRecord current = plans.findById(context.agentId(), record.planId()).orElse(record);
                        plans.transition(record.planId(), PlanState.IN_PROGRESS,
                                result != null && result.status() == Enums.TaskStatus.CANCELLED
                                        ? PlanState.CANCELLED : PlanState.FAILED,
                                current.stateVersion());
                    }
                    return Optional.of(outcome(lastTaskId,
                            withCompletedFacts(result, capabilityFacts(record.plan(), facts)),
                            capabilityFacts(record.plan(), facts), lastGuardrail));
                }
                PlanStepRecord step = stepRecord(record.planId(), execution.index(),
                        execution.card().capabilityId(), lastTaskId, result);
                if (!plans.saveStepAndAdvance(step, cursor)) {
                    return executed == 0 ? Optional.empty()
                            : Optional.of(outcome(lastTaskId,
                                    success(lastTaskId, capabilityFacts(record.plan(), facts)),
                                    capabilityFacts(record.plan(), facts), lastGuardrail));
                }
                facts.put(execution.item().stepId(), result.resultPayload());
                cursor++; executed++;
            }
        }
        return executed == 0 ? Optional.empty()
                : Optional.of(outcome(lastTaskId,
                        conditionStop == null
                                ? success(lastTaskId, capabilityFacts(record.plan(), facts))
                                : successWithConditionStop(lastTaskId,
                                        capabilityFacts(record.plan(), facts), conditionStop),
                        capabilityFacts(record.plan(), facts), lastGuardrail));
    }

    private Preparation prepare(RequestContext context, PlanRecord record, int index, AssetBundle assets,
                                Map<String,Object> parameters,
                                Map<String,Map<String,Object>> facts) {
        SubIntent item=record.plan().items().get(index);
        CapabilityCard card=item.capabilityId()==null?null:assets.capability(item.capabilityId());
        if(!eligible(card,parameters,context)) return new Preparation(null, null);
        ConditionEvaluator.Evaluation evaluation=evaluateCondition(
                record, item, index, assets, parameters, facts);
        if(item.condition()!=null&&conditionApproved(parameters)){
            evaluation=new ConditionEvaluator.Evaluation(ConditionEvaluator.Verdict.PROCEED,
                    item.condition(),evaluation.resolvedValues(),"user-approved-condition");
        }
        if(evaluation.verdict()!=ConditionEvaluator.Verdict.PROCEED){
            recordConditionBreak(context,index,item,evaluation);
            return new Preparation(null, evaluation);
        }
        return new Preparation(
                new PreparedStep(index,item,card,sourceInvocationId(record.planId(),index)), evaluation);
    }

    private List<StepExecution> executeBatch(RequestContext context,String planId,List<PreparedStep> batch,
            Map<String,Object> parameters,ContextLease lease,Enums.InvocationOrigin origin,
            int confirmedIndex){
        if(batch.size()==1)return List.of(executeOne(context,planId,batch.getFirst(),parameters,lease,origin,
                batch.getFirst().index()==confirmedIndex));
        List<CompletableFuture<StepExecution>> futures=batch.stream()
                .map(step->CompletableFuture.supplyAsync(
                        ()->executeOne(context,planId,step,parameters,lease,origin,
                                step.index()==confirmedIndex),parallelExecutor)).toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private StepExecution executeOne(RequestContext context,String planId,PreparedStep prepared,
            Map<String,Object> parameters,ContextLease lease,Enums.InvocationOrigin origin,
            boolean confirmed){
        CapabilityCard card=prepared.card();
        RouteDecision decision=RouteDecision.builder().decision(Decision.EXECUTE_CAPABILITY)
                .candidateIds(List.of(card.capabilityId())).confidence(1).reasonCode(ReasonCode.HIGH_CONFIDENCE).build();
        Span span=tracer==null?null:tracer.nextSpan().name("agent.static-plan.step")
                .tag("agent.plan.id",planId).tag("agent.plan.step",String.valueOf(prepared.index()))
                .tag("agent.capability",card.capabilityId()).tag("agent.intent_path",Enums.TaskSource.SLOW_PATH.name())
                .tag("agent.invocation_origin",origin.name()).start();
        try(Tracer.SpanInScope ignored=span==null?null:tracer.withSpan(span)){
            AgentTaskOutcome outcome=tasks.execute(new AgentTaskRequest(context,decision,card,parameters,
                    prepared.item().text(),confirmed,List.of(),lease,Enums.TaskSource.SLOW_PATH,origin,
                    prepared.sourceInvocationId()));
            if(meters!=null)meters.counter(AgentMetrics.STATIC_PLAN_STEP,AgentMetrics.TAG_OUTCOME,
                    outcome.result()==null?"NO_RESULT":outcome.result().status().name()).increment();
            return new StepExecution(prepared.index(),prepared.item(),card,outcome);
        }catch(RuntimeException e){if(span!=null)span.error(e);throw e;}finally{if(span!=null)span.end();}
    }

    private PlanStepRecord successfulStep(String planId,int index){
        return plans.steps(planId).stream().filter(step->step.stepIndex()==index)
                .filter(step->step.status()==Enums.TaskStatus.SUCCESS).findFirst().orElse(null);
    }

    private String sourceInvocationId(String planId,int index){
        PlanStepRecord previous=plans.steps(planId).stream()
                .filter(step->step.stepIndex()==index)
                .filter(step->step.status()!=Enums.TaskStatus.SUCCESS)
                .findFirst().orElse(null);
        String attempt=previous==null?"0":String.valueOf(previous.completedAt().toEpochMilli());
        return planId+':'+index+':'+attempt;
    }

    private record PreparedStep(int index,SubIntent item,CapabilityCard card,String sourceInvocationId){}
    private record Preparation(PreparedStep step, ConditionEvaluator.Evaluation evaluation){}
    private record StepExecution(int index,SubIntent item,CapabilityCard card,AgentTaskOutcome outcome){}

    private ConditionEvaluator.Evaluation evaluateCondition(
            PlanRecord record, SubIntent item, int index, AssetBundle assets,
            Map<String, Object> parameters, Map<String, Map<String, Object>> facts) {
        PlanCondition condition = item.planCondition();
        if (condition == null) return conditions.explainPlan(item, facts, parameters);
        if (condition.expression() != null) {
            var validation = conditionValidator.validate(record.plan(), item, condition.expression(),
                    planCards(record.plan(), assets));
            return validation.valid()
                    ? conditions.evaluate(condition.originalText(), condition.expression(), facts, parameters)
                    : new ConditionEvaluator.Evaluation(ConditionEvaluator.Verdict.UNDECIDED,
                            condition.originalText(), Map.of(), validation.reason());
        }

        String digest = factDigest(item, facts, parameters);
        PlanConditionResolutionRecord cached = plans.findConditionResolution(
                record.planId(), index, digest).orElse(null);
        if (cached != null) {
            return cached.outcome() == PlanConditionResolutionRecord.Outcome.RESOLVED
                    ? conditions.evaluate(condition.originalText(), cached.expression(), facts, parameters)
                    : new ConditionEvaluator.Evaluation(ConditionEvaluator.Verdict.UNDECIDED,
                            condition.originalText(), Map.of(),
                            "deferred-condition-" + cached.outcome().name().toLowerCase());
        }

        ConditionResolver.Resolution resolved;
        try {
            resolved = conditionResolver.resolve(new ConditionResolver.Request(
                    record.planId(), record.plan(), item, facts, parameters,
                    planCards(record.plan(), assets))).orElse(null);
        } catch (RuntimeException failure) {
            resolved = null;
        }
        PlanConditionResolutionRecord.Outcome outcome;
        com.huawei.finance.contracts.model.ConditionExpression expression = null;
        String modelVersion = null;
        String promptVersion = null;
        String reason;
        if (resolved == null || resolved.expression() == null) {
            outcome = PlanConditionResolutionRecord.Outcome.UNRESOLVED;
            reason = "deferred-condition-unresolved";
        } else {
            var validation = conditionValidator.validate(record.plan(), item, resolved.expression(),
                    planCards(record.plan(), assets));
            if (validation.valid()) {
                outcome = PlanConditionResolutionRecord.Outcome.RESOLVED;
                expression = resolved.expression();
                reason = "deferred-condition-resolved";
            } else {
                outcome = PlanConditionResolutionRecord.Outcome.INVALID;
                reason = validation.reason();
            }
            modelVersion = resolved.modelVersion();
            promptVersion = resolved.promptVersion();
        }
        plans.saveConditionResolution(new PlanConditionResolutionRecord(
                record.planId(), index, condition.originalText(), expression, outcome, digest,
                modelVersion, promptVersion, Instant.now()));
        return expression == null
                ? new ConditionEvaluator.Evaluation(ConditionEvaluator.Verdict.UNDECIDED,
                        condition.originalText(), Map.of(), reason)
                : conditions.evaluate(condition.originalText(), expression, facts, parameters);
    }

    private static Map<String, CapabilityCard> planCards(IntentPlan plan, AssetBundle assets) {
        Map<String, CapabilityCard> cards = new LinkedHashMap<>();
        plan.items().forEach(item -> {
            CapabilityCard card = item.capabilityId() == null ? null : assets.capability(item.capabilityId());
            if (card != null) cards.put(card.capabilityId(), card);
        });
        return Map.copyOf(cards);
    }

    private static String factDigest(SubIntent item, Map<String, Map<String, Object>> facts,
                                     Map<String, Object> parameters) {
        Map<String, Object> input = new LinkedHashMap<>();
        item.dependsOn().forEach(stepId -> input.put(stepId, facts.getOrDefault(stepId, Map.of())));
        input.put("parameters", parameters == null ? Map.of() : parameters);
        try {
            byte[] bytes = ContractJson.mapper().writeValueAsBytes(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("STATIC_PLAN_CONDITION_DIGEST_FAILED", failure);
        }
    }

    private Map<String, Map<String, Object>> restoredFacts(PlanRecord record) {
        Map<String, Map<String, Object>> restored = new LinkedHashMap<>();
        plans.steps(record.planId()).stream()
                .filter(step -> step.stepIndex() < record.cursor())
                .filter(step -> step.status() == Enums.TaskStatus.SUCCESS)
                .filter(step -> step.stepIndex() < record.plan().items().size())
                .forEach(step -> restored.put(
                        record.plan().items().get(step.stepIndex()).stepId(), step.facts()));
        return restored;
    }

    private static PlanStepRecord stepRecord(
            String planId, int cursor, String capabilityId, String taskId, TaskResult result) {
        Enums.TaskStatus status = result == null ? Enums.TaskStatus.FAILED : result.status();
        Enums.FailureClass failure = result == null
                ? Enums.FailureClass.FATAL : result.failureClass();
        Map<String, Object> payload = result == null ? Map.of() : result.resultPayload();
        Object reason = payload.get("reasonCode");
        return new PlanStepRecord(planId, cursor, capabilityId, taskId, status, failure,
                payload, reason == null ? null : String.valueOf(reason), Instant.now());
    }

    private static boolean eligible(
            CapabilityCard card, Map<String, Object> parameters, RequestContext context) {
        if (card == null) {
            return false;
        }
        if (Boolean.TRUE.equals(card.principalRequired())
                && (context.userId() == null || context.userId().isBlank())) {
            return false;
        }
        return card.requiredSlots().stream().allMatch(slot -> {
            Object value = parameters.get(slot);
            return value != null && !String.valueOf(value).isBlank();
        });
    }

    private static boolean autoReadOnly(CapabilityCard card) {
        return card != null && card.riskLevel() == RiskLevel.R0 && !card.hasSideEffects();
    }

    private static String firstMissingSlot(CapabilityCard card,Map<String,Object> parameters){
        if(card==null)return "planInput";
        return card.requiredSlots().stream().filter(slot->{Object value=parameters.get(slot);
            return value==null||String.valueOf(value).isBlank();}).findFirst().orElse("planInput");
    }

    private static String pendingSlot(TaskResult result){
        if(result==null)return "planInput";
        Object missing=result.resultPayload().get("missingSlots");
        if(missing instanceof List<?> values&&!values.isEmpty())return String.valueOf(values.getFirst());
        return "planInput";
    }

    private static boolean conditionApproved(Map<String,Object> parameters){
        return parameters!=null&&"继续办理".equals(String.valueOf(parameters.get("conditionDecision")));
    }

    private static boolean conditionRejected(Map<String,Object> parameters){
        return parameters!=null&&"不办理".equals(String.valueOf(parameters.get("conditionDecision")));
    }

    private Optional<OrchestrationOutcome> cancel(
            RequestContext context, PlanRecord record, AssetBundle assets,
            Map<String,Object> parameters, ContextLease lease, Enums.InvocationOrigin origin,
            long expectedVersion) {
        if (!plans.transition(record.planId(), record.state(), PlanState.CANCELLED, expectedVersion)) {
            throw new IllegalStateException("STATIC_PLAN_VERSION_CONFLICT:" + record.planId());
        }
        SubIntent item = record.next().orElse(null);
        CapabilityCard card = item == null || item.capabilityId() == null
                ? null : assets.capability(item.capabilityId());
        if (card == null) {
            return Optional.of(new OrchestrationOutcome(null, TaskState.CANCELLED,
                    null, null, GuardrailCheck.pending(), null));
        }
        RouteDecision decision = RouteDecision.builder().decision(Decision.CANCEL)
                .candidateIds(List.of(card.capabilityId())).confidence(1)
                .reasonCode(ReasonCode.CONTINUATION).build();
        AgentTaskOutcome cancelled = tasks.execute(new AgentTaskRequest(context, decision, card,
                parameters, item.text(), false, List.of(), lease, Enums.TaskSource.SLOW_PATH,
                origin, record.planId() + ':' + record.cursor()));
        return Optional.of(new OrchestrationOutcome(cancelled.taskId(), TaskState.CANCELLED,
                null, cancelled.result(), cancelled.guardrail(), null));
    }

    private static TaskState taskState(AgentTaskOutcome outcome) {
        if (outcome.orchestrationState() == null || outcome.orchestrationState().isBlank()) {
            return outcome.result() == null ? null : switch (outcome.result().status()) {
                case SUCCESS -> TaskState.SUCCEEDED;
                case PARTIAL, FAILED -> TaskState.FAILED;
                case CANCELLED -> TaskState.CANCELLED;
                case NEED_USER -> TaskState.CLARIFY_PENDING;
            };
        }
        return TaskState.valueOf(outcome.orchestrationState());
    }

    private static PlanState waitingState(TaskState state, TaskResult result) {
        if (state == TaskState.REVIEW_PENDING) return PlanState.WAITING_REVIEW;
        if (state == TaskState.CONFIRM_PENDING) return PlanState.WAITING_CONFIRMATION;
        if (state == TaskState.CLARIFY_PENDING
                || (result != null && result.status() == Enums.TaskStatus.NEED_USER)) {
            return PlanState.WAITING_USER;
        }
        return null;
    }

    private static Map<String, Object> capabilityFacts(
            IntentPlan plan, Map<String, Map<String, Object>> stepFacts) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        plan.items().forEach(item -> {
            Map<String, Object> value = stepFacts.get(item.stepId());
            if (value != null && item.capabilityId() != null) capabilities.put(item.capabilityId(), value);
        });
        return capabilities;
    }

    private static void recordConditionBreak(RequestContext context, int stepIndex, SubIntent item,
                                             ConditionEvaluator.Evaluation evaluation) {
        if (context == null || item == null || item.condition() == null) {
            return;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepIndex", stepIndex);
        input.put("summary", item.summary() == null ? "" : item.summary());
        input.put("capabilityId", item.capabilityId() == null ? "" : item.capabilityId());
        input.put("condition", evaluation.condition() == null ? "" : evaluation.condition());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("verdict", evaluation.verdict().name());
        output.put("resolvedValues", evaluation.resolvedValues());
        output.put("reason", evaluation.reason() == null ? "" : evaluation.reason());
        output.put("brokeAutoLoop", true);
        context.recordModuleStep(new RuntimeModuleStep(
                "intent-slowpath", "condition-gate", "MAIN",
                input, output, evaluation.verdict().name(), null));
    }

    private static OrchestrationOutcome outcome(
            String taskId, TaskResult result, Map<String, Object> facts, GuardrailCheck guardrail) {
        TaskState state = result == null ? TaskState.CLARIFY_PENDING : switch (result.status()) {
            case SUCCESS -> TaskState.SUCCEEDED;
            case PARTIAL -> TaskState.FAILED;
            case NEED_USER -> TaskState.CLARIFY_PENDING;
            case FAILED -> TaskState.FAILED;
            case CANCELLED -> TaskState.CANCELLED;
        };
        return new OrchestrationOutcome(taskId, state, null, result, guardrail, null);
    }

    private static TaskResult success(String taskId, Map<String, Object> facts) {
        return new TaskResult(taskId, Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("capabilities", Map.copyOf(facts)), taskId, GuardrailCheck.passed());
    }

    private static TaskResult successWithConditionStop(
            String taskId, Map<String, Object> facts, Map<String, Object> conditionStop) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capabilities", Map.copyOf(facts));
        payload.put("conditionNotMet", true);
        payload.putAll(conditionStop);
        return new TaskResult(taskId, Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                payload, taskId, GuardrailCheck.passed());
    }

    private static TaskResult withCompletedFacts(
            TaskResult result, Map<String, Object> completedFacts) {
        if (result == null || completedFacts.isEmpty()) {
            return result;
        }
        Map<String, Object> payload = new LinkedHashMap<>(result.resultPayload());
        payload.put("completedCapabilities", Map.copyOf(completedFacts));
        return new TaskResult(result.taskId(), result.status(), result.failureClass(), payload,
                result.idempotencyKey(), result.guardrailCheck());
    }
}
