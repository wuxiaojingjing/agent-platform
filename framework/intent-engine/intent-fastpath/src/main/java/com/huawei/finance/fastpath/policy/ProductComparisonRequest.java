package com.huawei.finance.fastpath.policy;

import com.huawei.finance.registry.asset.ProductCatalog;
import java.util.Objects;

/** Two catalog-grounded products submitted to the published comparison policy. */
public record ProductComparisonRequest(
        ProductCatalog.ProductEntity left,
        ProductCatalog.ProductEntity right) {

    public ProductComparisonRequest {
        Objects.requireNonNull(left, "left product is required");
        Objects.requireNonNull(right, "right product is required");
        if (left.entityId().equals(right.entityId())) {
            throw new IllegalArgumentException("comparison requires two distinct products");
        }
    }
}
