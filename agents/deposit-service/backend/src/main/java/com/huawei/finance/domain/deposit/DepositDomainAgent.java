package com.huawei.finance.domain.deposit;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.Map;
import java.util.Set;

public final class DepositDomainAgent implements TechDomainAgent {
    private static final Set<String> SUPPORTED = Set.of("cap.deposit.product.query");
    private final DepositCatalogPort port;
    public DepositDomainAgent(DepositCatalogPort port) { this.port = port; }
    public String techDomainCode() { return "deposit_service"; }
    public String agentId() { return "agent.deposit_service"; }
    public boolean supports(String capabilityId) { return SUPPORTED.contains(capabilityId); }
    public Set<String> advertisedCapabilities() { return SUPPORTED; }
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) return result(task, Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL,
                Map.of("error", "MISSING_IDEMPOTENCY_KEY"));
        try {
            var row = port.featuredProduct();
            return result(task, Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE, Map.of(
                    "productCode", row.productCode(), "name", row.name(), "domain", row.domain(),
                    "riskLevel", row.riskLevel(), "returnRate", row.returnRate(), "term", row.term()));
        } catch (RuntimeException error) {
            return result(task, Enums.TaskStatus.FAILED, Enums.FailureClass.RETRYABLE,
                    Map.of("reasonCode", "DEPOSIT_BACKEND_UNAVAILABLE"));
        }
    }
    private static TaskResult result(UnifiedTask task, Enums.TaskStatus status,
                                     Enums.FailureClass failure, Map<String, Object> payload) {
        return new TaskResult(task.taskId(), status, failure, payload, task.idempotencyKey(), task.guardrailCheck());
    }
}
