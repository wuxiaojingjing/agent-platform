package com.huawei.finance.product.mobilebanking.api;

/** Ordered user-visible projection emitted while a chat turn is running. */
public record ChatStreamEvent(
        long sequence,
        String type,
        String component,
        Integer itemIndex,
        Integer itemCount,
        ChatResponseDto response,
        String message) {

    public static ChatStreamEvent started() {
        return new ChatStreamEvent(0, "TURN_STARTED", null, null, null, null, null);
    }
}
