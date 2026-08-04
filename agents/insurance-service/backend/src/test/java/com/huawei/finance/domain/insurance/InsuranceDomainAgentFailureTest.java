package com.huawei.finance.domain.insurance;

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

class InsuranceDomainAgentFailureTest {
    @Test
    void unavailableBackendDoesNotFallBackToFixedProduct() {
        var agent = new InsuranceDomainAgent(principal -> { throw new ResourceAccessException("503"); });
        var result = agent.execute(task());
        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(result.resultPayload()).containsEntry("reasonCode", "INSURANCE_BACKEND_UNAVAILABLE");
    }
    private static UnifiedTask task() {
        return new UnifiedTask("task", "trace", Enums.TaskSource.FAST_PATH, "保险", "cap.insurance.product.query",
                Map.of("principalRef", "opaque"), RiskLevel.R0, Map.of(), GuardrailCheck.passed(),
                "delegation", List.of(), Instant.now().plusSeconds(10));
    }
}
