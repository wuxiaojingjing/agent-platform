package com.huawei.finance.runtime.multi;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanGroundingPolicyTest {

    private final PlanGroundingPolicy policy = new PlanGroundingPolicy();

    @Test
    void acceptsLegalCompletionAsHybrid() {
        IntentPlan baseline = plan(item(0, "余额", "cap.balance", locked("cap.balance")),
                item(1, "基金", null, unresolved("cap.fund", "cap.nav.fund")));
        IntentPlan proposed = proposed(baseline,
                item(0, "余额", "cap.balance", locked("cap.balance")),
                item(1, "基金", "cap.fund", unresolved("cap.fund", "cap.nav.fund")));

        PlanGroundingPolicy.Result result = policy.validate(baseline, proposed);

        assertThat(result.outcome()).isEqualTo(PlanGroundingPolicy.Outcome.ACCEPTED);
        assertThat(result.plan().source()).isEqualTo(IntentPlan.Source.HYBRID);
        assertThat(result.plan().items()).extracting(SubIntent::capabilityId)
                .containsExactly("cap.balance", "cap.fund");
    }

    @Test
    void allUnresolvedCompletedByPlannerUsesPlannerSource() {
        IntentPlan baseline = plan(item(0, "余额", null, unresolved("cap.balance")),
                item(1, "基金", null, unresolved("cap.fund")));
        IntentPlan proposed = proposed(baseline,
                item(0, "余额", "cap.balance", unresolved("cap.balance")),
                item(1, "基金", "cap.fund", unresolved("cap.fund")));

        assertThat(policy.validate(baseline, proposed).plan().source())
                .isEqualTo(IntentPlan.Source.PLANNER);
    }

    @Test
    void rejectsLockedReplacement() {
        IntentPlan baseline = plan(item(0, "余额", "cap.balance", locked("cap.balance")),
                item(1, "基金", null, unresolved("cap.fund")));
        IntentPlan proposed = proposed(baseline,
                item(0, "余额", "cap.asset", candidates(PlanResolution.Strength.LOCKED,
                        "cap.balance", "cap.asset")),
                item(1, "基金", "cap.fund", unresolved("cap.fund")));

        assertRejected(baseline, proposed, PlanGroundingPolicy.Outcome.LOCKED_REPLACED);
    }

    @Test
    void rejectsAddedOrMissingSteps() {
        IntentPlan baseline = plan(item(0, "余额", null, unresolved("cap.balance")),
                item(1, "基金", null, unresolved("cap.fund")),
                item(2, "账单", null, unresolved("cap.bill")));

        assertRejected(baseline, proposed(baseline,
                        item(0, "余额", "cap.balance", unresolved("cap.balance")),
                        item(1, "基金", "cap.fund", unresolved("cap.fund"))),
                PlanGroundingPolicy.Outcome.STEP_COUNT_MISMATCH);
        IntentPlan twoStepBaseline = plan(item(0, "余额", null, unresolved("cap.balance")),
                item(1, "基金", null, unresolved("cap.fund")));
        assertRejected(twoStepBaseline, proposed(twoStepBaseline,
                        item(0, "余额", "cap.balance", unresolved("cap.balance")),
                        item(1, "基金", "cap.fund", unresolved("cap.fund")),
                        item(2, "账单", "cap.bill", unresolved("cap.bill"))),
                PlanGroundingPolicy.Outcome.STEP_COUNT_MISMATCH);
    }

    @Test
    void rejectsOutOfCandidatesAndDuplicateCapability() {
        IntentPlan baseline = plan(item(0, "余额", null, unresolved("cap.balance")),
                item(1, "基金", null, unresolved("cap.fund", "cap.balance")));

        assertRejected(baseline, proposed(baseline,
                        item(0, "余额", "cap.asset", unresolved("cap.balance")),
                        item(1, "基金", "cap.fund", unresolved("cap.fund", "cap.balance"))),
                PlanGroundingPolicy.Outcome.OUT_OF_CANDIDATES);
        assertRejected(baseline, proposed(baseline,
                        item(0, "余额", "cap.balance", unresolved("cap.balance")),
                        item(1, "基金", "cap.balance", unresolved("cap.fund", "cap.balance"))),
                PlanGroundingPolicy.Outcome.DUPLICATE_CAPABILITY);
    }

    @Test
    void rejectsChangedClauseOrderRelationOrCondition() {
        IntentPlan baseline = conditionalPlan();
        SubIntent first = baseline.items().get(0);
        SubIntent second = baseline.items().get(1);

        assertRejected(baseline, proposed(baseline,
                        item(0, "别的原句", "cap.balance", first.resolution()), second),
                PlanGroundingPolicy.Outcome.CONDITION_LOST);
        assertRejected(baseline, proposed(baseline,
                        first, new SubIntent(1, second.text(), second.capabilityId(), second.summary(),
                                Enums.IntentRelation.SEQUENTIAL, null, second.resolution())),
                PlanGroundingPolicy.Outcome.CONDITION_LOST);
        assertRejected(baseline, proposed(baseline,
                        first, new SubIntent(1, second.text(), second.capabilityId(), second.summary(),
                                Enums.IntentRelation.CONDITIONAL, "余额低于 10", second.resolution())),
                PlanGroundingPolicy.Outcome.CONDITION_LOST);
    }

    private void assertRejected(IntentPlan baseline, IntentPlan proposed,
                                PlanGroundingPolicy.Outcome outcome) {
        PlanGroundingPolicy.Result result = policy.validate(baseline, proposed);
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.plan()).isSameAs(baseline);
    }

    private static IntentPlan conditionalPlan() {
        return new IntentPlan("查余额，不足就别转", List.of(
                item(0, "查余额", "cap.balance", preferred("cap.balance")),
                new SubIntent(1, "转账", "cap.transfer", "cap.transfer",
                        Enums.IntentRelation.CONDITIONAL, "余额不足", preferred("cap.transfer"))),
                IntentPlan.Source.RULE);
    }

    private static IntentPlan plan(SubIntent... items) {
        return new IntentPlan("原始目标", List.of(items), IntentPlan.Source.RULE);
    }

    private static IntentPlan proposed(IntentPlan baseline, SubIntent... items) {
        return new IntentPlan(baseline.original(), List.of(items), IntentPlan.Source.PLANNER);
    }

    private static SubIntent item(int order, String text, String capabilityId,
                                  PlanResolution resolution) {
        return new SubIntent(order, text, capabilityId, capabilityId,
                order == 0 ? Enums.IntentRelation.PARALLEL : Enums.IntentRelation.SEQUENTIAL,
                null, resolution);
    }

    private static PlanResolution locked(String... ids) {
        return candidates(PlanResolution.Strength.LOCKED, ids);
    }

    private static PlanResolution preferred(String... ids) {
        return candidates(PlanResolution.Strength.PREFERRED, ids);
    }

    private static PlanResolution unresolved(String... ids) {
        return candidates(PlanResolution.Strength.UNRESOLVED, ids);
    }

    private static PlanResolution candidates(PlanResolution.Strength strength, String... ids) {
        return new PlanResolution(strength, 0.4, 0.1, List.of(ids), List.of("rule:test"));
    }
}
