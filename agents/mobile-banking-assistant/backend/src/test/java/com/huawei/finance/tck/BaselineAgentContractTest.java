package com.huawei.finance.tck;

import com.huawei.finance.domain.transfer.TransferDomainAgent;
import com.huawei.finance.contracts.port.DomainAgent;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;

/** 基线转账域叶子跑一遍 TCK。理由同 {@link BaselineGuardrailContractTest}。 */
@DisplayName("基线转账 DomainAgent 通过 TCK")
class BaselineAgentContractTest extends DomainAgentContract {

    @Override
    protected DomainAgent agent() {
        return new TransferDomainAgent(command ->
                new com.huawei.finance.domain.transfer.TransferPort.TransferReceipt(
                        command.payee(), command.amount(), command.fromAccount(), "TR-TEST", "now"));
    }

    @Override
    protected String capabilityId() {
        return "cap.transfer";
    }

    @Override
    protected Map<String, Object> validParameters() {
        return Map.of("principalRef", "principal:test", "payee", "张三", "amount", "100");
    }
}
