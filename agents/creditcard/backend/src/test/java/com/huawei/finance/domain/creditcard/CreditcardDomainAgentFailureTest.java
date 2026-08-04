package com.huawei.finance.domain.creditcard;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class CreditcardDomainAgentFailureTest {

    private static final CreditcardPort UNAVAILABLE = new CreditcardPort() {
        public BillView bill(String principalRef, String cardRef) {
            throw new ResourceAccessException("timeout");
        }
        public OperationReceipt repay(RepayCommand command) { throw new ResourceAccessException("timeout"); }
        public OperationReceipt replace(ReplaceCommand command) { throw new ResourceAccessException("timeout"); }
    };

    @Test
    void readFailureIsRetryableButSideEffectFailureIsPartial() {
        var agent = new CreditcardDomainAgent(UNAVAILABLE);
        var read = agent.execute(task("cap.creditcard.bill.query",
                Map.of("principalRef", "opaque", "cardRef", "opaque-card"), RiskLevel.R0));
        var write = agent.execute(task("cap.creditcard.repay",
                Map.of("principalRef", "opaque", "amount", "100"), RiskLevel.R2));

        assertThat(read.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(read.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(write.status()).isEqualTo(Enums.TaskStatus.PARTIAL);
        assertThat(write.failureClass()).isEqualTo(Enums.FailureClass.PARTIAL);
    }

    private static UnifiedTask task(String capability, Map<String, Object> parameters, RiskLevel risk) {
        return new UnifiedTask("task", "trace", Enums.TaskSource.FAST_PATH, capability, capability,
                parameters, risk, risk == RiskLevel.R0 ? Map.of() : Map.of("confirmedAt", "now"),
                GuardrailCheck.passed(), "delegation", List.of(), Instant.now().plusSeconds(10));
    }
}
