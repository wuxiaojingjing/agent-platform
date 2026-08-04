package com.huawei.finance.domain.account;

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

class AccountDomainAgentFailureTest {

    @Test
    void missingPrincipalNeedsUserAndBackendTimeoutIsRetryable() {
        AccountPort unavailable = new AccountPort() {
            public AccountView accountView(String principalRef) { throw new ResourceAccessException("timeout"); }
            public List<TransactionView> transactions(String principalRef) { throw new ResourceAccessException("timeout"); }
        };
        var agent = new AccountDomainAgent(unavailable);

        var missing = agent.execute(task("cap.account.balance.query", Map.of()));
        var failed = agent.execute(task("cap.account.balance.query", Map.of("principalRef", "opaque")));

        assertThat(missing.status()).isEqualTo(Enums.TaskStatus.NEED_USER);
        assertThat(failed.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(failed.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(failed.resultPayload()).containsEntry("reasonCode", "ACCOUNT_BACKEND_UNAVAILABLE");
    }

    private static UnifiedTask task(String capability, Map<String, Object> parameters) {
        return new UnifiedTask("task", "trace", Enums.TaskSource.FAST_PATH, capability, capability,
                parameters, RiskLevel.R0, Map.of(), GuardrailCheck.passed(), "delegation",
                List.of(), Instant.now().plusSeconds(10));
    }
}
