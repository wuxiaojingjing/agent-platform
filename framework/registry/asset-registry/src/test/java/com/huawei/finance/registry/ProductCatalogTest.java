package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.registry.asset.ProductCatalog;
import com.huawei.finance.registry.asset.ProductCatalog.ProductEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductCatalogTest {

    @Test
    void resolvesPublishedProductsInMentionOrder() {
        ProductCatalog catalog = catalog();

        assertThat(catalog.resolveMentions("对比一下产品B和A产品"))
                .extracting(match -> match.entity().entityId())
                .containsExactly("product-b", "product-a");
    }

    @Test
    void unknownProductsAreNotGuessed() {
        assertThat(catalog().resolveMentions("对比一下产品X和产品Y")).isEmpty();
    }

    @Test
    void longerOverlappingProductNameWinsWithoutHidingALaterShortProduct() {
        ProductCatalog catalog = catalog();
        catalog.setProducts(List.of(
                product("product-a", "产品A", List.of("A产品")),
                new ProductEntity("product-b", "产品B", List.of("B产品"), "WEALTH",
                        "cap.wealth-product.product.query", "agent.wealth_product"),
                new ProductEntity("product-b2", "产品B2", List.of("B2产品"), "WEALTH",
                        "cap.wealth-product.product-b2.query", "agent.wealth_product")));

        assertThat(catalog.resolveMentions("对比产品B2和产品B"))
                .extracting(match -> match.entity().entityId())
                .containsExactly("product-b2", "product-b");
        assertThat(catalog.resolveMentions("查询产品B2"))
                .extracting(match -> match.entity().entityId())
                .containsExactly("product-b2");
    }

    @Test
    void duplicateEntityIdsAndCrossEntityAliasesAreRejected() {
        ProductEntity first = product("same-id", "产品A", List.of("A产品"));
        ProductEntity duplicateId = product("same-id", "产品B", List.of("B产品"));
        ProductEntity duplicateAlias = product("product-b", "产品B", List.of("A产品"));

        ProductCatalog duplicateIds = new ProductCatalog();
        assertThatThrownBy(() -> duplicateIds.setProducts(List.of(first, duplicateId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID 重复");

        ProductCatalog aliases = new ProductCatalog();
        assertThatThrownBy(() -> aliases.setProducts(List.of(first, duplicateAlias)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("别名映射冲突");
    }

    private static ProductCatalog catalog() {
        ProductCatalog catalog = new ProductCatalog();
        catalog.setProducts(List.of(
                product("product-a", "产品A", List.of("A产品")),
                new ProductEntity("product-b", "产品B", List.of("B产品"), "WEALTH",
                        "cap.wealth-product.product.query", "agent.wealth_product")));
        return catalog;
    }

    private static ProductEntity product(String id, String name, List<String> aliases) {
        return new ProductEntity(id, name, aliases, "INSURANCE",
                "cap.insurance.product.query", "agent.insurance_service");
    }
}
