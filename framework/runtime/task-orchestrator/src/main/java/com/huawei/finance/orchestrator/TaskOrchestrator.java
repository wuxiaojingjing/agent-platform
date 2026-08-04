package com.huawei.finance.orchestrator;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.contracts.port.GuardrailHook;
import com.huawei.finance.contracts.port.SessionLock;
import com.huawei.finance.contracts.port.SessionLockManager;
import com.huawei.finance.contracts.port.ExecutionParameterResolver;
import com.huawei.finance.orchestrator.idempotency.IdempotencyKeys;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 中控（v0.7 §3.4、实施架构 §8）。
 *
 * <p>整条链路里唯一的事务边界。领域 Agent 只负责「做这一件事」，做没做过、能不能做、
 * 做完之后任务停在哪，全部由这里判定并落库。
 *
 * <p>执行顺序不可调换：**建档 → 护栏 → 发幂等键 → 迁 RUNNING → 调用领域 Agent**。
 * 幂等键提前一步发放，就等于在护栏还没表态时先给了一张可以重放的执行许可（§8.4）。
 */
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private static final Duration LOCK_WAIT = Duration.ofSeconds(2);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(15);

    private final TaskRepository repository;
    private final GuardrailHook guardrail;
    private final AgentInvoker invoker;
    private final SessionLockManager locks;
    private final ContractValidator validator;
    private final MeterRegistry meterRegistry;
    private final Optional<TaskContextStore> taskContexts;
    private final List<ExecutionParameterResolver> parameterResolvers;

    public TaskOrchestrator(TaskRepository repository, GuardrailHook guardrail, AgentInvoker invoker,
                            SessionLockManager locks, ContractValidator validator, MeterRegistry meterRegistry) {
        this(repository, guardrail, invoker, locks, validator, meterRegistry, Optional.empty());
    }

    public TaskOrchestrator(TaskRepository repository, GuardrailHook guardrail, AgentInvoker invoker,
                            SessionLockManager locks, ContractValidator validator, MeterRegistry meterRegistry,
                            Optional<TaskContextStore> taskContexts) {
        this(repository, guardrail, invoker, locks, validator, meterRegistry, taskContexts, List.of());
    }

    public TaskOrchestrator(TaskRepository repository, GuardrailHook guardrail, AgentInvoker invoker,
                            SessionLockManager locks, ContractValidator validator, MeterRegistry meterRegistry,
                            Optional<TaskContextStore> taskContexts,
                            List<ExecutionParameterResolver> parameterResolvers) {
        this.repository = repository;
        this.guardrail = guardrail;
        this.invoker = invoker;
        this.locks = locks;
        this.validator = validator;
        this.meterRegistry = meterRegistry;
        this.taskContexts = taskContexts == null ? Optional.empty() : taskContexts;
        this.parameterResolvers = parameterResolvers == null ? List.of() : List.copyOf(parameterResolvers);
    }

    /**
     * 按出口处置任务。
     *
     * <p>整个处置过程持有会话级分布式锁。同一会话的两次快速点击若并发进入，
     * 会双双读到「无活跃任务」并各建一个任务，而后被唯一索引拒掉一个——
     * 用户看到的是随机的失败。锁把这种竞态挡在前面。
     */
    public OrchestrationOutcome handle(OrchestrationRequest request) {
        SessionLock lock = null;
        try {
            lock = locks.tryLock(
                    com.huawei.finance.common.context.ScopeKeys.sessionLock(request.ctx()),
                    LOCK_WAIT, LOCK_LEASE).orElse(null);
            if (lock == null) {
                log.warn("会话锁获取超时，拒绝本轮处置 agent={} session={}",
                        request.ctx().agentId(), request.ctx().sessionId());
                return OrchestrationOutcome.none();
            }
            return dispatch(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OrchestrationOutcome.none();
        } finally {
            if (lock != null) {
                lock.close();
            }
        }
    }

    private OrchestrationOutcome dispatch(OrchestrationRequest request) {
        Decision decision = request.decision().decision();
        if (request.sourceInvocationId() != null && !request.sourceInvocationId().isBlank()) {
            Optional<TaskRecord> replay = repository.findBySourceInvocation(
                    request.ctx().agentId(), request.invocationOrigin(), request.sourceInvocationId());
            if (replay.isPresent()) {
                TaskRecord task = replay.get();
                if (!task.state().terminal()
                        && task.state() != TaskState.RUNNING
                        && switch (decision) {
                            case EXECUTE_CAPABILITY, START_WORKFLOW, DELEGATE_GOAL, RESUME_TASK -> true;
                            default -> false;
                        }) {
                    return fastExecute(request, replay);
                }
                if (!task.state().terminal() && decision == Decision.CANCEL) {
                    return reject(request, replay);
                }
                return new OrchestrationOutcome(task.taskId(), task.state(), null,
                        repository.resultOf(task.taskId()).orElse(null), task.guardrail(), task.pendingSlot());
            }
        }
        Optional<TaskRecord> active = activeTask(request);

        return switch (decision) {
            case EXECUTE_CAPABILITY, START_WORKFLOW, DELEGATE_GOAL, RESUME_TASK -> fastExecute(request, active);
            case CLARIFY -> clarify(request, active);
            case REJECT, HANDOFF, CANCEL -> reject(request, active);
            // Static Plan 的可执行步骤由 StaticPlanCoordinator 在本类外逐步调用
            // EXECUTE_CAPABILITY；走到这里表示当前计划不能自动推进，只挂起。
            case STATIC_PLAN, START_LOOP, RESUME_LOOP -> suspend(request, active);
            case DIRECT_KNOWLEDGE, NAVIGATION -> OrchestrationOutcome.none();
        };
    }

    private Optional<TaskRecord> activeTask(OrchestrationRequest request) {
        if (taskContexts.isEmpty()) {
            return repository.findActiveBySession(request.ctx().agentId(), request.ctx().sessionId());
        }
        FocusFrame foreground = taskContexts.get().focus(request.ctx().spaceId(), request.ctx().agentId(),
                request.ctx().sessionId()).foreground();
        if (foreground == null || foreground.subjectType() != FocusSubjectType.PLATFORM_TASK) {
            return Optional.empty();
        }
        PlatformTask platformTask = taskContexts.get().task(request.ctx().spaceId(), request.ctx().agentId(),
                foreground.subjectRef()).orElse(null);
        if (platformTask == null || platformTask.bindingState() != BindingState.BOUND
                || (platformTask.runtimeType() != RuntimeType.TASK
                    && platformTask.runtimeType() != RuntimeType.WORKFLOW)) {
            return Optional.empty();
        }
        return repository.findById(platformTask.runtimeRef())
                .filter(task -> task.state().active())
                .filter(task -> request.ctx().agentId().equals(task.agentId()))
                .filter(task -> request.ctx().sessionId().equals(task.sessionId()));
    }

    /**
     * 直出执行。
     *
     * <p>刻意不用一个大事务包住整段：中间要调领域 Agent，一次远程调用可能跑上几秒，
     * 把数据库事务挂在上面等于让连接池陪着一起等。状态一致性改由条件更新
     * （{@code where state = ?}）与会话锁保证，这两者不占连接。
     */
    private OrchestrationOutcome fastExecute(OrchestrationRequest request, Optional<TaskRecord> active) {
        CapabilityCard card = request.capability();
        if (card == null) {
            log.error("EXECUTE_CAPABILITY 未指向任何能力 trace={}", request.ctx().traceId());
            return OrchestrationOutcome.none();
        }

        TaskRecord task = active
                .filter(t -> t.capabilityId().equals(card.capabilityId()))
                .map(t -> refreshParameters(t, ownedSlots(card, request.slots())))
                .orElseGet(() -> createSuperseding(active, request, card));

        ConfirmationPolicy confirmation = card.confirmationPolicy();
        if (confirmation != ConfirmationPolicy.NONE && !request.confirmed()) {
            TaskState waiting = confirmation == ConfirmationPolicy.REVIEW_ONLY
                    ? TaskState.REVIEW_PENDING : TaskState.CONFIRM_PENDING;
            if (task.state() != waiting) {
                transition(task, waiting, confirmation == ConfirmationPolicy.REVIEW_ONLY
                        ? "await-review" : "await-explicit-confirmation", request.ctx());
            }
            return new OrchestrationOutcome(task.taskId(), waiting, null, null,
                    GuardrailCheck.pending(), null);
        }

        return execute(request, task, card);
    }

    /**
     * 下发给领域 Agent 的上下文引用。
     *
     * <p>只给租约 id，不给租约内容。领域方需要核对当时的上下文时按 id 回查，
     * 而不是每次执行都被塞一份可能已经过期的副本——副本一旦被当成现值使用，
     * 就成了「按上一轮的收款人执行这一轮的转账」。
     */
    private static List<String> contextRefs(OrchestrationRequest request) {
        String leaseId = request.lease().leaseId();
        return leaseId == null ? List.of() : List.of("lease:" + leaseId);
    }

    private OrchestrationOutcome execute(OrchestrationRequest request, TaskRecord task, CapabilityCard card) {
        // 上下文不可信时停掉有副作用的操作（v0.7 §4.0、FP-28）。
        //
        // 位置刻意与护栏并列、且在幂等键之前：租约不可信意味着我们不知道用户此前确认过什么，
        // 此时发出的幂等键是一张依据不明的执行许可。只读能力不拦——按策略降级是允许的，
        // 查一次余额最坏是多查一次
        if (card.hasSideEffects() && !request.lease().allowsSideEffects()) {
            log.error("上下文不可信，拒绝执行有副作用的能力 task={} capability={} 租约可信={} 已过期={}",
                    task.taskId(), card.capabilityId(), request.lease().trustworthy(),
                    request.lease().isExpired(Instant.now()));
            meterRegistry.counter(AgentMetrics.CONTEXT_SIDE_EFFECT_BLOCKED,
                    AgentMetrics.TAG_CAPABILITY, card.capabilityId()).increment();

            GuardrailCheck blocked = GuardrailCheck.failed(List.of("CONTEXT_UNAVAILABLE"));
            repository.updateGuardrail(task.taskId(), blocked);
            transition(task, TaskState.GUARDRAIL_BLOCKED, "context-unavailable", request.ctx());
            return new OrchestrationOutcome(task.taskId(), TaskState.GUARDRAIL_BLOCKED, null, null,
                    blocked, null);
        }

        Map<String, Object> confirmation = request.confirmed()
                ? Map.of(card.confirmationPolicy() == ConfirmationPolicy.REVIEW_ONLY ? "reviewedAt" : "confirmedAt",
                        Instant.now().toString(), "confirmedBy", nullSafe(request.ctx().userId()))
                : Map.of();
        ParameterResolution parameterResolution = parametersForExecution(request, task, card);
        if (!parameterResolution.resolved()) {
            GuardrailCheck blocked = GuardrailCheck.failed(List.of(parameterResolution.reasonCode()));
            repository.updateGuardrail(task.taskId(), blocked);
            transition(task, TaskState.GUARDRAIL_BLOCKED,
                    "execution-parameter:" + parameterResolution.reasonCode(), request.ctx());
            return new OrchestrationOutcome(task.taskId(), TaskState.GUARDRAIL_BLOCKED,
                    null, null, blocked, null);
        }
        Map<String,Object> executionParameters = parameterResolution.parameters();

        // 草稿任务：护栏未表态，UnifiedTask 的构造器此时不允许携带幂等键
        UnifiedTask draft = new UnifiedTask(
                task.taskId(), request.ctx().traceId(), request.source(), request.invocationOrigin(), task.goal(),
                card.capabilityId(), executionParameters, card.riskLevel(), confirmation,
                GuardrailCheck.pending(), null, List.of(), Instant.now().plusSeconds(30),
                subtaskContext(request, card));

        GuardrailCheck check = guardrail.check(draft, card);
        repository.updateGuardrail(task.taskId(), check);

        if (!check.isPassed()) {
            transition(task, TaskState.GUARDRAIL_BLOCKED, "guardrail:" + String.join(",", check.codes()),
                    request.ctx());
            meterRegistry.counter(AgentMetrics.DEGRADED,
                    AgentMetrics.TAG_COMPONENT, "guardrail",
                    AgentMetrics.TAG_REASON, check.codes().isEmpty() ? "unknown" : check.codes().get(0)).increment();
            return new OrchestrationOutcome(task.taskId(), TaskState.GUARDRAIL_BLOCKED, null, null, check, null);
        }

        String key = request.sourceInvocationId() == null || request.sourceInvocationId().isBlank()
                ? IdempotencyKeys.of(task.taskId(), card.capabilityId(), task.parameters())
                : request.sourceInvocationId();
        if (!repository.attachIdempotencyKey(task.taskId(), card.capabilityId(), key)) {
            // 这把键已经发过，说明同样的任务同样的参数已经执行过一次。
            // 重复执行的代价（重复转账）远大于让用户再问一次
            log.warn("幂等键已存在，跳过重复执行 task={} key={}", task.taskId(), key);
            return new OrchestrationOutcome(task.taskId(), task.state(), null, null, check, null);
        }

        if (!transition(task, TaskState.RUNNING, "guardrail-passed", request.ctx())) {
            return new OrchestrationOutcome(task.taskId(), task.state(), null, null, check, null);
        }

        // deadline 由能力卡声明与主控上限共同决定，不再是硬编码的 30 秒——
        // 那个值此前既与卡上的 timeoutMs 无关，也从未被任何代码读取过
        long timeoutMs = invoker.effectiveTimeoutMs(card);
        UnifiedTask executable = new UnifiedTask(
                task.taskId(), request.ctx().traceId(), request.source(), request.invocationOrigin(), task.goal(),
                card.capabilityId(), executionParameters, card.riskLevel(), confirmation,
                check, key, contextRefs(request), Instant.now().plusMillis(timeoutMs),
                subtaskContext(request, card));
        validator.validate(SchemaRef.UNIFIED_TASK, executable).orThrow("UnifiedTask");
        traceRawGoalDelegation(card, executable);

        TaskResult result = invoker.invoke(executable, card);
        validator.validate(SchemaRef.TASK_RESULT, result).orThrow("TaskResult");
        repository.saveResult(task.taskId(), result);

        TaskState next = switch (result.status()) {
            case SUCCESS -> TaskState.SUCCEEDED;
            case PARTIAL, FAILED -> TaskState.FAILED;
            case CANCELLED -> TaskState.CANCELLED;
            case NEED_USER -> TaskState.CLARIFY_PENDING;
        };
        String pendingSlot = null;
        if (result.status() == Enums.TaskStatus.NEED_USER) {
            List<String> missing = missingSlots(result);
            pendingSlot = missing.isEmpty() ? null : missing.getFirst();
            repository.updateClarifyState(task.taskId(), task.parameters(), pendingSlot,
                    List.of(), task.clarifyRounds() + 1);
        }
        transition(taskAt(task, TaskState.RUNNING), next, "agent:" + result.status(), request.ctx());

        return new OrchestrationOutcome(task.taskId(), next, executable, result, check, pendingSlot);
    }

    private static List<String> missingSlots(TaskResult result) {
        if (result == null || result.resultPayload() == null) return List.of();
        Object raw = result.resultPayload().get("missingSlots");
        if (!(raw instanceof List<?> values)) return List.of();
        return values.stream().map(value -> {
            if (value instanceof Map<?, ?> map) {
                Object slot = map.get("slot");
                return slot == null ? null : String.valueOf(slot);
            }
            return value == null ? null : String.valueOf(value);
        }).filter(value -> value != null && !value.isBlank()).toList();
    }

    private ParameterResolution parametersForExecution(OrchestrationRequest request, TaskRecord task,
                                                        CapabilityCard card) {
        Map<String, Object> parameters = new LinkedHashMap<>(task.parameters());
        var principal = request.ctx().principal();
        String subjectRef = principal == null ? null : principal.subjectRef();
        for (ExecutionParameterResolver resolver : parameterResolvers) {
            ExecutionParameterResolver.Resolution resolved = resolver.resolve(
                    card.capabilityId(), parameters, request.lease(), subjectRef);
            if (!resolved.resolved()) {
                return new ParameterResolution(false, parameters,
                        resolved.reasonCode() == null
                                ? "EXECUTION_PARAMETER_UNRESOLVED" : resolved.reasonCode());
            }
            parameters = new LinkedHashMap<>(resolved.parameters());
        }
        parameters.keySet().removeIf(key -> key.startsWith("__context."));
        if (Boolean.TRUE.equals(card.principalRequired()) && subjectRef != null && !subjectRef.isBlank()) {
            parameters.put("principalRef", subjectRef);
        }
        return new ParameterResolution(true, Map.copyOf(parameters), null);
    }

    private record ParameterResolution(boolean resolved, Map<String, Object> parameters,
                                       String reasonCode) { }

    private static SubtaskContextEnvelope subtaskContext(
            OrchestrationRequest request, CapabilityCard card) {
        List<ContextEvidence> evidence = request.intentContext() == null
                ? List.of()
                : request.intentContext().evidence().stream()
                        .filter(item -> item.kind() != ContextEvidence.Kind.USER_TURN)
                        .filter(item -> item.validAt(Instant.now()))
                        .toList();
        return new SubtaskContextEnvelope(
                request.lease().leaseId(), request.lease().stateVersion(),
                request.lease().expiresAt(), request.goal(), request.lease().confirmedFacts(),
                evidence, List.of(card.capabilityId()),
                List.of(SubtaskContextEnvelope.Scope.SUBTASK,
                        SubtaskContextEnvelope.Scope.DOMAIN),
                SubtaskContextEnvelope.Scope.SUBTASK);
    }

    /**
     * R2 下发原句的留痕（FP-1K）。
     *
     * <p>{@code goal} 一路来自 {@code rewrite.original()}，也就是用户真说的那句话，
     * 而不是为检索改写归一过的版本。这条链路上很容易在某次重构里被换成 {@code normalized()}——
     * 两者类型相同、字段名相近、多数情况下取值也差不多，换错了不会有任何编译或运行时报错，
     * 只会在事后审计里留下一句用户从未说过的话。
     *
     * <p>打点的意义在于把这件事变成可查的事实：R2 执行数与本计数应当恒等，对不上就是漏发了。
     */
    private void traceRawGoalDelegation(CapabilityCard card, UnifiedTask executable) {
        if (card.riskLevel() != RiskLevel.R2) {
            return;
        }
        meterRegistry.counter(AgentMetrics.DELEGATE_R2_RAW_GOAL,
                AgentMetrics.TAG_CAPABILITY, card.capabilityId()).increment();
        log.info("R2 下发原句 task={} capability={} goalLength={}",
                executable.taskId(), card.capabilityId(),
                executable.goal() == null ? 0 : executable.goal().length());
    }

    private OrchestrationOutcome clarify(OrchestrationRequest request, Optional<TaskRecord> active) {
        if (request.decision().missingSlots().isEmpty()) {
            // 入口尚未选定业务，不创建绑定候选或 unknown 的假任务。
            // 业务多选或用户后续补充作为新目标重新路由；只有锁定能力后的缺槽才需要 Runtime 续办。
            return OrchestrationOutcome.none();
        }
        CapabilityCard card = request.capability();
        String pendingSlot = request.decision().missingSlots().isEmpty()
                ? null : request.decision().missingSlots().get(0);

        TaskRecord task = active
                .filter(t -> card == null || t.capabilityId().equals(card.capabilityId()))
                .orElseGet(() -> createSuperseding(active, request, card));

        int rounds = task.clarifyRounds() + 1;
        Map<String, Object> merged = merge(task.parameters(), ownedSlots(card, request.slots()));
        repository.updateClarifyState(task.taskId(), merged, pendingSlot, request.expectedAnswers(), rounds);

        if (task.state() != TaskState.CLARIFY_PENDING) {
            transition(task, TaskState.CLARIFY_PENDING, "missing-slot:" + pendingSlot, request.ctx());
        }
        return new OrchestrationOutcome(task.taskId(), TaskState.CLARIFY_PENDING, null, null,
                GuardrailCheck.pending(), pendingSlot);
    }

    private OrchestrationOutcome reject(OrchestrationRequest request, Optional<TaskRecord> active) {
        if (request.decision().reasonCode() == ReasonCode.STANDARD_ANSWER) {
            // 标准问答与拒绝共用一个出口，但语义相反：那是「办不了」，这是「答完了」。
            // 走到下面会把在办任务判成 CANCELLED——用户在转账确认途中问一句手续费怎么算，
            // 转账就没了，而他只是问了个问题
            return OrchestrationOutcome.none();
        }
        if (active.isEmpty()) {
            return OrchestrationOutcome.none();
        }
        TaskRecord task = active.get();
        transition(task, TaskState.CANCELLED, "reject-or-handoff", request.ctx());
        return new OrchestrationOutcome(task.taskId(), TaskState.CANCELLED, null, null,
                task.guardrail(), null);
    }

    private OrchestrationOutcome suspend(OrchestrationRequest request, Optional<TaskRecord> active) {
        return active
                .map(task -> new OrchestrationOutcome(task.taskId(), task.state(), null, null,
                        task.guardrail(), task.pendingSlot()))
                .orElseGet(OrchestrationOutcome::none);
    }

    /**
     * 建新任务，必要时先让旧任务让位。
     *
     * <p>一个会话同时只允许一个活跃任务（V1 迁移里的唯一索引）。用户在澄清途中转去问别的事，
     * 旧任务就不能再占着活跃位：不取消它，新任务的 insert 会被唯一索引拒掉，
     * 用户看到的是一次没有任何解释的失败。并行任务是后续切片的能力，
     * 在此之前「后来的意图取代先前的意图」是明确选择，而不是让数据库来兜底。
     */
    private TaskRecord createSuperseding(Optional<TaskRecord> active, OrchestrationRequest request,
                                         CapabilityCard card) {
        active.ifPresent(previous -> {
            log.info("新意图取代活跃任务 session={} 旧任务={} 旧能力={} 新能力={}",
                    request.ctx().sessionId(), previous.taskId(), previous.capabilityId(),
                    card == null ? "unknown" : card.capabilityId());
            transition(previous, TaskState.CANCELLED, "superseded-by-new-task", request.ctx());
        });
        return create(request, card, TaskState.CREATED);
    }

    private TaskRecord create(OrchestrationRequest request, CapabilityCard card, TaskState initial) {
        RequestContext ctx = request.ctx();
        TaskRecord task = new TaskRecord(
                "task-" + UUID.randomUUID(),
                ctx.agentId(),
                ctx.traceId(),
                ctx.sessionId(),
                ctx.userId(),
                card == null ? "unknown" : card.capabilityId(),
                card == null || card.domains().isEmpty() ? null : card.domains().get(0),
                request.goal(),
                initial,
                card == null ? RiskLevel.R0 : card.riskLevel(),
                request.source(),
                request.invocationOrigin(),
                ownedSlots(card, request.slots()),
                null,
                List.of(),
                0,
                GuardrailCheck.pending(),
                null,
                request.sourceInvocationId());
        repository.insert(task);
        return task;
    }

    /**
     * 只把能力卡声明过的槽位交给领域 Agent。
     *
     * <p>这是银行内部的分工边界落到代码上的那一行。能力卡声明了必填槽位，等于领域方把
     * 「这些参数叫什么、缺了要问」的口径交给主 Agent；没声明，主 Agent 就不得替它猜——
     * 抽槽是在选中能力之前做的，抽到的东西必然多于任何一张卡的声明范围，多出来的那部分
     * 语义由谁定义、抽错了谁负责，都没有答案。
     *
     * <p>被丢弃的槽位不是静默丢弃：打点出来，才有依据去找领域方补声明。
     */
    private Map<String, Object> ownedSlots(CapabilityCard card, Map<String, Object> extracted) {
        if (card == null) {
            return Map.of();
        }
        Map<String, Object> owned = card.ownedSlots(extracted);
        if (extracted != null && extracted.get("principalRef") != null) {
            Map<String, Object> withPrincipal = new LinkedHashMap<>(owned);
            withPrincipal.put("principalRef", extracted.get("principalRef"));
            owned = Map.copyOf(withPrincipal);
        }
        if (extracted != null) {
            Map<String, Object> withContextMetadata = new LinkedHashMap<>(owned);
            extracted.forEach((key, value) -> {
                if (key.startsWith("__context.") && value != null) {
                    withContextMetadata.put(key, value);
                }
            });
            owned = Map.copyOf(withContextMetadata);
        }
        if (extracted != null) {
            for (String slot : extracted.keySet()) {
                if (!owned.containsKey(slot)) {
                    meterRegistry.counter(AgentMetrics.SLOT_NOT_OWNED,
                            AgentMetrics.TAG_CAPABILITY, card.capabilityId(),
                            AgentMetrics.TAG_SLOT, slot).increment();
                }
            }
        }
        return owned;
    }

    private TaskRecord refreshParameters(TaskRecord task, Map<String, Object> slots) {
        Map<String, Object> merged = merge(task.parameters(), slots);
        if (merged.equals(task.parameters())) {
            return task;
        }
        repository.updateParameters(task.taskId(), merged);
        return new TaskRecord(task.taskId(), task.agentId(), task.traceId(), task.sessionId(),
                task.userId(), task.capabilityId(), task.domain(), task.goal(), task.state(),
                task.riskLevel(), task.source(), task.invocationOrigin(), merged,
                task.pendingSlot(), task.expectedAnswers(),
                task.clarifyRounds(), task.guardrail(), task.idempotencyKey(), task.sourceInvocationId());
    }

    private boolean transition(TaskRecord task, TaskState to, String reason, RequestContext ctx) {
        boolean ok = repository.transition(task.taskId(), task.state(), to, reason, ctx.traceId());
        if (ok) {
            meterRegistry.counter(AgentMetrics.TASK_TRANSITION,
                    AgentMetrics.TAG_FROM, task.state().name(),
                    AgentMetrics.TAG_TO, to.name()).increment();
        } else {
            log.warn("状态已被其他请求改变，本次迁移放弃 task={} expected={} to={}",
                    task.taskId(), task.state(), to);
        }
        return ok;
    }

    /** 构造一个「状态已推进到 state」的视图，用于连续迁移时避免重新查库。 */
    private static TaskRecord taskAt(TaskRecord task, TaskState state) {
        return new TaskRecord(task.taskId(), task.agentId(), task.traceId(), task.sessionId(),
                task.userId(), task.capabilityId(), task.domain(), task.goal(), state,
                task.riskLevel(), task.source(), task.invocationOrigin(), task.parameters(), task.pendingSlot(),
                task.expectedAnswers(), task.clarifyRounds(), task.guardrail(), task.idempotencyKey(),
                task.sourceInvocationId());
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        override.forEach((k, v) -> {
            if (v != null) {
                merged.put(k, v);
            }
        });
        return merged;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
