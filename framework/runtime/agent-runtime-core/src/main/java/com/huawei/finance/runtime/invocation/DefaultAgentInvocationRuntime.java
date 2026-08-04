package com.huawei.finance.runtime.invocation;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.common.context.InvocationLineage;
import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.context.ContextLeaseCompiler;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.TaskResultMetadata;
import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.runtime.AgentRequest;
import com.huawei.finance.runtime.AgentResponse;
import com.huawei.finance.runtime.AgentRuntime;
import com.huawei.finance.runtime.spi.RuntimeEnginesSource;
import com.huawei.finance.runtime.task.AgentTaskExecutor;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import com.huawei.finance.runtime.task.AgentTaskRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** 统一实现 GOAL 完整 Runtime 与 TASK 结构化 Runtime。 */
public final class DefaultAgentInvocationRuntime implements AgentInvocationRuntime {

    private final AgentRuntime runtime;
    private final AgentTaskExecutor tasks;
    private final RuntimeEnginesSource engines;
    private final ContextLeaseCompiler leases;
    private final TaskRepository repository;
    private final AgentIdentity identity;
    private final Tracer tracer;

    public DefaultAgentInvocationRuntime(
            AgentRuntime runtime, AgentTaskExecutor tasks, RuntimeEnginesSource engines,
            ContextLeaseCompiler leases, TaskRepository repository, AgentIdentity identity) {
        this(runtime, tasks, engines, leases, repository, identity, null);
    }

    public DefaultAgentInvocationRuntime(
            AgentRuntime runtime, AgentTaskExecutor tasks, RuntimeEnginesSource engines,
            ContextLeaseCompiler leases, TaskRepository repository, AgentIdentity identity,
            Tracer tracer) {
        this.runtime = runtime;
        this.tasks = tasks;
        this.engines = engines;
        this.leases = leases;
        this.repository = repository;
        this.identity = identity;
        this.tracer = tracer;
    }

    @Override
    public AgentInvocationOutcome invoke(AgentInvocationRequest request) {
        Span span = start("agent.a2a.target.runtime", request);
        AgentInvocationOutcome outcome;
        try (Tracer.SpanInScope ignored = scope(span)) {
            outcome = invokeInternal(request);
            if (span != null) {
                span.tag("agent.outcome", value(outcome.state()));
                span.tag("agent.reason_code", value(outcome.reasonCode()));
            }
        } catch (RuntimeException e) {
            if (span != null) span.error(e);
            throw e;
        } finally {
            if (span != null) span.end();
        }
        return outcome;
    }

    private AgentInvocationOutcome invokeInternal(AgentInvocationRequest request) {
        if (!identity.id().equals(request.targetAgentId())) {
            return AgentInvocationOutcome.rejected("NOT_MINE");
        }
        if (request.deadline() != null && Instant.now().isAfter(request.deadline())) {
            return AgentInvocationOutcome.rejected("DELEGATION_DEADLINE_PASSED");
        }
        AgentInvocationOutcome outcome = request.mode() == DelegationMode.GOAL
                ? goal(request) : task(request);
        return withContextDelta(request, outcome);
    }

    private AgentInvocationOutcome goal(AgentInvocationRequest request) {
        AgentResponse response = runtime.handle(new AgentRequest(
                request.targetSessionId(), request.goal(), subject(request), request.tenantId(),
                channel(request), "", "", Map.of("invocationOrigin", "A2A"),
                null, principalState(request), lineage(request), request.subtaskContext()));
        TaskResult result = response.taskId() == null
                ? null : repository.resultOf(response.taskId()).orElse(null);
        com.huawei.finance.orchestrator.task.TaskRecord targetTask =
                response.taskId() == null || repository == null
                        ? null : repository.findById(response.taskId()).orElse(null);
        Enums.TaskSource targetPath = targetTask == null ? null : targetTask.source();
        return outcome(response.taskId(), result, response.decision().reasonCode() == null
                ? null : response.decision().reasonCode().name(), targetPath,
                Enums.InvocationOrigin.A2A, targetTask == null ? null : targetTask.capabilityId());
    }

