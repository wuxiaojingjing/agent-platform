package com.huawei.finance.runtime.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopActionPolicyGateTest {
    private final LoopActionPolicyGate gate = new LoopActionPolicyGate();

    @Test void reviewAndConfirmationNeverProceedBeforeAcceptance() {
        Run run = run();
        Action action = new Action(ActionType.CALL_CAPABILITY, "cap.x", Map.of(), Map.of(), "NEXT", "fp");
        assertThat(gate.check(run, action, card(ConfirmationPolicy.REVIEW_ONLY, RiskLevel.R1), false).verdict())
                .isEqualTo(Verdict.WAIT_REVIEW);
        assertThat(gate.check(run, action, card(ConfirmationPolicy.EXPLICIT, RiskLevel.R2), false).verdict())
                .isEqualTo(Verdict.WAIT_CONFIRMATION);
    }

    @Test void deadlineAndLoopAccessAreHardStops() {
        Run expired = new Run("t", "l", "a", "s", "r", "tr", "g", Status.RUNNING,
                0, 3, List.of("cap.x"), Map.of(), null, Instant.now().minusSeconds(1), 0, null, null);
        Action action = new Action(ActionType.CALL_CAPABILITY, "cap.x", Map.of(), Map.of(), "NEXT", "fp");
        assertThat(gate.check(expired, action, card(ConfirmationPolicy.NONE, RiskLevel.R0), true).reasonCode())
                .isEqualTo("LOOP_DEADLINE");
    }

    private static Run run() {
        return new Run("t", "l", "a", "s", "r", "tr", "g", Status.RUNNING,
                0, 3, List.of("cap.x"), Map.of(), null, Instant.now().plusSeconds(10), 0, null, null);
    }
    private static CapabilityCard card(ConfirmationPolicy confirmation, RiskLevel risk) {
        return new CapabilityCard("cap.x", "X", Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.x", List.of("x"), "", List.of(), Map.of(), Map.of(), List.of(),
                risk == RiskLevel.R0 ? List.of() : List.of("WRITE"), risk, 1000,
                risk == RiskLevel.R2 ? Enums.Idempotency.REQUIRED : Enums.Idempotency.SUPPORTED,
                "o", "1", Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(), Enums.GuardrailOwner.DOMAIN, false,
                confirmation, LoopAccess.DEFAULT);
    }
}
