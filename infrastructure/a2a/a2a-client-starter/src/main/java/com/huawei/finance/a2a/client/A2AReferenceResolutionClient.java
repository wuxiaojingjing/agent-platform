package com.huawei.finance.a2a.client;

import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.common.context.InvocationLineage;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.registry.asset.AssetBundle;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executes a policy-selected internal reference resolver on the authoritative source Agent. */
final class A2AReferenceResolutionClient {

    private final DelegationClient delegations;
    private final AgentCardRegistry agents;
    private final AssetBundle assets;
    private final AgentIdentity source;
    private final Tracer tracer;

    A2AReferenceResolutionClient(DelegationClient delegations, AgentCardRegistry agents,
                                 AssetBundle assets, AgentIdentity source) {
        this(delegations, agents, assets, source, null);
    }

    A2AReferenceResolutionClient(DelegationClient delegations, AgentCardRegistry agents,
                                 AssetBundle assets, AgentIdentity source, Tracer tracer) {
        this.delegations = delegations;
        this.agents = agents;
        this.assets = assets;
        this.source = source;
        this.tracer = tracer;
    }

    Outcome resolve(String targetAgentId, String capabilityId, Map<String, Object> proposed) {
        return resolve(targetAgentId, capabilityId, proposed, ContextResolutionMarkers.CONTEXT_ONLY);
    }

    Outcome resolveForExecution(String targetAgentId, String capabilityId,
                                Map<String, Object> proposed) {
        return resolve(targetAgentId, capabilityId, proposed, ContextResolutionMarkers.EXECUTION);
    }

