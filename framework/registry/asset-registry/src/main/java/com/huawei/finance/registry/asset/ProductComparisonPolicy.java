package com.huawei.finance.registry.asset;

import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import java.util.List;
import java.util.Map;

/** Published business policy for deciding whether two grounded products may be compared. */
public class ProductComparisonPolicy {

    private String version = "disabled";
    private boolean requireSameProductType;
    private List<String> intentMarkers = List.of("对比", "比较");
    private Map<String, ProductGroup> groups = Map.of();
    private IncompatibleAction onIncompatible = new IncompatibleAction();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "" : version.trim();
    }

    public boolean isRequireSameProductType() {
        return requireSameProductType;
    }

    public void setRequireSameProductType(boolean requireSameProductType) {
        this.requireSameProductType = requireSameProductType;
    }

    public List<String> getIntentMarkers() {
        return intentMarkers;
    }

    public void setIntentMarkers(List<String> intentMarkers) {
        this.intentMarkers = intentMarkers == null ? List.of() : intentMarkers.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public Map<String, ProductGroup> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, ProductGroup> groups) {
        this.groups = groups == null ? Map.of() : Map.copyOf(groups);
    }

    public IncompatibleAction getOnIncompatible() {
        return onIncompatible;
    }

    public void setOnIncompatible(IncompatibleAction onIncompatible) {
        this.onIncompatible = onIncompatible == null ? new IncompatibleAction() : onIncompatible;
    }

    public boolean hasComparisonIntent(String query) {
        return query != null && intentMarkers.stream().anyMatch(query::contains);
    }

    public String groupDisplayName(String productType) {
        ProductGroup group = groups.get(productType);
        return group == null || group.displayName() == null || group.displayName().isBlank()
                ? productType : group.displayName();
    }

    public void validate(ProductCatalog catalog) {
        if (version.isBlank()) {
            throw new IllegalArgumentException("产品比较策略 version 不得为空");
        }
        if (requireSameProductType && intentMarkers.isEmpty()) {
            throw new IllegalArgumentException("产品比较策略开启后 intentMarkers 不得为空");
        }
        if (onIncompatible.decision() != Decision.CLARIFY) {
            throw new IllegalArgumentException("不可比产品当前只允许收敛为 CLARIFY");
        }
        if (onIncompatible.reasonCode() != ReasonCode.INCOMPARABLE_PRODUCT_TYPE) {
            throw new IllegalArgumentException("不可比产品必须使用 INCOMPARABLE_PRODUCT_TYPE 原因码");
        }
        if (blank(onIncompatible.templateKey()) || blank(onIncompatible.separateViewOptionTemplate())
                || !onIncompatible.separateViewOptionTemplate().contains("%s")) {
            throw new IllegalArgumentException("不可比产品模板和单独查看选项模板配置无效");
        }
        if (requireSameProductType) {
            for (ProductCatalog.ProductEntity product : catalog.getProducts()) {
                if (!groups.containsKey(product.productType())) {
                    throw new IllegalArgumentException("产品类型缺少比较分组配置：" + product.productType());
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ProductGroup(String displayName, List<String> dimensions) {
        public ProductGroup {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    public static class IncompatibleAction {
        private Decision decision = Decision.CLARIFY;
        private ReasonCode reasonCode = ReasonCode.INCOMPARABLE_PRODUCT_TYPE;
        private String templateKey = "tpl.product.compare.incomparable";
        private boolean allowSeparateView = true;
        private String separateViewOptionTemplate = "查看%s";

        public Decision getDecision() {
            return decision;
        }

        public void setDecision(Decision decision) {
            this.decision = decision;
        }

        public ReasonCode getReasonCode() {
            return reasonCode;
        }

        public void setReasonCode(ReasonCode reasonCode) {
            this.reasonCode = reasonCode;
        }

        public String getTemplateKey() {
            return templateKey;
        }

        public void setTemplateKey(String templateKey) {
            this.templateKey = templateKey;
        }

        public boolean isAllowSeparateView() {
            return allowSeparateView;
        }

        public void setAllowSeparateView(boolean allowSeparateView) {
            this.allowSeparateView = allowSeparateView;
        }

        public String getSeparateViewOptionTemplate() {
            return separateViewOptionTemplate;
        }

        public void setSeparateViewOptionTemplate(String separateViewOptionTemplate) {
            this.separateViewOptionTemplate = separateViewOptionTemplate;
        }

        public Decision decision() {
            return decision;
        }

        public ReasonCode reasonCode() {
            return reasonCode;
        }

        public String templateKey() {
            return templateKey;
        }

        public boolean allowSeparateView() {
            return allowSeparateView;
        }

        public String separateViewOptionTemplate() {
            return separateViewOptionTemplate;
        }
    }
}
