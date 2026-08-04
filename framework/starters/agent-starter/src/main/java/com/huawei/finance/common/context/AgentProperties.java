package com.huawei.finance.common.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本进程所属 Agent 身份（架构草案阶段 1）。
 *
 * <p>由服务端配置，不采信客户端。单 Agent 时代默认 {@link RequestContext#AGENT_ENTRY}。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent")
public class AgentProperties {

    /** 逻辑 agentId，参与缓存键 / Redis / 库表作用域。 */
    private String id;

    public String getId() {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("缺少 huawei.finance.agent.id；每个 Agent 必须显式声明身份");
        }
        return id.trim();
    }

    public void setId(String id) {
        this.id = id;
    }
}
