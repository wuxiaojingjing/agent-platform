package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.TaskShape;
import com.huawei.finance.fastpath.arbitration.ArbitrationInput;
import com.huawei.finance.fastpath.arbitration.FailSafeGuard;
import com.huawei.finance.fastpath.arbitration.SlotGate;
import com.huawei.finance.fastpath.recall.HybridRecall;
import com.huawei.finance.fastpath.recall.MultiTaskDetector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FailSafeGuardTest {

    @Test
    @DisplayName("R1 REVIEW_ONLY 能力槽位齐全时，fail-safe 保留 Review 语义")
    void reviewOnlyCapabilityIsNotTightenedToExplicitConfirmation() {
        RecallResult.Candidate candidate = new RecallResult.Candidate(
                "cap.card.replace",
                Enums.CandidateType.TOOL,
                List.of("creditcard_service", "account"),
                RecallResult.Scores.zero(),
                List.of("utterance:我要换信用卡"),
                List.of("cardType"),
                RiskLevel.R1,
                Map.of());
        RecallResult recall = new RecallResult(
                new RecallResult.DomainRouting(Enums.RoutingMode.SINGLE, List.of(), false),
                List.of(candidate),
                List.of());
        ArbitrationInput input = new ArbitrationInput(
                "我要换信用卡",
                "MOBILE_BANK",
                "home",
                new HybridRecall.Output(
                        recall,
                        new MultiTaskDetector.Signal(false, false, false),
                        null,
                        Map.of("cap.card.replace", 0.96)),
                Map.of("cardType", "信用卡"),
                0);
        RouteDecision modelDecision = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .candidateIds(List.of("cap.card.replace"))
                .taskShape(TaskShape.SINGLE_ACTION)
                .confidence(0.96)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .modelVersion("test-arbitrator")
                .promptVersion("test-prompt")
                .shortCircuit(ShortCircuitLevel.L3_MODEL)
                .build();

        RouteDecision guarded = new FailSafeGuard(FastPathFixture.assets(), new SlotGate())
                .tighten(modelDecision, input)
                .orElseThrow();

        assertThat(guarded.decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(guarded.selectedCandidateId()).isEqualTo("cap.card.replace");
        assertThat(guarded.reasonCode()).isEqualTo(ReasonCode.REVIEW_REQUIRED);
    }
}
