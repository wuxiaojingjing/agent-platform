package com.huawei.finance.runtime.invocation;

import com.huawei.finance.stability.Spi;

/** 目标 Agent 的结构化入站 Runtime；A2A Server 不得绕过该门面直调领域实现。 */
@Spi
public interface AgentInvocationRuntime {
    AgentInvocationOutcome invoke(AgentInvocationRequest request);
}
