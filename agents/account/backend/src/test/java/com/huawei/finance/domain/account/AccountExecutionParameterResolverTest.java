package com.huawei.finance.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.ContextLease;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountExecutionParameterResolverTest {

    @Test
    void calculatesAmountFromTheAuthoritativeBalanceOnlyAtExecution() {
        AccountPort port = new AccountPort() {
            @Override public AccountView accountView(String principalRef) {
                return new AccountView(List.of(
                        new CardView(1, "尾号 8821 借记卡", "1000"),
                        new CardView(2, "尾号 3344 借记卡", "6000")));
            }
            @Override public List<TransactionView> transactions(String principalRef) { return List.of(); }
        };
        var resolver = new AccountExecutionParameterResolver(port);
        Map<String, Object> preview = Map.of(
                "payee", "张三", "fromAccount", "尾号 3344 借记卡",
                AccountReferenceResolver.AMOUNT_BASIS_SLOT, AccountReferenceResolver.REQUERY_THEN_HALF,
                AccountReferenceResolver.ACCOUNT_ORDINAL_SLOT, 2);

        var result = resolver.resolve("cap.transfer", preview, lease(), "opaque-principal");

        assertThat(result.resolved()).isTrue();
        assertThat(result.parameters()).containsEntry("amount", "3000");
        assertThat(result.evidenceRef()).startsWith("requery:cap.account.balance.query");
    }

    @Test
    void failedRequeryStopsResolution() {
        AccountPort broken = new AccountPort() {
            @Override public AccountView accountView(String principalRef) { throw new IllegalStateException(); }
            @Override public List<TransactionView> transactions(String principalRef) { return List.of(); }
        };
        Map<String, Object> parameters = Map.of(
                AccountReferenceResolver.AMOUNT_BASIS_SLOT, AccountReferenceResolver.REQUERY_THEN_HALF);

        var result = new AccountExecutionParameterResolver(broken)
                .resolve("cap.transfer", parameters, lease(), "opaque-principal");

        assertThat(result.resolved()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("BALANCE_REQUERY_FAILED");
    }

    private static ContextLease lease() {
        return new ContextLease("lease", "s", "goal", Map.of(), List.of(), List.of(),
                100, 0, List.of(), true, 1, Instant.now().plusSeconds(30));
    }
}
