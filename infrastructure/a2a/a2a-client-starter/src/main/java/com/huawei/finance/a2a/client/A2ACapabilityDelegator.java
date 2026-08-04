package com.huawei.finance.a2a.client;

import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.common.context.InvocationLineage;
import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.a2a.PrincipalContext;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.port.ContextStateVersionProvider;
import com.huawei.finance.context.ContextDeltaMerger;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.obs.AgentLogContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 在本地中控完成建档、护栏与幂等控制后，把任务交给独立 A2A Gateway。
 */
public final class A2ACapabilityDelegator implements CapabilityDelegator {

    private static final Logger log = LoggerFactory.getLogger(A2ACapabilityDelegator.class);

    private final DelegationClient client;
    private final AgentCardRegistry agents;
    private final AssetBundle assets;
    private final AgentIdentity source;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final ContextStateVersionProvider contextVersions;
    private final ContextDeltaMerger contextDeltas = new ContextDeltaMerger();

    public A2ACapabilityDelegator(DelegationClient client, AgentCardRegistry agents,
                                  AssetBundle assets, AgentIdentity source) {
        this(client, agents, assets, source, new SimpleMeterRegistry(), null,
                ContextStateVersionProvider.UNKNOWN);
    }

    public A2ACapabilityDelegator(DelegationClient client, AgentCardRegistry agents,
                                  AssetBundle assets, AgentIdentity source,
                                  MeterRegistry meterRegistry, Tracer tracer) {
        this(client, agents, assets, source, meterRegistry, tracer,
                ContextStateVersionProvider.UNKNOWN);
    }

    public A2ACapabilityDelegator(DelegationClient client, AgentCardRegistry agents,
                                  AssetBundle assets, AgentIdentity source,
                                  MeterRegistry meterRegistry, Tracer tracer,
                                  ContextStateVersionProvider contextVersions) {
        this.client = client;
        this.agents = agents;
        this.assets = assets;
        this.source = source;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.contextVersions = contextVersions == null
                ? ContextStateVersionProvider.UNKNOWN : contextVersions;
    }

    @Override
    public boolean handles(String capabilityId) {
        CapabilityCard card = assets.capability(capabilityId);
        return card != null && !candidates(card).isEmpty();
    }

