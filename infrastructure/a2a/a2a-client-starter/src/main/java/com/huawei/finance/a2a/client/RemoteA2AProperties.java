package com.huawei.finance.a2a.client;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "huawei.finance.agent.a2a.remote")
public class RemoteA2AProperties {

    private URI gatewayUrl = URI.create("http://localhost:8086");

    public URI getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(URI gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }
}
