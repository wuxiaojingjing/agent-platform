package com.huawei.finance.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingSystemsControllerTest {

    private final BankingSystemsController simulator = new BankingSystemsController();

    @Test
    void readContractsReturnDeterministicTypedFields() {
        assertThat(simulator.balances("opaque")).containsKey("cards");
        assertThat(simulator.transactions("opaque")).isNotEmpty();
        assertThat(simulator.bill("opaque", "opaque-card"))
                .containsKeys("billAmount", "dueDate").containsEntry("cardRef", "opaque-card");
        assertThat(simulator.holdings("opaque")).containsKeys("totalAsset", "profit");
        assertThat(simulator.fund())
                .containsKeys("productCode", "riskLevel", "returnRate")
                .containsEntry("name", "基金产品C");
        assertThat(simulator.insurance()).containsKeys("productCode", "riskLevel", "term");
    }

    @Test
    void repeatedSideEffectWithSameKeyReturnsOriginalResult() {
        Map<String, Object> first = simulator.transfer(Map.of(
                "idempotencyKey", "idem-1", "payee", "张三", "amount", "100"));
        Map<String, Object> replay = simulator.transfer(Map.of(
                "idempotencyKey", "idem-1", "payee", "李四", "amount", "999"));

        assertThat(replay).isEqualTo(first);
        assertThat(replay).containsEntry("payee", "张三").containsEntry("amount", "100");
    }

    @Test
    void missingRequiredSideEffectFieldIsRejected() {
        assertThatThrownBy(() -> simulator.transfer(Map.of(
                "idempotencyKey", "idem-2", "amount", "100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payee");
    }
}
