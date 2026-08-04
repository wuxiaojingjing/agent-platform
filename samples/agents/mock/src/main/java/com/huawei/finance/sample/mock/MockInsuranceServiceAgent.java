package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 保险服务域 Mock 子 Agent（场景 4 产品 B）。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至 {@code com.huawei.finance.domain.insurance.InsuranceDomainAgent}。
 */
public class MockInsuranceServiceAgent implements TechDomainAgent {

    private static final Set<String> SUPPORTED = Set.of("cap.insurance.product.query");

    @Override
    public String techDomainCode() {
        return "insurance_service";
    }

    @Override
    public String agentId() {
        return "agent.insurance_service";
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
            return MockAgents.missingIdempotency(task);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productCode", "B");
        row.put("name", "产品B");
        row.put("domain", "保险");
        row.put("riskLevel", "R2");
        // 保险侧故意不给「参考收益」可比字段——模板应显示「—」，不得编造
        row.put("returnRate", "—");
        row.put("term", "终身");
        return MockAgents.success(task, row);
    }
}
