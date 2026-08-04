package com.huawei.finance.registry.asset;

import com.huawei.finance.contracts.port.AssetCatalog;
import com.huawei.finance.contracts.validation.ContractValidator;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AssetRegistryProperties.class)
public class AssetRegistryAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public ContractValidator contractValidator() { return new ContractValidator(); }

    @Bean @ConditionalOnMissingBean
    public AssetLoader assetLoader(ContractValidator validator) { return new AssetLoader(validator); }

    @Bean @ConditionalOnMissingBean
    public AssetStore assetStore(AssetLoader loader, AssetRegistryProperties properties) {
        return new AssetStore(loader, Path.of(properties.getPath()));
    }

    @Bean @ConditionalOnMissingBean
    public AssetBundle assetBundle(AssetStore store) { return store.current(); }

    @Bean @ConditionalOnMissingBean
    public AssetCatalog assetCatalog(AssetBundle bundle) { return new BundleAssetCatalog(bundle); }
}
