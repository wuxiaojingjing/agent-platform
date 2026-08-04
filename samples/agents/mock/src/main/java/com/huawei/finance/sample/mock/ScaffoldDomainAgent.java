package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.Objects;
import java.util.Set;

/**
 * 科技域脚手架子 Agent：域身份已登记、本期无面客 TOOL。
 *
 * <p>{@code supports} 恒为 false，中控不会派单过来；若误派则明确失败，不回假业务数据。
 */
public final class ScaffoldDomainAgent implements TechDomainAgent {

    private final String techDomainCode;
    private final String agentId;

    public ScaffoldDomainAgent(String techDomainCode) {
        this.techDomainCode = Objects.requireNonNull(techDomainCode, "techDomainCode");
        this.agentId = "agent." + techDomainCode;
    }

    @Override
    public String techDomainCode() {
        return techDomainCode;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public boolean supports(String capabilityId) {
        return false;
    }

    @Override
    public Set<String> advertisedCapabilities() {
        return Set.of();
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        return MockAgents.failed(task, "DOMAIN_NOT_OPEN:" + techDomainCode);
    }
}
