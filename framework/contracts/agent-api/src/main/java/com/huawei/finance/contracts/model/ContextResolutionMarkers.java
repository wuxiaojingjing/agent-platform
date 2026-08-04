package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/** Platform-owned markers for refreshing model-resolved domain references before execution. */
@Api
public final class ContextResolutionMarkers {

    public static final String RESOLVER_AGENT_ID = "__context.resolverAgentId";
    public static final String RESOLVER_CAPABILITY_ID = "__context.resolverCapabilityId";
    public static final String RESOLUTION_INPUT_KEYS = "__context.resolutionInputKeys";
    public static final String REFRESH_AT_EXECUTION = "__context.refreshAtExecution";
    public static final String FAILURE_REASON = "__context.resolutionFailureReason";
    public static final String FAILURE_MISSING_SLOTS = "__context.resolutionFailureMissingSlots";
    public static final String RESOLVED_SLOTS = "resolvedSlots";
    public static final String RESOLUTION_MODE = "resolutionMode";
    public static final String CONTEXT_ONLY = "CONTEXT_ONLY";
    public static final String EXECUTION = "EXECUTION";

    private ContextResolutionMarkers() {
    }
}
