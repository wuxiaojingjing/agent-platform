package com.huawei.finance.fastpath.policy;

import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.ProductCatalog;
import com.huawei.finance.registry.asset.ProductComparisonPolicy;
import java.util.List;
import java.util.Map;

/** Enforces configured product comparability after entity grounding and before plan/Loop creation. */
public class ProductComparisonPolicyGate {

    private final AssetBundle bundle;

    public ProductComparisonPolicyGate(AssetBundle bundle) {
        this.bundle = bundle;
    }

    public Outcome evaluate(ProductComparisonRequest request) {
        ProductComparisonPolicy policy = bundle.productComparisonPolicy();
        ProductCatalog.ProductEntity left = request.left();
        ProductCatalog.ProductEntity right = request.right();
        boolean comparable = !policy.isRequireSameProductType()
                || left.productType().equals(right.productType());
        return new Outcome(comparable ? Status.COMPARABLE : Status.INCOMPARABLE,
                left, right, policy.groupDisplayName(left.productType()),
                policy.groupDisplayName(right.productType()));
    }

    public Map<String, Object> presentationSlots(Outcome outcome) {
        if (outcome == null || outcome.left() == null || outcome.right() == null) {
            return Map.of();
        }
        ProductComparisonPolicy.IncompatibleAction action =
                bundle.productComparisonPolicy().getOnIncompatible();
        List<String> options = action.allowSeparateView()
                ? List.of(action.separateViewOptionTemplate().formatted(outcome.left().displayName()),
                        action.separateViewOptionTemplate().formatted(outcome.right().displayName()))
                : List.of();
        return Map.of(
                "leftName", outcome.left().displayName(),
                "leftType", outcome.leftGroup(),
                "rightName", outcome.right().displayName(),
                "rightType", outcome.rightGroup(),
                "options", options);
    }

    public enum Status { COMPARABLE, INCOMPARABLE }

    public record Outcome(Status status, ProductCatalog.ProductEntity left,
                          ProductCatalog.ProductEntity right,
                          String leftGroup, String rightGroup) {
        public boolean comparable() {
            return status == Status.COMPARABLE;
        }

        public boolean incompatible() {
            return status == Status.INCOMPARABLE;
        }
    }
}
