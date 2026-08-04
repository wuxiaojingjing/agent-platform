package com.huawei.finance.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountDomainReferenceCapabilityTest {

    @Test
    void contextResolutionReturnsTheReferenceAndBasisWithoutCalculatingAnAmount() {
        AccountDomainAgent agent = new AccountDomainAgent(port(List.of(
                new AccountPort.CardView(1, "card-a", "12000"),
                new AccountPort.CardView(2, "card-b", "8000"))));

        TaskResult result = agent.execute(task(Map.of(
                "principalRef", "opaque-principal",
                "accountOrdinal", 2,
                "amountBasis", "REQUERY_THEN_HALF",
                ContextResolutionMarkers.RESOLUTION_MODE, ContextResolutionMarkers.CONTEXT_ONLY)));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
        assertThat(result.resultPayload()).containsEntry("refreshAtExecution", true);
        assertThat(result.resultPayload().get(ContextResolutionMarkers.RESOLVED_SLOTS))
                .isEqualTo(Map.of("accountOrdinal", 2, "fromAccount", "card-b",
                        "amountBasis", "REQUERY_THEN_HALF"));
    }

    @Test
    void executionResolutionCalculatesTheAmountFromTheAuthoritativeView() {
        AccountDomainAgent agent = new AccountDomainAgent(port(List.of(
                new AccountPort.CardView(1, "card-a", "12000"),
                new AccountPort.CardView(2, "card-b", "8000"))));

        TaskResult result = agent.execute(task(Map.of(
                "principalRef", "opaque-principal",
                "accountOrdinal", 2,
                "amountBasis", "REQUERY_THEN_HALF",
                ContextResolutionMarkers.RESOLUTION_MODE, ContextResolutionMarkers.EXECUTION)));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
        assertThat(result.resultPayload().get(ContextResolutionMarkers.RESOLVED_SLOTS))
                .isEqualTo(Map.of("accountOrdinal", 2, "fromAccount", "card-b",
                        "amountBasis", "REQUERY_THEN_HALF", "amount", "4000"));
    }

    @Test
    void rejectsOutOfRangeOrdinalInsteadOfSelectingAnotherAccount() {
        AccountDomainAgent agent = new AccountDomainAgent(port(List.of(
                new AccountPort.CardView(1, "card-a", "12000"))));

        TaskResult result = agent.execute(task(Map.of(
                "principalRef", "opaque-principal", "accountOrdinal", 4,
                ContextResolutionMarkers.RESOLUTION_MODE, ContextResolutionMarkers.CONTEXT_ONLY)));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(result.resultPayload()).containsEntry("reasonCode", "ACCOUNT_REFERENCE_STALE");
    }

    private static UnifiedTask task(Map<String, Object> parameters) {
        return new UnifiedTask("task", "trace", Enums.TaskSource.FAST_PATH,
                Enums.InvocationOrigin.A2A, "internal-reference-resolution",
                AccountDomainAgent.REFERENCE_RESOLUTION_CAPABILITY,
                parameters, com.huawei.finance.contracts.model.RiskLevel.R0, Map.of(),
                GuardrailCheck.passed(), "idem", List.of(), Instant.now().plusSeconds(5));
    }

    private static AccountPort port(List<AccountPort.CardView> cards) {
        return new AccountPort() {
            @Override public AccountView accountView(String principalRef) {
                return new AccountView(cards);
            }
            @Override public List<TransactionView> transactions(String principalRef) {
                return List.of();
            }
        };
    }
}
