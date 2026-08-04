package com.huawei.finance.intent;

import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.stability.Api;

/**
 * 跨域对比计划的识别（供 Agent 应用使用，不暴露 fastpath 内部类）。
 */
@Api
public final class ComparePlans {
    public static final String EVIDENCE_PREFIX = "catalog:product-compare:";

    private ComparePlans() {
    }

    public static boolean isComparePlan(IntentPlan plan) {
        if (plan == null || plan.items().size() != 2 || !plan.fullyResolved()) {
            return false;
        }
        if (plan.items().stream().map(item -> item.capabilityId()).distinct().count() != 2) {
            return false;
        }
        return plan.items().stream().allMatch(item -> item.resolution().evidenceRefs().stream()
                .anyMatch(ref -> ref.startsWith(EVIDENCE_PREFIX)));
    }
}
