package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.ProductComparisonPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductComparisonPolicyTest {

    @Test
    void loadsPublishedPolicyTogetherWithProductFacts() {
        AssetBundle bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());

        assertThat(bundle.productComparisonPolicy().getVersion()).isEqualTo("product-comparison-v1");
        assertThat(bundle.productComparisonPolicy().isRequireSameProductType()).isTrue();
        assertThat(bundle.productComparisonPolicy().getGroups()).containsKeys("INSURANCE", "WEALTH");
        assertThat(bundle.productComparisonPolicy().getOnIncompatible().templateKey())
                .isEqualTo("tpl.product.compare.incomparable");
    }

    @Test
    void rejectsAnEnabledPolicyWhenCatalogTypesAreNotConfigured() {
        ProductComparisonPolicy policy = new ProductComparisonPolicy();
        policy.setVersion("test-v1");
        policy.setRequireSameProductType(true);
        policy.setGroups(Map.of());

        AssetBundle bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        assertThatThrownBy(() -> policy.validate(bundle.productCatalog()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少比较分组配置");
    }
}
