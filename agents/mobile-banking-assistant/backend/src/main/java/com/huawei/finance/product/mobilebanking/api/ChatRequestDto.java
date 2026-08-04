package com.huawei.finance.product.mobilebanking.api;

import com.huawei.finance.runtime.ActionEvent;

/**
 * 会话请求。
 *
 * @param sessionId 会话标识，必填
 * @param userId    用户标识
 * @param query     用户原文
 * @param channel   渠道（MOBILE_BANK / WECHAT / ...），参与出口缓存键
 * @param page      当前页面，强规则会用到（如「在转账页说转账」）
 * @param userState 用户状态标签
 */
public record ChatRequestDto(
        String sessionId,
        String userId,
        String query,
        String channel,
        String page,
        String userState,
        ActionEvent action) {

    public ChatRequestDto(String sessionId, String userId, String query, String channel,
                          String page, String userState) {
        this(sessionId, userId, query, channel, page, userState, null);
    }
}
