package com.huawei.finance.common.context;

import com.huawei.finance.stability.Api;

/** Redacted authentication state that may be propagated to a child Agent. */
@Api
public record PrincipalState(
        String subjectRef,
        boolean verified,
        String authLevel,
        String channel) {

    public PrincipalState {
        authLevel = authLevel == null || authLevel.isBlank() ? "ANONYMOUS" : authLevel;
        channel = channel == null || channel.isBlank() ? "UNKNOWN" : channel;
    }

    public static PrincipalState anonymous(String channel) {
        return new PrincipalState(null, false, "ANONYMOUS", channel);
    }
}
