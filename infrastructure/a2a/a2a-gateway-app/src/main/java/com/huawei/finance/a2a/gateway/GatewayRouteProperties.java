package com.huawei.finance.a2a.gateway;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 静态路由只用于本地和迁移兜底；生产由发现适配器提供同一张 agentId 到地址的表。 */
@ConfigurationProperties(prefix = "huawei.finance.agent.a2a.gateway")
public class GatewayRouteProperties {

    private Map<String, URI> targets = new LinkedHashMap<>();

    public Map<String, URI> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, URI> targets) {
        this.targets = targets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(targets);
    }
}
