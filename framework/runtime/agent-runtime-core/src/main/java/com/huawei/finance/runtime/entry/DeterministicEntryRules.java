package com.huawei.finance.runtime.entry;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.registry.asset.AssetBundle;

public class DeterministicEntryRules {
    public RouteDecision normalize(IntentEvidence evidence,AssetBundle assets){
        RouteDecision d=evidence.proposal();
        if(d.reasonCode()==ReasonCode.STANDARD_ANSWER && d.decision()!=Decision.DIRECT_KNOWLEDGE)
            return copy(d,Decision.DIRECT_KNOWLEDGE,new RouteTarget(RouteTarget.Type.KNOWLEDGE,evidence.templateKey()));
        if(d.candidateIds().size()!=1)return d;
        CapabilityCard card=assets.capability(d.selectedCandidateId());
        if(card==null)return d;
        Decision decision=d.decision();
        if (decision != Decision.EXECUTE_CAPABILITY && decision != Decision.START_WORKFLOW
                && decision != Decision.DELEGATE_GOAL && decision != Decision.NAVIGATION) {
            return d;
        }
        RouteTarget.Type type=RouteTarget.Type.CAPABILITY;
        if(card.capabilityId().startsWith("cap.nav.")){decision=Decision.NAVIGATION;type=RouteTarget.Type.MENU;}
        else if(card.type()==Enums.CapabilityType.WORKFLOW){decision=Decision.START_WORKFLOW;type=RouteTarget.Type.WORKFLOW;}
        else if(card.type()==Enums.CapabilityType.AGENT){decision=Decision.DELEGATE_GOAL;type=RouteTarget.Type.AGENT;}
        return copy(d,decision,new RouteTarget(type,card.capabilityId()));
    }
    static RouteDecision copy(RouteDecision d,Decision decision,RouteTarget target){
        return new RouteDecision(decision,target,d.candidateIds(),d.taskShape(),d.intentPlan(),d.missingSlots(),
                d.confidence(),d.reasonCode(),d.evidenceRefs(),d.modelVersion(),d.promptVersion(),d.configVersion(),d.shortCircuit());
    }
}