    @Override
    public Optional<TaskResult> delegate(UnifiedTask task, CapabilityCard card) {
        List<String> candidates = candidates(card);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        RequestContext context = RequestContextHolder.get();
        String tenantId = context == null ? RequestContext.SPACE_UNSCOPED : context.spaceId();
        if (context != null) {
            context.recordGatewayRoundTrip("a2a:" + candidates.getFirst());
        }
        List<Map<String, Object>> confirmedFacts = task.confirmation().isEmpty()
                ? List.of() : List.of(task.confirmation());
        DelegationMode mode = card.type() == Enums.CapabilityType.AGENT
                ? DelegationMode.GOAL : DelegationMode.TASK;
        AgentCard targetCard = agents.find(candidates.getFirst()).orElse(null);
        if (targetCard != null
                && targetCard.contextContract() == AgentCard.ContextContract.STATELESS_READ_ONLY
                && (mode != DelegationMode.TASK
                    || card.riskLevel() != com.huawei.finance.contracts.model.RiskLevel.R0)) {
            return Optional.of(failed(task, Enums.FailureClass.FATAL,
                    "CONTEXT_CONTRACT_UNSUPPORTED",
                    "Target supports only stateless R0 TASK delegation"));
        }
        InvocationLineage lineage = context == null ? null : context.lineage();
        String rootTaskId = lineage == null || lineage.rootTaskId() == null
                ? task.taskId() : lineage.rootTaskId();
        List<String> delegationPath = lineage == null ? List.of() : lineage.delegationPath();
        java.time.Instant upstreamDeadline = earlier(task.deadline(),
                lineage == null ? null : lineage.deadline());

        long started = System.nanoTime();
        Span clientSpan = tracer == null ? null : tracer.nextSpan().name("agent.a2a.client")
                .tag("a2a.source.agent", source.id())
                .tag("a2a.target.agent", candidates.get(0))
                .tag("a2a.capability", task.capabilityId())
                .tag("a2a.mode", mode.name()).start();
        Span span = tracer == null ? null : tracer.nextSpan().name("agent.a2a.delegate")
                .tag("a2a.source.agent", source.id())
                .tag("a2a.target.agent", candidates.get(0))
                .tag("a2a.capability", task.capabilityId())
                .tag("a2a.mode", mode.name())
                .tag("a2a.depth", String.valueOf(delegationPath.size() + 1))
                .tag("agent.intent_path", task.source().name())
                .tag("agent.invocation_origin", task.invocationOrigin().name())
                .tag("agent.task.id", task.taskId()).start();

        DelegationReceipt receipt;
        try (AgentLogContext logContext = AgentLogContext.open(Map.of(
                "traceId", value(task.traceId()),
                "taskId", value(task.taskId()),
                "sourceAgent", value(source.id()),
                "targetAgent", value(candidates.getFirst())))) {
            try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
                receipt = client.delegate(new DelegationClient.DelegationRequest(
                        tenantId, source.id(), rootTaskId, task.taskId(), task.taskId(),
                        task.traceId(), mode,
                        mode == DelegationMode.TASK ? task.source() : null,
                        principalOf(context, task.taskId()),
                        task.goal(), task.capabilityId(),
                        task.parameters(), confirmedFacts, upstreamDeadline, card.timeoutMs(),
                        delegationPath, targetCard != null
                                && targetCard.contextContract() == AgentCard.ContextContract.STATELESS_READ_ONLY
                                ? null : task.subtaskContext()),
                        candidates);
            }
            receipt = mergeContextDelta(context, task, receipt);
            MDC.put("delegationId", receipt.delegationId());
            MDC.put("outcome", receipt.outcome().name());
            if (receipt.reasonCode() != null) {
                MDC.put("reasonCode", receipt.reasonCode());
            }
            recordA2a(context, task, candidates.getFirst(), mode, confirmedFacts.size(), receipt,
                    (System.nanoTime() - started) / 1_000_000L);
            log.info("A2A 委托完成 capability={} target={} outcome={} reason={}",
                    task.capabilityId(), candidates.getFirst(), receipt.outcome(), receipt.reasonCode());
        } catch (RuntimeException ex) {
            Enums.FailureClass failure = card.hasSideEffects()
                    ? Enums.FailureClass.PARTIAL : Enums.FailureClass.RETRYABLE;
            try (AgentLogContext logContext = AgentLogContext.open(Map.of(
                    "traceId", value(task.traceId()),
                    "taskId", value(task.taskId()),
                    "sourceAgent", value(source.id()),
                    "targetAgent", value(candidates.getFirst()),
                    "outcome", "ERROR",
                    "reasonCode", "A2A_GATEWAY_UNAVAILABLE"))) {
                log.error("A2A Gateway 调用失败 reasonCode=A2A_GATEWAY_UNAVAILABLE task={} capability={} "
                                + "target={} failureClass={} cause={}",
                        task.taskId(), task.capabilityId(), candidates, failure, ex.toString());
            }
            if (context != null) {
                context.recordModuleStep(new RuntimeModuleStep(
                        "a2a-client", "delegate", "CHILD",
                        Map.of("sourceAgent", source.id(), "targetAgent", candidates.getFirst(),
                                "capability", task.capabilityId(), "mode", "TASK",
                                "parameterKeys", task.parameters().keySet(),
                                "confirmedFactCount", confirmedFacts.size()),
                        Map.of("outcome", "ERROR", "reasonCode", "A2A_GATEWAY_UNAVAILABLE"),
                        "ERROR", (System.nanoTime() - started) / 1_000_000L));
            }
            finish(span, started, "client", "ERROR", "A2A_GATEWAY_UNAVAILABLE");
            finishAlias(clientSpan, "ERROR", "A2A_GATEWAY_UNAVAILABLE");
            return Optional.of(failed(task, failure, "A2A_GATEWAY_UNAVAILABLE", ex.getClass().getSimpleName()));
        }

