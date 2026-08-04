package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.fastpath.recall.ComparePlanBuilder;
import com.huawei.finance.fastpath.policy.ProductComparisonPolicyGate;
import com.huawei.finance.fastpath.recall.ProductComparisonGrounder;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComparePlanBuilderTest {

    private static ComparePlanBuilder builder;
    private static ProductComparisonPolicyGate policyGate;
    private static ProductComparisonGrounder grounder;

    @BeforeAll
    static void load() {
        AssetBundle bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        builder = new ComparePlanBuilder(bundle);
        policyGate = new ProductComparisonPolicyGate(bundle);
        grounder = new ProductComparisonGrounder(bundle);
    }

    @Test
    @DisplayName("保险与理财不属于同类产品 → 策略拦截且不生成执行计划")
    void crossTypeComparisonDoesNotBuildPlan() {
        ProductComparisonPolicyGate.Outcome outcome = policyGate.evaluate(
                grounder.ground("对比产品A和产品B").resolvedRequest().orElseThrow());

        assertThat(outcome.incompatible()).isTrue();
        assertThat(outcome.leftGroup()).isEqualTo("保险产品");
        assertThat(outcome.rightGroup()).isEqualTo("理财产品");
        assertThat(policyGate.presentationSlots(outcome))
                .containsEntry("options", java.util.List.of("查看产品A", "查看产品B"));
        assertThat(builder.plan("对比产品A和产品B")).isEmpty();
    }

    @Test
    @DisplayName("同类理财产品B与B2 → 生成等待真实 Observation 的两项计划")
    void sameTypeComparisonBuildsParallelProductQueries() {
        IntentPlan plan = builder.plan("对比产品B和产品B2").orElseThrow();

        assertThat(plan.items()).hasSize(2);
        assertThat(plan.items()).extracting(item -> item.capabilityId()).containsExactly(
                "cap.wealth-product.product.query", "cap.wealth-product.product-b2.query");
        assertThat(plan.items()).allMatch(item -> item.relation() == Enums.IntentRelation.PARALLEL);
        assertThat(ComparePlanBuilder.isComparePlan(plan)).isTrue();
    }

    @Test
    @DisplayName("无对比信号或只提一侧产品 → 不给计划")
    void nonCompareReturnsEmpty() {
        assertThat(builder.plan("查一下余额")).isEmpty();
        assertThat(builder.plan("对比产品A")).isEmpty();
        assertThat(builder.plan("产品A和产品B怎么样")).isEmpty();
        assertThat(builder.plan("对比产品A和未知产品")).isEmpty();
    }
}
