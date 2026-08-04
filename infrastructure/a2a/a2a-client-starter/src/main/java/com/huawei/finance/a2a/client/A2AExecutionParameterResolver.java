package com.huawei.finance.a2a.client;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.port.ExecutionParameterResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Refreshes context-derived values through the same authoritative Agent before side effects. */
final class A2AExecutionParameterResolver implements ExecutionParameterResolver {

    private final A2AReferenceResolutionClient client;

    A2AExecutionParameterResolver(A2AReferenceResolutionClient client) {
        this.client = client;
    }

    @Override
    public Resolution resolve(String capabilityId, Map<String, Object> parameters,
                              ContextLease lease, String subjectRef) {
        if (!Boolean.TRUE.equals(parameters.get(ContextResolutionMarkers.REFRESH_AT_EXECUTION))) {
            return Resolution.unchanged(parameters);
        }
        String agentId = text(parameters.get(ContextResolutionMarkers.RESOLVER_AGENT_ID));
        String resolverCapability = text(parameters.get(
                ContextResolutionMarkers.RESOLVER_CAPABILITY_ID));
        Object rawKeys = parameters.get(ContextResolutionMarkers.RESOLUTION_INPUT_KEYS);
        if (agentId == null || resolverCapability == null || !(rawKeys instanceof List<?> keys)) {
            return Resolution.failed(parameters, "REFERENCE_REFRESH_MARKERS_INVALID");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (Object rawKey : keys) {
            String key = text(rawKey);
            if (key != null && parameters.get(key) != null) {
                input.put(key, parameters.get(key));
            }
        }
        var outcome = client.resolveForExecution(agentId, resolverCapability, input);
        if (!outcome.resolved()) {
            return Resolution.failed(parameters, outcome.reasonCode());
        }
        Map<String, Object> refreshed = new LinkedHashMap<>(parameters);
        refreshed.putAll(outcome.slots());
        return new Resolution(true, Map.copyOf(refreshed), null,
                "a2a-reference-refresh:" + agentId);
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return String.valueOf(value);
    }
}
