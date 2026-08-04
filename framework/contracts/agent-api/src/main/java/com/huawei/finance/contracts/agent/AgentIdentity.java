package com.huawei.finance.contracts.agent;

import com.huawei.finance.stability.Api;

/** 当前进程承载的 Agent 身份。目录名和拓扑角色都不能替代显式 agentId。 */
@Api
public record AgentIdentity(String id) {

    public AgentIdentity {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空；请在 agent.yaml 或运行配置中显式声明");
        }
        id = id.trim();
    }
}
