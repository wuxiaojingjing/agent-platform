package com.huawei.finance.domain.loan;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.Map;
import java.util.Set;
public final class LoanDomainAgent implements TechDomainAgent {
    private static final Set<String> SUPPORTED = Set.of("cap.loan.product.query");
    private final LoanCatalogPort port;
    public LoanDomainAgent(LoanCatalogPort port) { this.port = port; }
    public String techDomainCode() { return "loan_service"; }
    public String agentId() { return "agent.loan_service"; }
    public boolean supports(String id) { return SUPPORTED.contains(id); }
    public Set<String> advertisedCapabilities() { return SUPPORTED; }
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) return out(task, Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL, Map.of("error","MISSING_IDEMPOTENCY_KEY"));
        try { var row=port.featuredProduct(); return out(task, Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE, Map.of(
                "productCode",row.productCode(),"name",row.name(),"domain",row.domain(),"riskLevel",row.riskLevel(),"returnRate",row.returnRate(),"term",row.term()));
        } catch (RuntimeException e) { return out(task, Enums.TaskStatus.FAILED, Enums.FailureClass.RETRYABLE, Map.of("reasonCode","LOAN_BACKEND_UNAVAILABLE")); }
    }
    private static TaskResult out(UnifiedTask task, Enums.TaskStatus s, Enums.FailureClass f, Map<String,Object> p) { return new TaskResult(task.taskId(),s,f,p,task.idempotencyKey(),task.guardrailCheck()); }
}
