package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Api;
import java.util.Map;

/** 目标 Agent 可使用的主体视图；只在目标进程内存在。 */
@Api
public record ResolvedPrincipal(
        String subjectRef,
        boolean verified,
        String authLevel,
        String channel,
        Map<String, Object> claims) {

    public ResolvedPrincipal {
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }

    public static ResolvedPrincipal anonymous(String channel) {
        return new ResolvedPrincipal(null, false, "ANONYMOUS", channel, Map.of());
    }
}
