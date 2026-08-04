package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Spi;

/** Reads the source Agent's current context version for ContextDelta CAS. */
@Spi
@FunctionalInterface
public interface ContextStateVersionProvider {
    long currentVersion(String tenantId, String agentId, String sessionId);

    ContextStateVersionProvider UNKNOWN = (tenantId, agentId, sessionId) -> -1L;
}
