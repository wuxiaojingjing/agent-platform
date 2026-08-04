package com.huawei.finance.runtime.loop;

import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import java.util.Map;

/** 模型不可用时只推进候选中的下一个只读动作，不创造新能力。 */
public class FallbackAgentLoopPlanner implements AgentLoopPlanner {
    @Override public Action nextAction(LoopContext context) {
        return context.candidates().stream()
                .filter(c -> !context.run().facts().containsKey(c.capabilityId()))
                .findFirst()
                .map(c -> action(ActionType.CALL_CAPABILITY, c.capabilityId(),
                        c.ownedSlots(context.confirmedSlots()), "NEXT_ALLOWED"))
                .orElseGet(() -> action(ActionType.FINISH, null, Map.of(), "NO_REMAINING_CANDIDATE"));
    }
    static Action action(ActionType type, String target, Map<String,Object> parameters, String reason) {
        Map<String,String> provenance = new java.util.LinkedHashMap<>();
        parameters.keySet().forEach(key -> provenance.put(key, "CONFIRMED_SLOT"));
        return new Action(type, target, parameters, provenance, reason,
                LoopActionFingerprint.of(type, target, parameters));
    }
}
