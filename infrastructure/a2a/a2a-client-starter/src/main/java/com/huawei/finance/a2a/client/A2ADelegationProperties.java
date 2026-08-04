package com.huawei.finance.a2a.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Runtime 通过独立 A2A Gateway 委托能力的开关。 */
@ConfigurationProperties(prefix = "huawei.finance.agent.a2a.delegation")
public class A2ADelegationProperties {

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
