package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 财富聚合服务域 Mock 子 Agent（持仓总览）。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至 {@code com.huawei.finance.domain.wealth.WealthDomainAgent}。
 */
public class MockWealthAggregateAgent implements TechDomainAgent {

    private static final Set<String> SUPPORTED = Set.of("cap.wealth.holding.query");

    @Override
    public String techDomainCode() {
        return "wealth_aggregate";
    }

    @Override
    public String agentId() {
        return "agent.wealth_aggregate";
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalAsset", "86,300.00");
        payload.put("profit", "+2,145.30");
        return MockAgents.success(task, payload);
    }
}
