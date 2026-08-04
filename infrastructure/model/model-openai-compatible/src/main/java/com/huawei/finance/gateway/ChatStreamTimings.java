package com.huawei.finance.gateway;

/**
 * 一次流式 chat 的分段耗时（FP-63）。
 *
 * @param content           拼好的完整答复
 * @param firstFrameMs      首条非空 SSE data 到达，相对请求发出；未收到为 -1
 * @param firstTokenMs      首个非空 content delta 到达；未收到为 -1
 * @param completionTokens  服务端回报的 completion token 数；未知为 0
 * @param totalMs           整次流式调用墙钟耗时
 */
record ChatStreamTimings(
        String content,
        long firstFrameMs,
        long firstTokenMs,
        int completionTokens,
        long totalMs) {

    double avgTokenMs() {
        if (completionTokens <= 0 || totalMs < 0) {
            return -1;
        }
        return (double) totalMs / completionTokens;
    }
}
