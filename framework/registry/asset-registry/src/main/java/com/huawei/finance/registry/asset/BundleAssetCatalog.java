package com.huawei.finance.registry.asset;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.port.AssetCatalog;
import java.util.List;
import java.util.Optional;

/** 把 {@link AssetBundle} 适配为 {@link AssetCatalog}。 */
public final class BundleAssetCatalog implements AssetCatalog {

    private final AssetBundle bundle;

    public BundleAssetCatalog(AssetBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public String assetVersion() {
        return bundle.assetVersion();
    }

    @Override
    public List<CapabilityCard> capabilities() {
        return bundle.capabilities();
    }

    @Override
    public Optional<CapabilityCard> findCapability(String capabilityId) {
        return Optional.ofNullable(bundle.capability(capabilityId));
    }
}
