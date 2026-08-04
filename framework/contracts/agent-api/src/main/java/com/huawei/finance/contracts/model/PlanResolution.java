package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Objects;

/** 规则拆分阶段为单个子意图留下的候选约束。 */
@Api
public record PlanResolution(
        Strength strength,
        double topScore,
        double margin,
        List<String> candidateIds,
        List<String> evidenceRefs) {

    public enum Strength {
        LOCKED,
        PREFERRED,
        UNRESOLVED
    }

    public PlanResolution {
        Objects.requireNonNull(strength, "规划分级不得为空");
        candidateIds = List.copyOf(Objects.requireNonNull(candidateIds, "规划候选不得为空"));
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "规划证据不得为空"));
    }

    /** 确定性规则直接产出的锁定候选。 */
    public static PlanResolution locked(String capabilityId, String evidenceRef) {
        Objects.requireNonNull(capabilityId, "锁定能力不得为空");
        Objects.requireNonNull(evidenceRef, "锁定证据不得为空");
        return new PlanResolution(Strength.LOCKED, 1.0, 1.0,
                List.of(capabilityId), List.of(evidenceRef));
    }

    public boolean locked() {
        return strength == Strength.LOCKED;
    }
}