    private AgentInvocationOutcome task(AgentInvocationRequest request) {
        CapabilityCard card = engines.current().bundle().capability(request.capabilityId());
        if (card == null || !identity.id().equals(card.parentCapabilityId())) {
            return AgentInvocationOutcome.rejected("NOT_MINE");
        }
        boolean verified = request.principal() != null && request.principal().verified();
        if (Boolean.TRUE.equals(card.principalRequired()) && !verified) {
            return AgentInvocationOutcome.rejected("PRINCIPAL_REQUIRED");
        }
        if (card.hasSideEffects() && !verified) {
            return AgentInvocationOutcome.rejected("PRINCIPAL_REQUIRED_FOR_SIDE_EFFECT");
        }

        List<String> missing = card.requiredSlots().stream()
                .filter(slot -> !present(request.parameters().get(slot))).toList();
        Decision decision = missing.isEmpty() ? Decision.EXECUTE_CAPABILITY : Decision.CLARIFY;
        RouteDecision arbitration = RouteDecision.builder()
                .decision(decision)
                .candidateIds(List.of(card.capabilityId()))
                .confidence(1.0)
                .reasonCode(missing.isEmpty() ? ReasonCode.HIGH_CONFIDENCE : ReasonCode.MISSING_SLOT)
                .missingSlots(missing)
                .build();
        RequestContext context = new RequestContext(
                request.traceId(), request.targetSessionId(), subject(request), request.tenantId(),
                identity.id(), channel(request), "", "", false,
                principalState(request), lineage(request));
        var lease = compileLease(request);
        Map<String, Object> targetParameters = new java.util.LinkedHashMap<>(request.parameters());
        if (request.principal() != null && request.principal().subjectRef() != null) {
            targetParameters.put("principalRef", request.principal().subjectRef());
        }
        RequestContextHolder.set(context);
        try {
            AgentTaskOutcome result = executeTask(request, new AgentTaskRequest(
                    context, arbitration, card, targetParameters, request.goal(),
                    !request.confirmedFacts().isEmpty(), List.of(), lease,
                    request.intentPath(), request.invocationOrigin(), request.sourceInvocationId()));
            if (result.result() == null) {
                String state = result.orchestrationState() == null
                        ? "PENDING" : result.orchestrationState();
                if (!missing.isEmpty()) {
                    return new AgentInvocationOutcome(result.taskId(), state, null, Map.of(),
                            missing, "MISSING_SLOT", request.intentPath(), request.invocationOrigin());
                }
                if ("CONFIRM_PENDING".equals(state)) {
                    return new AgentInvocationOutcome(result.taskId(), state, null, Map.of(),
                            List.of("confirmation"), "CONFIRMATION_REQUIRED",
                            request.intentPath(), request.invocationOrigin());
                }
                String reason = result.guardrail() != null && !result.guardrail().isPassed()
                        ? "GUARDRAIL_BLOCKED" : null;
                return new AgentInvocationOutcome(result.taskId(), state, null, Map.of(),
                        List.of(), reason, request.intentPath(), request.invocationOrigin());
            }
            return outcome(result.taskId(), result.result(), null, request.intentPath(),
                    request.invocationOrigin(), card.capabilityId());
        } finally {
            RequestContextHolder.clear();
        }
    }

    private static PrincipalState principalState(AgentInvocationRequest request) {
        if (request.principal() == null) {
            return PrincipalState.anonymous(channel(request));
        }
        return new PrincipalState(request.principal().subjectRef(), request.principal().verified(),
                request.principal().authLevel(), request.principal().channel());
    }

    private static InvocationLineage lineage(AgentInvocationRequest request) {
        return new InvocationLineage(request.rootTaskId(), request.parentTaskId(),
                request.sourceTaskId(), request.delegationPath(), request.deadline());
    }

