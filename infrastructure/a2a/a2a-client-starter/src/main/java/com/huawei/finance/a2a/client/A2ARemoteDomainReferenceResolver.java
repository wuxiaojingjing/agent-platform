package com.huawei.finance.a2a.client;

import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.port.DomainReferenceResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves semantic references on the Agent that produced the policy-validated context fact. */
final class A2ARemoteDomainReferenceResolver implements DomainReferenceResolver {

    private final A2AReferenceResolutionClient client;
    private final AgentCardRegistry agents;

    A2ARemoteDomainReferenceResolver(A2AReferenceResolutionClient client,
                                     AgentCardRegistry agents) {
        this.client = client;
        this.agents = agents;
    }

    @Override
    public String techDomainCode() {
        return "remote-context-source";
    }

    @Override
    public Map<String, Object> resolve(Map<String, Object> slots, ContextLease lease, String query) {
        return slots == null ? Map.of() : Map.copyOf(slots);
    }

    @Override
    public Map<String, Object> resolve(Map<String, Object> slots, ContextLease lease, String query,
                                       IntentContext intentContext, ContextualQuery contextualQuery,
                                       CapabilityCard selectedCapability) {
        Map<String, Object> unchanged = new LinkedHashMap<>(slots == null ? Map.of() : slots);
        if (intentContext == null || contextualQuery == null || selectedCapability == null
                || contextualQuery.slotUpdates().isEmpty()
                || unchanged.containsKey(ContextResolutionMarkers.RESOLVER_AGENT_ID)) {
            return unchanged;
        }
        List<String> refs = contextualQuery.resolutions().stream()
                .filter(item -> "ORDINAL_REFERENCE".equals(item.resolutionType())
                        || "REQUERY_THEN_HALF".equals(item.resolutionType()))
                .map(ContextualQuery.Resolution::contextRef)
                .filter(contextualQuery.usedContextRefs()::contains)
                .distinct().toList();
        List<String> sourceAgents = intentContext.evidence().stream()
                .filter(item -> refs.contains(item.ref()))
                .map(item -> item.sourceAgentId())
                .filter(value -> value != null && !value.isBlank())
                .distinct().toList();
        if (sourceAgents.size() != 1) return unchanged;

        String sourceAgent = sourceAgents.getFirst();
        var sourceCard = agents.find(sourceAgent).orElse(null);
        if (sourceCard == null || sourceCard.techDomainCode() == null) return unchanged;
        if (!selectedCapability.hasSideEffects()
                && selectedCapability.domains().contains(sourceCard.techDomainCode())) {
            return unchanged;
        }

        String resolverCapability = "cap." + sourceCard.techDomainCode() + ".reference.resolve";
        var outcome = client.resolve(sourceAgent, resolverCapability,
                contextualQuery.slotUpdates());
        if (!outcome.resolved()) {
            List<String> missing = selectedCapability.requiredSlots().stream()
                    .filter(slot -> unchanged.get(slot) == null
                            || String.valueOf(unchanged.get(slot)).isBlank())
                    .toList();
            unchanged.put(ContextResolutionMarkers.FAILURE_REASON, outcome.reasonCode());
            unchanged.put(ContextResolutionMarkers.FAILURE_MISSING_SLOTS, missing);
            return Map.copyOf(unchanged);
        }

        unchanged.putAll(outcome.slots());
        if (outcome.refreshAtExecution()) {
            unchanged.put(ContextResolutionMarkers.RESOLVER_AGENT_ID, sourceAgent);
            unchanged.put(ContextResolutionMarkers.RESOLVER_CAPABILITY_ID, resolverCapability);
            unchanged.put(ContextResolutionMarkers.RESOLUTION_INPUT_KEYS, outcome.inputKeys());
            unchanged.put(ContextResolutionMarkers.REFRESH_AT_EXECUTION, true);
        }
        return Map.copyOf(unchanged);
    }
}
