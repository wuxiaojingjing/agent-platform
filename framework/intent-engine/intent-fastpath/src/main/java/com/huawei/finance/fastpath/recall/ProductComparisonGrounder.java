package com.huawei.finance.fastpath.recall;

import com.huawei.finance.fastpath.policy.ProductComparisonRequest;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.ProductCatalog;
import java.util.List;
import java.util.Optional;

/** Grounds comparison mentions against the published catalog before policy evaluation. */
public class ProductComparisonGrounder {

    private final AssetBundle bundle;

    public ProductComparisonGrounder(AssetBundle bundle) {
        this.bundle = bundle;
    }

    public Grounding ground(String query) {
        if (!bundle.productComparisonPolicy().hasComparisonIntent(query)) {
            return new Grounding(Status.NOT_APPLICABLE, List.of(), null);
        }
        List<ProductCatalog.Match> matches = bundle.productCatalog().resolveMentions(query);
        if (matches.size() != 2
                || matches.stream().map(match -> match.entity().entityId()).distinct().count() != 2) {
            return new Grounding(Status.UNRESOLVED, matches, null);
        }
        return new Grounding(Status.RESOLVED, matches,
                new ProductComparisonRequest(matches.get(0).entity(), matches.get(1).entity()));
    }

    public enum Status { NOT_APPLICABLE, UNRESOLVED, RESOLVED }

    public record Grounding(Status status, List<ProductCatalog.Match> matches,
                            ProductComparisonRequest request) {
        public Grounding {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        public Optional<ProductComparisonRequest> resolvedRequest() {
            return Optional.ofNullable(request);
        }
    }
}
