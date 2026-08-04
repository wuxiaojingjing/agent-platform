package com.huawei.finance.common.context;

/**
 * 共享基础设施上的作用域键（架构草案阶段 1 / v0.2 §11）。
 *
 * <p>形状统一为 {@code huawei-finance-agent:{agent}:{space}:...}。Agent 与租户都必须进前缀：
 * 少一维就会在多 Agent 共享 Redis 时串味，而单 Agent 测试环境永远复现不出来。
 */
public final class ScopeKeys {

    private ScopeKeys() {
    }

    /** 清洗进 Redis 键段：只保留安全字符，空则回落占位。 */
    public static String segment(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim();
        String cleaned = value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    public static String prefix(RequestContext ctx) {
        return prefix(ctx.agentId(), ctx.spaceId());
    }

    public static String prefix(String agentId, String spaceId) {
        return "huawei-finance-agent:"
                + segment(agentId, RequestContext.AGENT_ENTRY)
                + ":"
                + segment(spaceId, RequestContext.SPACE_UNSCOPED);
    }

    public static String turns(RequestContext ctx, String sessionId) {
        return prefix(ctx) + ":turns:" + sessionId;
    }

    public static String turns(String agentId, String spaceId, String sessionId) {
        return prefix(agentId, spaceId) + ":turns:" + sessionId;
    }

    public static String sessionLock(RequestContext ctx) {
        return prefix(ctx) + ":lock:session:" + ctx.sessionId();
    }

    public static String sessionAffinity(RequestContext ctx) {
        return prefix(ctx) + ":affinity:session:" + ctx.sessionId();
    }

    public static String sessionAffinity(String agentId, String spaceId, String sessionId) {
        return prefix(agentId, spaceId) + ":affinity:session:" + sessionId;
    }
}
