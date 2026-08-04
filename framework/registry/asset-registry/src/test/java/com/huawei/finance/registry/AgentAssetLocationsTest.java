package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentAssetLocationsTest {

    @AfterEach
    void clearConfiguredAssetsPath() {
        System.clearProperty(AgentAssetLocations.ASSETS_PATH_PROPERTY);
    }

    @Test
    void explicitAssetsPathDoesNotDependOnAgentHome(@TempDir Path temp) throws Exception {
        Path assets = Files.createDirectories(temp.resolve("shared-assets"));
        Files.writeString(assets.resolve("manifest.yaml"), "version: test\n");
        System.setProperty(AgentAssetLocations.ASSETS_PATH_PROPERTY, assets.toString());

        assertThat(AgentAssetLocations.findAssets()).contains(assets.toAbsolutePath().normalize());
    }
}
