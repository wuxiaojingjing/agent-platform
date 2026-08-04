package com.huawei.finance.runtime.entry;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.registry.asset.AssetBundle;

public class LoopEntryPolicyGate {
    private final boolean loopEnabled;
    public LoopEntryPolicyGate(boolean loopEnabled){this.loopEnabled=loopEnabled;}
    public RouteDecision tighten(RouteDecision d,IntentEvidence evidence,AssetBundle assets){
        for(String id:d.candidateIds())if(assets.capability(id)==null)
            return replace(d,Decision.CLARIFY,TaskShape.AMBIGUOUS_GOAL,ReasonCode.INVALID_MODEL_OUTPUT);
        if(!d.missingSlots().isEmpty()&&switch(d.decision()){
            case EXECUTE_CAPABILITY,START_WORKFLOW,STATIC_PLAN,DELEGATE_GOAL,START_LOOP->true;default->false;})
            return replace(d,Decision.CLARIFY,TaskShape.AMBIGUOUS_GOAL,ReasonCode.MISSING_SLOT);
        if(d.decision()==Decision.STATIC_PLAN){
            IntentPlan plan=evidence.intentPlan();
            if(plan==null||!plan.fullyResolved())return replace(d,Decision.CLARIFY,TaskShape.AMBIGUOUS_GOAL,ReasonCode.LOW_MARGIN);
            if(plan.items().stream().anyMatch(item->assets.capability(item.capabilityId())==null))
                return replace(d,Decision.CLARIFY,TaskShape.AMBIGUOUS_GOAL,ReasonCode.INVALID_MODEL_OUTPUT);
            d=d.withIntentPlan(plan);
        }
        if(d.decision()==Decision.START_LOOP){
            boolean valid=d.taskShape()==TaskShape.OPEN_ENDED_DIAGNOSIS||d.taskShape()==TaskShape.OBSERVATION_DRIVEN;
            if(!valid)return evidence.intentPlan()!=null&&evidence.intentPlan().fullyResolved()
                    ?replace(d,Decision.STATIC_PLAN,evidence.intentPlan().hasConditional()?TaskShape.CONDITIONAL_PLAN:TaskShape.FIXED_MULTI_STEP,ReasonCode.RESULT_RULE).withIntentPlan(evidence.intentPlan())
                    :replace(d,Decision.CLARIFY,TaskShape.AMBIGUOUS_GOAL,ReasonCode.INVALID_MODEL_OUTPUT);
            if(!loopEnabled)return replace(d,Decision.HANDOFF,d.taskShape(),ReasonCode.LOOP_DISABLED);
            boolean anyAllowed=d.candidateIds().stream().map(assets::capability)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(card->card.effectiveLoopAccess()!=EffectiveLoopAccess.DENY);
            if(!anyAllowed)return replace(d,Decision.HANDOFF,d.taskShape(),ReasonCode.POLICY_BLOCK);
            // TaskShape 表达“开放诊断/观测驱动”，reasonCode 表达“为什么必须进 Loop”。
            // 模型偶尔会把 OPEN_ENDED_DIAGNOSIS 同时填进两个字段，这里按公共契约归一。
            if(d.reasonCode()!=ReasonCode.AFTER_OBSERVATION)
                d=replace(d,Decision.START_LOOP,d.taskShape(),ReasonCode.AFTER_OBSERVATION);
        }
        return d;
    }
    private static RouteDecision replace(RouteDecision d,Decision decision,TaskShape shape,ReasonCode reason){
        return new RouteDecision(decision,d.target(),d.candidateIds(),shape,d.intentPlan(),d.missingSlots(),d.confidence(),
                reason,d.evidenceRefs(),d.modelVersion(),d.promptVersion(),d.configVersion(),d.shortCircuit());
    }
}
