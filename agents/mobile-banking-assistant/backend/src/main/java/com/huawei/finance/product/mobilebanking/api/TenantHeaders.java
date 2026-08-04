package com.huawei.finance.product.mobilebanking.api;

/**
 * 渠道网关注入的租户与身份头（FP-65）。
 *
 * <p>为什么身份走头而不走请求体：请求体是客户端写的，客户端说了不算的东西就不该让客户端说
 * （与 {@code clarifyRetry} 由服务端判定同一条原则，v0.7 §2.1.1 注 4）。手机银行 App 里
 * 一个能改自己 body 的人不该因此能查别人的余额，而 {@code X-User-ID} 由渠道网关在鉴权之后
 * 注入，App 改不了。
 *
 * <p>因此 {@code ChatRequestDto.userId} 从本轮起只有一个用途：**与头比对**。不一致就拒绝，
 * 不是静默取头里那个——不一致意味着上游有 bug 或者有人在试，两种都该在这里停住并留痕。
 *
 * @param userId  用户标识，来自 {@code X-User-ID}
 * @param spaceId 租户/空间标识，来自 {@code X-Space-ID}。参与出口缓存键，见
 *                {@code RequestContext#spaceId()}
 * @param channel 渠道，来自 {@code X-Channel-ID}；缺失时回落请求体的 channel。
 *                渠道只影响话术与缓存分片，不影响能看到什么数据，所以它是唯一允许回落的一项
 */
public record TenantHeaders(String userId, String spaceId, String channel) {

    public static final String HEADER_USER_ID = "X-User-ID";
    public static final String HEADER_SPACE_ID = "X-Space-ID";
    public static final String HEADER_CHANNEL_ID = "X-Channel-ID";

    /** 缺头或与请求体冲突时的拒绝原因。进日志与指标，不面客。 */
    public enum Rejection {
        MISSING_USER_ID,
        MISSING_SPACE_ID,
        USER_ID_MISMATCH
    }

    /** 解析结果：要么是一份可用的租户上下文，要么是一个拒绝原因，不存在两者都有的中间态。 */
    public record Resolution(TenantHeaders headers, Rejection rejection) {

        public boolean rejected() {
            return rejection != null;
        }
    }

    /**
     * 从头与请求体解析。
     *
     * @param headerUserId  {@code X-User-ID} 原值，可为 null
     * @param headerSpaceId {@code X-Space-ID} 原值，可为 null
     * @param headerChannel {@code X-Channel-ID} 原值，可为 null
     * @param bodyUserId    请求体里的 userId，只用于比对
     * @param bodyChannel   请求体里的 channel，作为渠道头的回落
     */
    public static Resolution resolve(String headerUserId, String headerSpaceId, String headerChannel,
                                     String bodyUserId, String bodyChannel) {
        String userId = trim(headerUserId);
        String spaceId = trim(headerSpaceId);

        if (userId == null) {
            return new Resolution(null, Rejection.MISSING_USER_ID);
        }
        if (spaceId == null) {
            // 没有租户就无法隔离缓存与能力可见性。此时"先放过去按默认租户处理"是最糟的选择：
            // 它会往共享的缓存键上写一条谁都可能读到的记录
            return new Resolution(null, Rejection.MISSING_SPACE_ID);
        }

        String body = trim(bodyUserId);
        if (body != null && !body.equals(userId)) {
            return new Resolution(null, Rejection.USER_ID_MISMATCH);
        }

        String channel = trim(headerChannel);
        return new Resolution(
                new TenantHeaders(userId, spaceId, channel != null ? channel : trim(bodyChannel)), null);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
