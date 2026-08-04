package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Api;

/** A2A 中传播的最小主体上下文；引用值必须是不透明令牌。 */
@Api
public record PrincipalContext(
        String principalRef,
        String authLevel,
        String channel,
        String sourceSessionRef) {

    public PrincipalContext {
        authLevel = blankTo(authLevel, "ANONYMOUS");
        channel = blankTo(channel, "UNKNOWN");
    }

    public boolean authenticated() {
        return principalRef != null && !principalRef.isBlank()
                && !"ANONYMOUS".equalsIgnoreCase(authLevel);
    }

    public boolean hasSourceSession() {
        return sourceSessionRef != null && !sourceSessionRef.isBlank();
    }

    public static PrincipalContext anonymous(String channel, String sourceSessionRef) {
        return new PrincipalContext(null, "ANONYMOUS", channel, sourceSessionRef);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
