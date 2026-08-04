package com.huawei.finance.intent;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.SubIntent;
import java.util.Map;
import java.util.Optional;

/** Compiles deferred natural-language conditions; it never decides or executes an action. */
@FunctionalInterface
public interface ConditionResolver {
    ConditionResolver NONE = request -> Optional.empty();

    Optional<Resolution> resolve(Request request);

    record Resolution(ConditionExpression expression, String modelVersion, String promptVersion) { }

    record Request(String planId, IntentPlan plan, SubIntent item,
                   Map<String, Map<String, Object>> stepFacts,
                   Map<String, Object> parameters,
                   Map<String, CapabilityCard> cards) {
        public Request {
            stepFacts = stepFacts == null ? Map.of() : Map.copyOf(stepFacts);
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            cards = cards == null ? Map.of() : Map.copyOf(cards);
        }
    }
}
