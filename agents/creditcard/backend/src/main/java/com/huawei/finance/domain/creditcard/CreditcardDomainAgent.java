package com.huawei.finance.domain.creditcard;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 信用卡服务域叶子（阶段 3b；自 MockCreditCardAgent 上收）。 */
public class CreditcardDomainAgent implements TechDomainAgent {

    private final CreditcardPort port;
    public CreditcardDomainAgent(CreditcardPort port) { this.port = port; }

    private static final Set<String> SUPPORTED = Set.of(
            "cap.creditcard.bill.query",
            "cap.creditcard.repay",
            "cap.card.replace",
            "workflow.creditcard.replace");

    @Override
    public String techDomainCode() {
        return "creditcard_service";
    }

    @Override
    public String agentId() {
        return "agent.creditcard";
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
        String principal = text(task, "principalRef");
        if (principal.isBlank()) return need(task, "principalRef");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            switch (task.capabilityId()) {
                case "cap.creditcard.bill.query" -> {
                    String cardRef = text(task, "cardRef");
                    if (cardRef.isBlank()) return need(task, "cardRef");
                    var bill = port.bill(principal, cardRef);
                    payload.put("billAmount", bill.billAmount());
                    payload.put("dueDate", bill.dueDate());
                    payload.put("cardRef", cardRef);
                }
                case "cap.creditcard.repay" -> {
                    String amount = text(task, "amount");
                    if (amount.isBlank()) return need(task, "amount");
                    var receipt = port.repay(new CreditcardPort.RepayCommand(
                            principal, amount, task.idempotencyKey()));
                    payload.put("amount", receipt.amount());
                    payload.put("serialNo", receipt.serialNo());
                }
                default -> {
                    String cardType = text(task, "cardType");
                    if (cardType.isBlank()) return need(task, "cardType");
                    var receipt = port.replace(new CreditcardPort.ReplaceCommand(
                            principal, cardType, task.idempotencyKey()));
                    payload.put("cardTypeName", receipt.cardTypeName());
                    payload.put("applicationNo", receipt.serialNo());
                }
            }
            return DomainAgents.success(task, payload);
        } catch (org.springframework.web.client.ResourceAccessException
                 | org.springframework.web.client.RestClientResponseException e) {
            boolean read = "cap.creditcard.bill.query".equals(task.capabilityId());
            var failure = read ? com.huawei.finance.contracts.model.Enums.FailureClass.RETRYABLE
                    : com.huawei.finance.contracts.model.Enums.FailureClass.PARTIAL;
            var status = read ? com.huawei.finance.contracts.model.Enums.TaskStatus.FAILED
                    : com.huawei.finance.contracts.model.Enums.TaskStatus.PARTIAL;
            return new TaskResult(task.taskId(), status, failure,
                    Map.of("reasonCode", read ? "CREDITCARD_BACKEND_UNAVAILABLE" : "CREDITCARD_RESULT_UNKNOWN"),
                    task.idempotencyKey(), task.guardrailCheck());
        }
    }

    private static String text(UnifiedTask task, String key) {
        Object value = task.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }
    private static TaskResult need(UnifiedTask task, String slot) {
        return new TaskResult(task.taskId(), com.huawei.finance.contracts.model.Enums.TaskStatus.NEED_USER,
                com.huawei.finance.contracts.model.Enums.FailureClass.NEED_USER,
                Map.of("missingSlots", java.util.List.of(slot)), task.idempotencyKey(), task.guardrailCheck());
    }
}
