package com.huawei.finance.product.mobilebanking.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.intent.PathSummary;
import com.huawei.finance.common.context.RuntimeModuleStep;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecentDecisionsTest {

    @Test
    void keepsSessionIdentityForConversationGrouping() {
        var recent = new RecentDecisions();
        var decision = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();

        RuntimeModuleStep step = new RuntimeModuleStep("context-engine", "compile-lease", "CONTEXT",
                Map.of("ownerAgent", "agent.mobile-banking-assistant"),
                Map.of("trustworthy", true), "OK", 2L);
        recent.record("trace-1", "session-1", "查余额", decision, null,
                "tpl.answer", false, List.of(), 12L, PathSummary.empty(), List.of(), List.of(step));

        assertThat(recent.snapshot()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.sessionId()).isEqualTo("session-1");
                    assertThat(entry.moduleSteps()).containsExactly(step);
                    Map<String, Object> jsonView = new ObjectMapper().findAndRegisterModules()
                            .convertValue(entry, new TypeReference<>() { });
                    assertThat(jsonView.get("at"))
                            .isInstanceOf(String.class)
                            .asString()
                            .contains("T")
                            .endsWith("Z");
                });
    }
}