        finish(span, started, "client", receipt.outcome().name(), receipt.reasonCode());
        finishAlias(clientSpan, receipt.outcome().name(), receipt.reasonCode());
        return Optional.of(toTaskResult(task, card, receipt));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    static PrincipalContext principalOf(RequestContext context, String sourceTaskId) {
        if (context == null) {
            return PrincipalContext.anonymous("UNKNOWN",
                    opaque("session", RequestContext.SPACE_UNSCOPED, sourceTaskId));
        }
        String sessionRef = opaque("session", context.spaceId(), context.sessionId());
        PrincipalState state = context.principal();
        if (state == null || !state.verified() || state.subjectRef() == null
                || state.subjectRef().isBlank()) {
            return PrincipalContext.anonymous(
                    state == null ? context.channel() : state.channel(), sessionRef);
        }
        String principalRef = context.lineage() == null
                ? opaque("principal", context.spaceId(), state.subjectRef())
                : state.subjectRef();
        return new PrincipalContext(
                principalRef, state.authLevel(), state.channel(), sessionRef);
    }

    private static java.time.Instant earlier(java.time.Instant left, java.time.Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static String opaque(String kind, String tenantId, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((kind + ':' + tenantId + ':' + value)
                    .getBytes(StandardCharsets.UTF_8));
            return kind + ':' + HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private void recordA2a(RequestContext context, UnifiedTask task, String targetAgent,
                           DelegationMode mode, int confirmedFactCount,
                           DelegationReceipt receipt, long durationMs) {
        if (context == null) {
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("delegationId", receipt.delegationId());
        output.put("outcome", receipt.outcome().name());
        output.put("reasonCode", value(receipt.reasonCode()));
        output.put("factKeys", receipt.facts().keySet());
        copySafe(receipt.facts(), output, "targetTaskId");
        copySafe(receipt.facts(), output, "intentPath");
        copySafe(receipt.facts(), output, "invocationOrigin");
        copySafe(receipt.facts(), output, "principalVerified");
        context.recordModuleStep(new RuntimeModuleStep(
                "a2a-client", "delegate", "CHILD",
                Map.of("sourceAgent", source.id(), "targetAgent", targetAgent,
                        "capability", task.capabilityId(), "mode",
                        mode.name(),
                        "intentPath", mode == DelegationMode.GOAL
                                ? "TARGET_RECOGNIZES" : task.source().name(),
                        "invocationOrigin", task.invocationOrigin().name(),
                        "parameterKeys", task.parameters().keySet(),
                        "confirmedFactCount", confirmedFactCount),
                output,
                receipt.outcome().name(), durationMs));
    }

    private static void copySafe(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private void finish(Span span, long started, String segment, String outcome, String reason) {
        meterRegistry.timer(AgentMetrics.A2A_SEGMENT_LATENCY,
                AgentMetrics.TAG_SEGMENT, segment,
                AgentMetrics.TAG_OUTCOME, outcome)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        if (reason != null) {
            meterRegistry.counter(AgentMetrics.A2A_FAILURE,
                    AgentMetrics.TAG_SEGMENT, segment,
                    AgentMetrics.TAG_REASON, reason,
                    AgentMetrics.TAG_OUTCOME, outcome).increment();
        }
        if (span != null) {
            span.tag("a2a.outcome", outcome);
            if (reason != null) {
                span.tag("a2a.reason_code", reason);
            }
            span.end();
        }
    }

    private static void finishAlias(Span span, String outcome, String reason) {
        if (span == null) return;
        span.tag("a2a.outcome", outcome);
        if (reason != null) span.tag("a2a.reason_code", reason);
        span.end();
    }

    private List<String> candidates(CapabilityCard card) {
        if (card == null) {
            return List.of();
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();

        agents.find(card.capabilityId()).map(AgentCard::agentId).ifPresent(resolved::add);
        if (card.parentCapabilityId() != null) {
            agents.find(card.parentCapabilityId()).map(AgentCard::agentId).ifPresent(resolved::add);
        }
        for (String domain : card.domains()) {
            agents.byTechDomain(domain).map(AgentCard::agentId).ifPresent(resolved::add);
        }
        resolved.remove(source.id());

        int limit = Math.min(resolved.size(), 2);
        return new ArrayList<>(resolved).subList(0, limit);
    }

    private static TaskResult toTaskResult(UnifiedTask task, CapabilityCard card,
                                           DelegationReceipt receipt) {
        Map<String, Object> payload = new LinkedHashMap<>(receipt.facts());
        payload.put("a2aDelegationId", receipt.delegationId());
        if (receipt.reasonCode() != null) {
            payload.put("reasonCode", receipt.reasonCode());
        }
        if (receipt.diagnostics() != null) {
            payload.put("reasonDetail", receipt.diagnostics());
        }
        if (!receipt.missingSlots().isEmpty()) {
            payload.put("missingSlots", receipt.missingSlots().stream()
                    .map(slot -> Map.<String, Object>of(
                            "slot", slot.slot(), "reasonCode", slot.reasonCode()))
                    .toList());
        }
        if (receipt.contextDelta() != null) {
            payload.put("contextDeltaBaseVersion", receipt.contextDelta().baseStateVersion());
            payload.put("contextDeltaRefs", receipt.contextDelta().upserts().stream()
                    .map(com.huawei.finance.contracts.model.ContextEvidence::ref).toList());
            payload.put("contextDeltaFacts", receipt.contextDelta().upserts());
        }

        return switch (receipt.outcome()) {
            case SUCCEEDED -> result(task, Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE, payload);
            case NEED_USER -> result(task, Enums.TaskStatus.NEED_USER, Enums.FailureClass.NEED_USER, payload);
            case PARTIAL -> result(task, Enums.TaskStatus.PARTIAL, Enums.FailureClass.PARTIAL, payload);
            case FATAL -> result(task, Enums.TaskStatus.FAILED,
                    remoteFailureClass(card, receipt.reasonCode()), payload);
            case NOT_MINE -> result(task, Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL, payload);
            case DOMAIN_NOT_OPEN -> result(task, Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL, payload);
        };
    }

    private static Enums.FailureClass remoteFailureClass(CapabilityCard card, String reasonCode) {
        if ("AGENT_UNREACHABLE".equals(reasonCode) || "AGENT_ENDPOINT_MISSING".equals(reasonCode)) {
            return card.hasSideEffects() ? Enums.FailureClass.PARTIAL : Enums.FailureClass.RETRYABLE;
        }
        return Enums.FailureClass.FATAL;
    }

    private static TaskResult failed(UnifiedTask task, Enums.FailureClass failure,
                                     String reasonCode, String detail) {
        return result(task,
                failure == Enums.FailureClass.PARTIAL ? Enums.TaskStatus.PARTIAL : Enums.TaskStatus.FAILED,
                failure, Map.of("reasonCode", reasonCode, "reasonDetail", detail));
    }

    private static TaskResult result(UnifiedTask task, Enums.TaskStatus status,
                                     Enums.FailureClass failure, Map<String, Object> payload) {
        return new TaskResult(task.taskId(), status, failure, payload,
                task.idempotencyKey(), task.guardrailCheck());
    }

    private DelegationReceipt mergeContextDelta(
            RequestContext context, UnifiedTask task, DelegationReceipt receipt) {
        if (task.subtaskContext() == null || receipt.contextDelta() == null) return receipt;
        long current = context == null ? -1
                : contextVersions.currentVersion(context.spaceId(), source.id(), context.sessionId());
        if (current < 0) current = task.subtaskContext().baseStateVersion();
        ContextDeltaMerger.MergeResult merge = contextDeltas.merge(
                current, task.subtaskContext(), receipt.contextDelta());
        meterRegistry.counter(AgentMetrics.CONTEXT_DELTA_MERGE,
                AgentMetrics.TAG_OUTCOME, merge.status().name(),
                AgentMetrics.TAG_REASON, merge.reasonCode() == null ? "NONE" : merge.reasonCode())
                .increment();
        if (!merge.applied()) {
            if ("CONTEXT_VERSION_CONFLICT".equals(merge.reasonCode())) {
                return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION,
                        receipt.delegationId(), DelegationOutcome.NEED_USER, Map.of(),
                        List.of(new DelegationReceipt.MissingSlot(
                                "contextReconfirmation", List.of(), merge.reasonCode())),
                        merge.reasonCode(), null, null);
            }
            return DelegationReceipt.fatal(receipt.delegationId(), merge.reasonCode(),
                    "ContextDelta rejected by parent CAS/scope gate");
        }
        return receipt;
    }
}
