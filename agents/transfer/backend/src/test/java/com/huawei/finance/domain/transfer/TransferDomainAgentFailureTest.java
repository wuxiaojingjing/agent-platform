package com.huawei.finance.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class TransferDomainAgentFailureTest {

    @Test
    void missingParametersNeedUserAndUnknownSideEffectIsPartial() {
        var unknown = new TransferDomainAgent(command -> { throw new ResourceAccessException("timeout"); });

        assertThat(unknown.execute(task(Map.of("principalRef", "opaque"))).status())
                .isEqualTo(Enums.TaskStatus.NEED_USER);
        var result = unknown.execute(task(Map.of(
                "principalRef", "opaque", "payee", "张三", "amount", "100")));
        assertThat(result.status()).isEqualTo(Enums.TaskStatus.PARTIAL);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.PARTIAL);
        assertThat(result.resultPayload()).containsEntry("reasonCode", "TRANSFER_RESULT_UNKNOWN");
    }

    @Test
    void delegationIdIsPassedToBackendAsIdempotencyKey() {
        AtomicReference<String> key = new AtomicReference<>();
        var agent = new TransferDomainAgent(command -> {
            key.set(command.idempotencyKey());
            return new TransferPort.TransferReceipt(command.payee(), command.amount(),
                    command.fromAccount(), "serial", "now");
        });
        agent.execute(task(Map.of("principalRef", "opaque", "payee", "张三", "amount", "100")));
        assertThat(key).hasValue("delegation");
    }

    private static UnifiedTask task(Map<String, Object> parameters) {
        return new UnifiedTask("task", "trace", Enums.TaskSource.FAST_PATH, "转账", "cap.transfer",
                parameters, RiskLevel.R2, Map.of("confirmedAt", "now"), GuardrailCheck.passed(),
                "delegation", List.of(), Instant.now().plusSeconds(10));
    }
}