    private com.huawei.finance.contracts.model.ContextLease compileLease(
            AgentInvocationRequest request) {
        Span span = start("agent.context.compile", request);
        try (Tracer.SpanInScope ignored = scope(span)) {
            return leases.compile(identity.id(), request.targetSessionId(), request.goal(),
                    mergedFacts(request), List.of());
        } catch (RuntimeException e) {
            if (span != null) span.error(e);
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    private AgentTaskOutcome executeTask(
            AgentInvocationRequest invocation, AgentTaskRequest task) {
        Span span = start("agent.task.orchestrate", invocation);
        try (Tracer.SpanInScope ignored = scope(span)) {
            AgentTaskOutcome outcome = tasks.execute(task);
            if (span != null && outcome != null && outcome.orchestrationState() != null) {
                span.tag("agent.task.state", outcome.orchestrationState());
            }
            return outcome;
        } catch (RuntimeException e) {
            if (span != null) span.error(e);
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    private Span start(String name, AgentInvocationRequest request) {
        if (tracer == null) return null;
        Span span = tracer.nextSpan().name(name)
                .tag("agent.source", value(request.sourceAgentId()))
                .tag("agent.target", value(request.targetAgentId()))
                .tag("agent.mode", request.mode().name())
                .tag("agent.intent_path", request.intentPath() == null
                        ? "TARGET_RECOGNIZES" : request.intentPath().name())
                .tag("agent.invocation_origin", request.invocationOrigin().name())
                .tag("agent.capability", value(request.capabilityId()));
        return span.start();
    }

    private Tracer.SpanInScope scope(Span span) {
        return span == null ? null : tracer.withSpan(span);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static AgentInvocationOutcome outcome(
            String taskId, TaskResult result, String reason, Enums.TaskSource intentPath,
            Enums.InvocationOrigin invocationOrigin, String targetCapabilityId) {
        if (result == null) {
            return new AgentInvocationOutcome(taskId, "PENDING", null, Map.of(), List.of(), reason,
                    intentPath, invocationOrigin);
        }
        Object rawMissing = result.resultPayload().get("missingSlots");
        List<String> missing = rawMissing instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        String resultReason = String.valueOf(result.resultPayload().getOrDefault("reasonCode", ""));
        Map<String, Object> facts = new java.util.LinkedHashMap<>(result.resultPayload());
        if (targetCapabilityId != null && !targetCapabilityId.isBlank()) {
            facts.put(TaskResultMetadata.TARGET_CAPABILITY_ID, targetCapabilityId);
        }
        return new AgentInvocationOutcome(taskId, result.status().name(), result,
                facts, missing, resultReason.isBlank() ? reason : resultReason,
                intentPath, invocationOrigin);
    }

    private static boolean present(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static String subject(AgentInvocationRequest request) {
        return request.principal() == null || request.principal().subjectRef() == null
                ? "" : request.principal().subjectRef();
    }

    private static String channel(AgentInvocationRequest request) {
        return request.principal() == null ? "A2A" : request.principal().channel();
    }

    private static Map<String, Object> mergedFacts(AgentInvocationRequest request) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>();
        request.confirmedFacts().forEach(merged::putAll);
        if (request.subtaskContext() != null) {
            merged.putAll(request.subtaskContext().confirmedInputs());
            request.subtaskContext().facts().forEach(fact -> merged.putAll(fact.value()));
        }
        return merged;
    }

    private static AgentInvocationOutcome withContextDelta(
            AgentInvocationRequest request, AgentInvocationOutcome outcome) {
        if (outcome == null || request.subtaskContext() == null) return outcome;
        Instant observedAt = Instant.now();
        List<ContextEvidence> upserts = outcome.facts().entrySet().stream()
                .map(entry -> new ContextEvidence(
                        "fact:" + request.targetAgentId() + ":" + entry.getKey(),
                        ContextEvidence.Kind.TOOL_FACT, Map.of(entry.getKey(), entry.getValue()),
                        request.targetAgentId(), outcome.taskId(), null, observedAt,
                        isSnapshot(entry.getKey()) ? observedAt.plusSeconds(30) : null,
                        ContextEvidence.Sensitivity.SENSITIVE))
                .toList();
        List<ContextDelta.PendingQuestion> pending = outcome.missingSlots().stream()
                .map(slot -> new ContextDelta.PendingQuestion(slot, List.of(), "MISSING"))
                .toList();
        ContextDelta delta = new ContextDelta(request.subtaskContext().baseStateVersion(),
                upserts, List.of(), pending, List.of());
        return new AgentInvocationOutcome(outcome.taskId(), outcome.state(), outcome.result(),
                outcome.facts(), outcome.missingSlots(), outcome.reasonCode(),
                outcome.intentPath(), outcome.invocationOrigin(), delta);
    }

    private static boolean isSnapshot(String key) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("balance") || normalized.contains("quote")
                || normalized.contains("rate");
    }
}
