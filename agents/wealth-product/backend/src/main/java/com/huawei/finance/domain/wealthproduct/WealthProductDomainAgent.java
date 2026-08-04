package com.huawei.finance.domain.wealthproduct;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.Map;
import java.util.Set;
public final class WealthProductDomainAgent implements TechDomainAgent {
    private static final Set<String> SUPPORTED=Set.of(
            "cap.wealth-product.product.query", "cap.wealth-product.product-b2.query");
    private final WealthProductPort port;
    public WealthProductDomainAgent(WealthProductPort port){this.port=port;}
    public String techDomainCode(){return "wealth_product";} public String agentId(){return "agent.wealth_product";}
    public boolean supports(String id){return SUPPORTED.contains(id);} public Set<String> advertisedCapabilities(){return SUPPORTED;}
    public TaskResult execute(UnifiedTask task){ if(!task.executable()) return out(task,Enums.TaskStatus.FAILED,Enums.FailureClass.FATAL,Map.of("error","MISSING_IDEMPOTENCY_KEY"));
        try{var r=port.product(task.capabilityId()); return out(task,Enums.TaskStatus.SUCCESS,Enums.FailureClass.NONE,Map.of("productCode",r.productCode(),"name",r.name(),"domain",r.domain(),"riskLevel",r.riskLevel(),"returnRate",r.returnRate(),"term",r.term()));}
        catch(RuntimeException e){return out(task,Enums.TaskStatus.FAILED,Enums.FailureClass.RETRYABLE,Map.of("reasonCode","WEALTH_PRODUCT_BACKEND_UNAVAILABLE"));}}
    private static TaskResult out(UnifiedTask t,Enums.TaskStatus s,Enums.FailureClass f,Map<String,Object> p){return new TaskResult(t.taskId(),s,f,p,t.idempotencyKey(),t.guardrailCheck());}
}
