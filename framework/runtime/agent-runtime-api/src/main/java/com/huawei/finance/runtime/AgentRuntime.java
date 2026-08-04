package com.huawei.finance.runtime;

import com.huawei.finance.stability.Api;

/**
 * 同构 Agent 运行时门面（架构草案 v0.5 §3 / §17.3）。
 *
 * <p>文档中的「AgentNode.handle」在本仓库映射为本接口，避免与 A2A 协议 SPI
 * {@code com.huawei.finance.contracts.a2a.AgentNode}（{@code DelegationEnvelope}）撞名。
 */
@Api
public interface AgentRuntime {

    AgentResponse handle(AgentRequest request);
}
