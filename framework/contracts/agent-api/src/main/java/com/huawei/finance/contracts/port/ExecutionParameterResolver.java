package com.huawei.finance.contracts.port;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.stability.Spi;
import java.util.Map;

/** Resolves context-derived execution parameters immediately before guardrail/idempotency. */
@Spi
public interface ExecutionParameterResolver {

    Resolution resolve(String capabilityId, Map<String, Object> parameters,
                       ContextLease lease, String subjectRef);

    record Resolution(boolean resolved, Map<String, Object> parameters,
                      String reasonCode, String evidenceRef) {
        public Resolution {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }

        public static Resolution unchanged(Map<String, Object> parameters) {
            return new Resolution(true, parameters, null, null);
        }

        public static Resolution failed(Map<String, Object> parameters, String reasonCode) {
            return new Resolution(false, parameters, reasonCode, null);
        }
    }
}
