package com.huawei.finance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.runtime.extension.AgentRuntimeExtensionException;
import com.huawei.finance.runtime.extension.ResponseEnricher;
import com.huawei.finance.runtime.extension.ResponseEnrichmentContext;
import com.huawei.finance.runtime.extension.RuntimeExtensionFailurePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseEnricherChainTest {

    @Test
    void enrichersRunInOrderAndSeePreviousRenderingChanges() {
        List<String> calls = new ArrayList<>();
        ResponseEnricher first = enricher("first", RuntimeExtensionFailurePolicy.FAIL_CLOSED,
                context -> {
                    calls.add("first");
                    Map<String, Object> slots = new LinkedHashMap<>(context.renderSlots());
                    slots.put("first", true);
                    return slots;
                });
        ResponseEnricher second = enricher("second", RuntimeExtensionFailurePolicy.FAIL_CLOSED,
                context -> {
                    calls.add("second");
                    assertThat(context.renderSlots()).containsEntry("first", true);
                    Map<String, Object> slots = new LinkedHashMap<>(context.renderSlots());
                    slots.put("second", true);
                    return slots;
                });

        Map<String, Object> result = new ResponseEnricherChain(List.of(first, second))
                .enrich(context(Map.of("base", true)));

        assertThat(calls).containsExactly("first", "second");
        assertThat(result).containsOnlyKeys("base", "first", "second");
    }

    @Test
    void fallbackDefaultDiscardsEarlierRenderingChanges() {
        ResponseEnricher first = enricher("first", RuntimeExtensionFailurePolicy.FAIL_CLOSED,
                context -> Map.of("changed", true));
        ResponseEnricher failed = enricher("failed",
                RuntimeExtensionFailurePolicy.FALLBACK_DEFAULT,
                context -> {
                    throw new IllegalStateException("timeout");
                });

        Map<String, Object> result = new ResponseEnricherChain(List.of(first, failed))
                .enrich(context(Map.of("base", true)));

        assertThat(result).containsExactlyEntriesOf(Map.of("base", true));
    }

    @Test
    void failClosedExtensionStopsRendering() {
        ResponseEnricher failed = enricher("compliance-display",
                RuntimeExtensionFailurePolicy.FAIL_CLOSED,
                context -> {
                    throw new IllegalArgumentException("required disclosure unavailable");
                });

        assertThatThrownBy(() -> new ResponseEnricherChain(List.of(failed))
                .enrich(context(Map.of())))
                .isInstanceOf(AgentRuntimeExtensionException.class)
                .hasMessageContaining("compliance-display");
    }

    private static ResponseEnrichmentContext context(Map<String, Object> slots) {
        RequestContext request = new RequestContext(
                "trace-1", "session-1", "user-1", "space-1", "agent.payment",
                "mobile", "home", "LOGGED_IN", false);
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();
        return new ResponseEnrichmentContext(
                request, decision, null, "task-1", null, GuardrailCheck.passed(), slots);
    }

    private static ResponseEnricher enricher(
            String id, RuntimeExtensionFailurePolicy policy, Enrichment operation) {
        return new ResponseEnricher() {
            @Override
            public Map<String, Object> enrich(ResponseEnrichmentContext context) {
                return operation.apply(context);
            }

            @Override
            public String extensionId() {
                return id;
            }

            @Override
            public RuntimeExtensionFailurePolicy failurePolicy() {
                return policy;
            }
        };
    }

    @FunctionalInterface
    private interface Enrichment {
        Map<String, Object> apply(ResponseEnrichmentContext context);
    }
}
