package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;

@FunctionalInterface
public interface ContinuationUnderstandingModel {
    Resolution understand(String tenantId, String agentId, String sessionId,
                          String userInput, Context context);

    /** Rich projection used online; the legacy method remains the functional SPI for extensions. */
    default Resolution understand(String tenantId, String agentId, String sessionId,
                                  String userInput, Context context, IntentContext intentContext) {
        return understand(tenantId, agentId, sessionId, userInput, context);
    }

    ContinuationUnderstandingModel UNAVAILABLE = (tenant, agent, session, input, context) ->
            new Resolution(Event.UNRESOLVED, null, java.util.Map.of(), null, 0, "MODEL_UNAVAILABLE");
}
