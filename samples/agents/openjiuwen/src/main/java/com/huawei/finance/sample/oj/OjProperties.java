package com.huawei.finance.sample.oj;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 中控侧接入 OpenJiuwen Agent Server 的配置。 */
@ConfigurationProperties(prefix = "huawei.finance.sample.openjiuwen")
public class OjProperties {

    /**
     * 是否启用。默认关。
     *
     * <p>与 Mock 那个开关同理：没配就不该有这条链路。开着而后端不通时，
     * 每次执行都要等到超时才失败，而那个延迟会顶满整条面客链路的预算。
     */
    private boolean enabled = false;

    /**
     * 能力标识到 Agent Server 基址的路由表。
     *
     * <p>做成一张表而不是一个全局地址：领域 Agent 是按领域分进程部署的
     * （账户一个、支付一个），本来就不共用一个地址。key 用能力标识而不是领域名，
     * 是因为 {@code DomainAgent.supports} 判定的粒度就是能力——用领域名还得再维护
     * 一份「哪个能力属于哪个领域」的映射，而那份映射已经在能力卡里了。
     */
    private Map<String, String> endpoints = new LinkedHashMap<>();

    /**
     * 单次调用超时。
     *
     * <p>这不是唯一的超时闸门：中控的 {@code AgentInvoker} 按能力卡上的 {@code timeoutMs}
     * 另有一道，且以它为准。这一道存在的意义是别让 HTTP 连接在中控已经放弃之后还挂着，
     * 所以应当配得略小于能力卡上的值。
     */
    private int requestTimeoutMs = 5000;

    private int connectTimeoutMs = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, String> endpoints) {
        this.endpoints = endpoints;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}
