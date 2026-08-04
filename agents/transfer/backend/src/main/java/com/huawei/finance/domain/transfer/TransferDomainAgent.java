package com.huawei.finance.domain.transfer;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 转账服务域叶子（阶段 3b；自 MockTransferAgent 上收）。 */
public class TransferDomainAgent implements TechDomainAgent {

    private final TransferPort port;
    public TransferDomainAgent(TransferPort port) { this.port = port; }

    private static final Set<String> SUPPORTED = Set.of("cap.transfer");

    @Override
    public String techDomainCode() {
        return "transfer";
    }

    @Override
    public String agentId() {
        return "agent.transfer";
    }

    @Override
    public boolean supports(String capabilityId) {
        return capabilityId != null && SUPPORTED.contains(capabilityId);
    }

    @Override
    public Set<String> advertisedCapabilities() {
        return SUPPORTED;
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) {
            return DomainAgents.missingIdempotency(task);
        }
        String payee = text(task, "payee");
        String amount = text(task, "amount");
        String principal = text(task, "principalRef");
        if (payee.isBlank() || amount.isBlank() || principal.isBlank()) {
            return new TaskResult(task.taskId(), com.huawei.finance.contracts.model.Enums.TaskStatus.NEED_USER,
                    com.huawei.finance.contracts.model.Enums.FailureClass.NEED_USER,
                    Map.of("missingSlots", java.util.stream.Stream.of("principalRef", "payee", "amount")
                            .filter(k -> text(task, k).isBlank()).toList()),
                    task.idempotencyKey(), task.guardrailCheck());
        }
        try {
            var receipt = port.submit(new TransferPort.TransferCommand(principal, payee, amount,
                    text(task, "fromAccount"), task.idempotencyKey()));
            return DomainAgents.success(task, Map.of(
                    "payee", receipt.payee(), "amount", receipt.amount(),
                    "fromAccount", receipt.fromAccount(), "serialNo", receipt.serialNo(),
                    "finishedAt", receipt.finishedAt()));
        } catch (org.springframework.web.client.ResourceAccessException
                 | org.springframework.web.client.RestClientResponseException e) {
            return new TaskResult(task.taskId(), com.huawei.finance.contracts.model.Enums.TaskStatus.PARTIAL,
                    com.huawei.finance.contracts.model.Enums.FailureClass.PARTIAL,
                    Map.of("reasonCode", "TRANSFER_RESULT_UNKNOWN"),
                    task.idempotencyKey(), task.guardrailCheck());
        }
    }

    private static String text(UnifiedTask task, String key) {
        Object value = task.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
