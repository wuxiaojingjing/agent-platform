package com.huawei.finance.domain.fund;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 基金服务域叶子（阶段 3b）。 */
public class FundDomainAgent implements TechDomainAgent {
    private final FundProductPort port;
    public FundDomainAgent(FundProductPort port) { this.port = port; }

    private static final Set<String> SUPPORTED = Set.of("cap.fund.product.query");

    @Override
    public String techDomainCode() {
        return "fund_service";
    }

    @Override
    public String agentId() {
        return "agent.fund_service";
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
        String principal = String.valueOf(task.parameters().getOrDefault("principalRef", ""));
        if (principal.isBlank()) return needPrincipal(task);
        try {
            var row = port.product(principal);
            return DomainAgents.success(task, Map.of("productCode", row.productCode(), "name", row.name(),
                    "domain", row.domain(), "riskLevel", row.riskLevel(),
                    "returnRate", row.returnRate(), "term", row.term()));
        } catch (org.springframework.web.client.ResourceAccessException
                 | org.springframework.web.client.RestClientResponseException e) {
            return failed(task, "FUND_BACKEND_UNAVAILABLE");
        }
    }
    private static TaskResult needPrincipal(UnifiedTask task) {
        return new TaskResult(task.taskId(), com.huawei.finance.contracts.model.Enums.TaskStatus.NEED_USER,
                com.huawei.finance.contracts.model.Enums.FailureClass.NEED_USER,
                Map.of("missingSlots", java.util.List.of("principalRef")), task.idempotencyKey(), task.guardrailCheck());
    }
    private static TaskResult failed(UnifiedTask task, String reason) {
        return new TaskResult(task.taskId(), com.huawei.finance.contracts.model.Enums.TaskStatus.FAILED,
                com.huawei.finance.contracts.model.Enums.FailureClass.RETRYABLE,
                Map.of("reasonCode", reason), task.idempotencyKey(), task.guardrailCheck());
    }
}
