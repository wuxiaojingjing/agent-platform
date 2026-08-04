package com.huawei.finance.registry.asset;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "huawei.finance.agent.assets")
public class AssetRegistryProperties {

    private String path;

    public String getPath() {
        return path == null || path.isBlank() ? AgentAssetLocations.requireAssets().toString() : path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
