package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 造 TASK 信封的公用脚手架。 */
final class DomainAgentNodeTestSupport {

    private DomainAgentNodeTestSupport() {
    }

    static DelegationEnvelope task(String target, String capabilityId) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t", "mobile-banking-assistant",
                target, "root", "parent", "src", "d-" + target + "-" + capabilityId, "trace",
                DelegationMode.TASK, null, capabilityId, Map.of(), List.of(),
                Instant.parse("2025-07-28T10:00:30Z"), List.of("mobile-banking-assistant"));
    }
}
