package com.huawei.finance.runtime.multi;

import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 对所有 IntentPlanner 实现统一施加逐步骤候选约束。 */
public final class PlanGroundingPolicy {

    public enum Outcome {
        ALL_LOCKED,
        ACCEPTED,
        EMPTY_CANDIDATES,
        STEP_COUNT_MISMATCH,
        LOCKED_REPLACED,
        OUT_OF_CANDIDATES,
        CONDITION_LOST,
        DUPLICATE_CAPABILITY,
        PLANNER_FALLBACK
    }

    public Result validate(IntentPlan baseline, IntentPlan proposed) {
        if (proposed == null || proposed == baseline || proposed.source() == IntentPlan.Source.RULE) {
            return new Result(baseline, Outcome.PLANNER_FALLBACK);
        }
        if (proposed.items().size() != baseline.items().size()) {
            return new Result(baseline, Outcome.STEP_COUNT_MISMATCH);
        }

        List<SubIntent> grounded = new ArrayList<>(baseline.items().size());
        Set<String> selected = new HashSet<>();
        boolean hadRuleSelection = false;
        for (int i = 0; i < baseline.items().size(); i++) {
            SubIntent anchor = baseline.items().get(i);
            SubIntent choice = proposed.items().get(i);
            PlanResolution resolution = anchor.resolution();
            String capabilityId = choice.capabilityId();

            if (anchor.order() != choice.order()
                    || !Objects.equals(anchor.text(), choice.text())
                    || !Objects.equals(anchor.relation(), choice.relation())
                    || !Objects.equals(anchor.condition(), choice.condition())) {
                return new Result(baseline, Outcome.CONDITION_LOST);
            }
            if (resolution.locked() && !Objects.equals(anchor.capabilityId(), capabilityId)) {
                return new Result(baseline, Outcome.LOCKED_REPLACED);
            }
            if (capabilityId == null || !resolution.candidateIds().contains(capabilityId)) {
                return new Result(baseline, Outcome.OUT_OF_CANDIDATES);
            }
            if (!selected.add(capabilityId)) {
                return new Result(baseline, Outcome.DUPLICATE_CAPABILITY);
            }
            hadRuleSelection |= anchor.capabilityId() != null;
            grounded.add(new SubIntent(anchor.order(), anchor.text(), capabilityId,
                    choice.summary(), anchor.relation(), anchor.condition(), resolution));
        }

        IntentPlan.Source source = hadRuleSelection
                ? IntentPlan.Source.HYBRID : IntentPlan.Source.PLANNER;
        return new Result(new IntentPlan(baseline.original(), grounded, source), Outcome.ACCEPTED);
    }

    public record Result(IntentPlan plan, Outcome outcome) {
    }
}
