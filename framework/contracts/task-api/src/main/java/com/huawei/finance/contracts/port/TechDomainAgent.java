package com.huawei.finance.contracts.port;

/**
 * 按科技域分治的领域 Agent（附录 F）。
 *
 * <p>比 {@link DomainAgent} 多一层域身份，便于清单展示与「一域一进程」替换时逐个摘除 Mock。
 * 路由真值仍是 {@link #supports(String)}，不是域码。
 */
@com.huawei.finance.stability.Spi
public interface TechDomainAgent extends DomainAgent {

    /** 规范科技域码，如 {@code fund_service}。 */
    String techDomainCode();

    /** 能力注册中心里的 AGENT 父卡 id，如 {@code agent.fund_service}。 */
    String agentId();
}
