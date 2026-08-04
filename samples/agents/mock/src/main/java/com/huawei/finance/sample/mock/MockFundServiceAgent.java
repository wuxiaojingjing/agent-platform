package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基金服务域 Mock 子 Agent（场景 4 产品 A）。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至 {@code com.huawei.finance.domain.fund.FundDomainAgent}。
 */
public class MockFundServiceAgent implements TechDomainAgent {

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
            return MockAgents.missingIdempotency(task);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productCode", "A");
        row.put("name", "产品A");
        row.put("domain", "基金");
        row.put("riskLevel", "R3");
        row.put("returnRate", "3.2%");
        row.put("term", "开放式");
        return MockAgents.success(task, row);
    }
}
