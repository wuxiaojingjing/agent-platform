package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.TenantHeaders;
import org.springframework.http.HttpHeaders;

/**
 * 端到端用例的租户头装配（FP-65）。
 *
 * <p>抽成一处而不是每个用例各写一遍，是为了让「头是必需的」只需在一个地方维护：
 * 将来加一个必填头（比如 {@code X-Tenant-Tier}），所有端到端用例跟着一起过。
 */
final class TenantHeaderSupport {

    /** 租户取固定值：这些用例验的是出口语义，不是多租户隔离，隔离另有专门用例。 */
    static final String SPACE_ID = "space-test";

    private TenantHeaderSupport() {
    }

    static HttpHeaders of(ChatRequestDto request) {
        return of(request.userId(), request.channel());
    }

    static HttpHeaders of(String userId, String channel) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TenantHeaders.HEADER_USER_ID, userId);
        headers.set(TenantHeaders.HEADER_SPACE_ID, SPACE_ID);
        if (channel != null && !channel.isBlank()) {
            headers.set(TenantHeaders.HEADER_CHANNEL_ID, channel);
        }
        return headers;
    }
}
