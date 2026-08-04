package com.huawei.finance.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 中控配置。 */
@ConfigurationProperties(prefix = "huawei.finance.agent.orchestrator")
public class OrchestratorProperties {

    /**
     * 领域 Agent 调用的主控侧超时上限（毫秒）。
     *
     * <p>能力卡上的 {@code timeoutMs} 只能比它更小。这个数不是性能调优参数，是**用户能等多久**：
     * 超过它，无论下游在做什么，主控都要给用户一个交代。外部同类系统一次派发干等了
     * 74 秒（§2.7.4），那种体验不是慢，是没人管。
     *
     * <p>10 秒是 A 线的经验值，正式值应随 M3 压测一起定，并与渠道侧的网关超时对齐——
     * 设得比渠道网关还大，等于这道上限永远轮不到生效。
     */
    private int agentTimeoutCeilingMs = 10_000;

    /** 执行领域调用的线程池上限。超时要能被外部观测到，就必须有一个不被下游占满的调度侧。 */
    private int agentPoolSize = 32;

    public int getAgentTimeoutCeilingMs() {
        return agentTimeoutCeilingMs;
    }

    public void setAgentTimeoutCeilingMs(int agentTimeoutCeilingMs) {
        this.agentTimeoutCeilingMs = agentTimeoutCeilingMs;
    }

    public int getAgentPoolSize() {
        return agentPoolSize;
    }

    public void setAgentPoolSize(int agentPoolSize) {
        this.agentPoolSize = agentPoolSize;
    }
}
