package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Api;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves a capability to the base URL of an independently deployed Agent. */
@Api
public interface AgentEndpointResolver {

    Optional<String> resolve(String capabilityId);

    Set<String> knownCapabilities();

    String source();

    static AgentEndpointResolver ofStatic(Map<String, String> endpoints) {
        Map<String, String> copy = Map.copyOf(endpoints);
        return new AgentEndpointResolver() {
            @Override
            public Optional<String> resolve(String capabilityId) {
                return Optional.ofNullable(capabilityId).map(copy::get);
            }

            @Override
            public Set<String> knownCapabilities() {
                return copy.keySet();
            }

            @Override
            public String source() {
                return "static";
            }
        };
    }
}
