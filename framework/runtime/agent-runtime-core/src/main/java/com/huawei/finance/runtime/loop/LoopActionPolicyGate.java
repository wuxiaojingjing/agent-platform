package com.huawei.finance.runtime.loop;

import com.huawei.finance.contracts.model.*;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class LoopActionPolicyGate {
    public ActionCheck check(Run run, Action action, CapabilityCard card, boolean accepted) {
        if (run.deadline() != null && Instant.now().isAfter(run.deadline()))
            return check(Verdict.REJECT,"LOOP_DEADLINE",card,List.of(),action.parameters());
        if (run.iteration() >= run.maxIterations())
            return check(Verdict.REJECT,"LOOP_BUDGET_EXHAUSTED",card,List.of(),action.parameters());
        if (action.actionType() == ActionType.ASK_USER)
            return check(Verdict.WAIT_USER,"LOOP_ASK_USER",card,List.of(),action.parameters());
        if (action.actionType() == ActionType.HANDOFF)
            return check(Verdict.HANDOFF,"LOOP_HANDOFF",card,List.of(),action.parameters());
        if (action.actionType() == ActionType.FINISH)
            return check(Verdict.PROCEED,"LOOP_FINISH",null,List.of(),action.parameters());
        if (action.actionType() == ActionType.SEARCH_KNOWLEDGE)
            return check(Verdict.PROCEED,"KNOWLEDGE_READ_ONLY",null,List.of(),action.parameters());
        if (action.actionType() == ActionType.RESOLVE_MENU && card != null) {
            if (!accepted && card.effectiveLoopAccess() == EffectiveLoopAccess.PROPOSE_ONLY)
                return check(Verdict.WAIT_REVIEW,"REVIEW_REQUIRED",card,List.of(),action.parameters());
            return check(Verdict.PROCEED,"MENU_READ_ONLY",card,List.of(),action.parameters());
        }
        if (card == null || card.effectiveLoopAccess() == EffectiveLoopAccess.DENY)
            return check(Verdict.REJECT,"LOOP_ACCESS_DENIED",card,List.of(),action.parameters());
        List<String> missing = card.requiredSlots().stream().filter(s -> !action.parameters().containsKey(s)).toList();
        if (!missing.isEmpty()) return check(Verdict.WAIT_USER,"MISSING_SLOT",card,missing,action.parameters());
        if (!accepted && card.confirmationPolicy() == ConfirmationPolicy.EXPLICIT)
            return check(Verdict.WAIT_CONFIRMATION,"CONFIRMATION_REQUIRED",card,List.of(),action.parameters());
        if (!accepted && (card.confirmationPolicy() == ConfirmationPolicy.REVIEW_ONLY
                || card.effectiveLoopAccess() == EffectiveLoopAccess.PROPOSE_ONLY))
            return check(Verdict.WAIT_REVIEW,"REVIEW_REQUIRED",card,List.of(),action.parameters());
        return check(Verdict.PROCEED,"PROCEED",card,List.of(),action.parameters());
    }
    private static ActionCheck check(Verdict verdict,String reason,CapabilityCard card,List<String> missing,Map<String,Object> params){
        return new ActionCheck(verdict,reason,missing,params,card==null?"R0":card.riskLevel().name(),
                card==null?ConfirmationPolicy.NONE:card.confirmationPolicy(),card!=null&&card.hasSideEffects());
    }
}
