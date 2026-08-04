package com.huawei.finance.fastpath.recall;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.intent.ComparePlans;
import com.huawei.finance.fastpath.policy.ProductComparisonPolicyGate;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.Optional;

/**
 * 跨域产品对比的规则拆解（场景 4）。
 *
 * <p>实体归属只来自已发布产品目录。模型负责识别比较任务；本类提供可复核的实体与能力证据，
 * 不根据产品名称、字母或能力 ID 猜领域。
 */
public class ComparePlanBuilder {

    private final AssetBundle bundle;
    private final ProductComparisonGrounder grounder;
    private final ProductComparisonPolicyGate policyGate;

    public ComparePlanBuilder(AssetBundle bundle) {
        this.bundle = bundle;
        this.grounder = new ProductComparisonGrounder(bundle);
        this.policyGate = new ProductComparisonPolicyGate(bundle);
    }

    /**
     * @return 两件只读产品查询都认得出时给出计划，否则 empty（调用方退回手递）
     */
    public Optional<IntentPlan> plan(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        ProductComparisonPolicyGate.Outcome policy = grounder.ground(normalizedQuery)
                .resolvedRequest()
                .map(policyGate::evaluate)
                .orElse(null);
        if (policy == null) return Optional.empty();
        if (!policy.comparable()) {
            return Optional.empty();
        }
        List<com.huawei.finance.registry.asset.ProductCatalog.ProductEntity> products =
                List.of(policy.left(), policy.right());
        if (products.stream().anyMatch(product ->
                bundle.capability(product.queryCapabilityId()) == null)) {
            return Optional.empty();
        }

        List<SubIntent> items = new java.util.ArrayList<>();
        for (int index = 0; index < products.size(); index++) {
            var entity = products.get(index);
            String capabilityId = entity.queryCapabilityId();
            items.add(new SubIntent(index, entity.displayName(), capabilityId,
                    "查询" + entity.displayName(), Enums.IntentRelation.PARALLEL, null,
                    PlanResolution.locked(capabilityId, ComparePlans.EVIDENCE_PREFIX
                            + entity.entityId() + ":" + entity.ownerAgentId())));
        }
        return Optional.of(new IntentPlan(normalizedQuery, items, IntentPlan.Source.RULE));
    }

    /** 是否为已解析的跨域对比计划（两件都是产品查询卡）。 */
    public static boolean isComparePlan(IntentPlan plan) {
        return ComparePlans.isComparePlan(plan);
    }
}