    private Outcome resolve(String targetAgentId, String capabilityId, Map<String, Object> proposed,
                            String resolutionMode) {
        RequestContext context = RequestContextHolder.get();
        CapabilityCard resolver = assets.capability(capabilityId);
        AgentCard target = agents.find(targetAgentId).orElse(null);
        if (context == null || resolver == null || target == null || !target.deliverable()
                || source.id().equals(targetAgentId)
                || resolver.riskLevel() != RiskLevel.R0 || resolver.hasSideEffects()
                || Boolean.TRUE.equals(resolver.entryVisible())
                || !targetAgentId.equals(resolver.parentCapabilityId())) {
            return Outcome.failed("REFERENCE_RESOLVER_NOT_ALLOWED");
        }

        Set<String> inputKeys = schemaPropertyKeys(resolver.inputSchema());
        Map<String, Object> parameters = select(proposed, inputKeys);
        if (inputKeys.contains(ContextResolutionMarkers.RESOLUTION_MODE)) {
            parameters.put(ContextResolutionMarkers.RESOLUTION_MODE, resolutionMode);
        }
        if (parameters.isEmpty()) return Outcome.failed("REFERENCE_INPUT_EMPTY");

        String sourceTaskId = "reference:" + UUID.randomUUID();
        InvocationLineage lineage = context.lineage();
        String rootTaskId = lineage == null || lineage.rootTaskId() == null
                ? sourceTaskId : lineage.rootTaskId();
        List<String> path = lineage == null ? List.of() : lineage.delegationPath();
        var request = new DelegationClient.DelegationRequest(
                context.spaceId(), source.id(), rootTaskId,
                lineage == null ? rootTaskId : lineage.parentTaskId(), sourceTaskId,
                context.traceId(), DelegationMode.TASK, Enums.TaskSource.FAST_PATH,
                A2ACapabilityDelegator.principalOf(context, sourceTaskId), null,
                capabilityId, parameters, List.of(),
                lineage == null ? null : lineage.deadline(), resolver.timeoutMs(), path);
        var receipt = delegate(request, targetAgentId, capabilityId);
        if (receipt.outcome() != DelegationOutcome.SUCCEEDED) {
            return Outcome.failed(receipt.reasonCode() == null
                    ? "REFERENCE_RESOLUTION_FAILED" : receipt.reasonCode());
        }
        Object raw = receipt.facts().get(ContextResolutionMarkers.RESOLVED_SLOTS);
        if (!(raw instanceof Map<?, ?> values)) {
            return Outcome.failed("REFERENCE_OUTPUT_MISSING");
        }
        Set<String> outputKeys = nestedResolvedSlotKeys(resolver.outputSchema());
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!outputKeys.contains(key) || entry.getValue() == null) {
                return Outcome.failed("REFERENCE_OUTPUT_OUT_OF_SCHEMA");
            }
            resolved.put(key, entry.getValue());
        }
        if (ContextResolutionMarkers.CONTEXT_ONLY.equals(resolutionMode)) {
            resolved.remove(com.huawei.finance.contracts.model.SlotNames.AMOUNT);
        }
        if (resolved.isEmpty()) return Outcome.failed("REFERENCE_OUTPUT_EMPTY");
        boolean refresh = Boolean.TRUE.equals(receipt.facts().get("refreshAtExecution"));
        return new Outcome(true, Map.copyOf(resolved), refresh, null,
                parameters.keySet().stream()
                        .filter(key -> !ContextResolutionMarkers.RESOLUTION_MODE.equals(key))
                        .toList());
    }

    private com.huawei.finance.contracts.a2a.DelegationReceipt delegate(
            DelegationClient.DelegationRequest request, String targetAgentId,
            String capabilityId) {
        Span clientSpan = tracer == null ? null : tracer.nextSpan().name("agent.a2a.client")
                .tag("a2a.source.agent", source.id())
                .tag("a2a.target.agent", targetAgentId)
                .tag("a2a.capability", capabilityId)
                .tag("a2a.mode", DelegationMode.TASK.name())
                .tag("agent.intent_path", Enums.TaskSource.FAST_PATH.name())
                .tag("agent.invocation_origin", "CONTEXT_REFERENCE_RESOLUTION")
                .start();
        try (Tracer.SpanInScope ignored = clientSpan == null ? null : tracer.withSpan(clientSpan)) {
            Span delegateSpan = tracer == null ? null : tracer.nextSpan().name("agent.a2a.delegate")
                    .tag("a2a.source.agent", source.id())
                    .tag("a2a.target.agent", targetAgentId)
                    .tag("a2a.capability", capabilityId)
                    .tag("a2a.mode", DelegationMode.TASK.name())
                    .tag("agent.intent_path", Enums.TaskSource.FAST_PATH.name())
                    .tag("agent.invocation_origin", "CONTEXT_REFERENCE_RESOLUTION")
                    .start();
            try (Tracer.SpanInScope delegateScope = delegateSpan == null
                    ? null : tracer.withSpan(delegateSpan)) {
                var receipt = delegations.delegate(request, List.of(targetAgentId));
                finish(delegateSpan, receipt.outcome().name(), receipt.reasonCode());
                finish(clientSpan, receipt.outcome().name(), receipt.reasonCode());
                return receipt;
            } catch (RuntimeException exception) {
                finish(delegateSpan, "ERROR", "A2A_REFERENCE_RESOLUTION_FAILED");
                finish(clientSpan, "ERROR", "A2A_REFERENCE_RESOLUTION_FAILED");
                throw exception;
            }
        }
    }

    private static void finish(Span span, String outcome, String reason) {
        if (span == null) return;
        span.tag("a2a.outcome", outcome);
        if (reason != null) span.tag("a2a.reason_code", reason);
        span.end();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> schemaPropertyKeys(Map<String, Object> schema) {
        Object properties = schema == null ? null : schema.get("properties");
        if (!(properties instanceof Map<?, ?> map)) return Set.of();
        return map.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> nestedResolvedSlotKeys(Map<String, Object> schema) {
        Object properties = schema == null ? null : schema.get("properties");
        if (!(properties instanceof Map<?, ?> outer)) return Set.of();
        Object resolved = outer.get(ContextResolutionMarkers.RESOLVED_SLOTS);
        if (!(resolved instanceof Map<?, ?> resolvedSchema)) return Set.of();
        Object nested = resolvedSchema.get("properties");
        if (!(nested instanceof Map<?, ?> map)) return Set.of();
        return map.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, Object> select(Map<String, Object> source, Set<String> allowed) {
        Map<String, Object> selected = new LinkedHashMap<>();
        if (source == null) return selected;
        for (String key : allowed) {
            Object value = source.get(key);
            if (value != null) selected.put(key, value);
        }
        return selected;
    }

    record Outcome(boolean resolved, Map<String, Object> slots, boolean refreshAtExecution,
                   String reasonCode, List<String> inputKeys) {
        static Outcome failed(String reason) {
            return new Outcome(false, Map.of(), false, reason, List.of());
        }
    }
}
