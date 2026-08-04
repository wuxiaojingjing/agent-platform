package com.huawei.finance.agent.promptopt;

import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;

/**
 * 带退避的重试，只给这个离线工具用。
 *
 * <p>为什么不直接用网关自带的重试：那一层是为面客链路设计的，**退避为 0**——
 * 快路径的毫秒预算里挤不出等待时间，宁可立刻失败走降级。这个取舍在线上是对的，
 * 在这里是错的：优化器一轮要发几十次请求，跑十分钟也无所谓，唯一不能接受的是
 * 因为一次瞬时抖动就把结论算歪。
 *
 * <p>实测确认这种抖动是真的：同一个密钥、同一个端点，216 字的请求握手被对端掐断，
 * 紧接着 6 万字的请求正常返回。所以「失败」在这里不代表请求有问题，只代表要再试一次。
 */
final class GatewayRetry {

    private static final int ATTEMPTS = 4;
    private static final long[] BACKOFF_MS = {1_000, 3_000, 8_000};

    private GatewayRetry() {
    }

    /**
     * 重试到成功，或抛。
     *
     * <p>抛而不是返回不可用，是刻意的：分数只有在**同一份分母**上才能跨轮比较。
     * 少算一条的那一轮看起来可能更好，而它好在少考了一道题——这种失败没有任何症状，
     * 只会让优化器把一次网络抖动当成一版更好的提示词。
     */
    static String chat(ModelGatewayClient gateway, ChatRequest request, String what) {
        String lastReason = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
            }
            GatewayResult<String> result = gateway.chat(request);
            if (result.available()) {
                return result.value();
            }
            lastReason = result.reason();
            System.out.printf("  · %s 第 %d 次失败（%s），退避后重试%n",
                    what, attempt + 1, lastReason);
        }
        throw new IllegalStateException(
                what + " 连续 " + ATTEMPTS + " 次失败（最后一次：" + lastReason
                        + "）。本次运行作废——在缺样本的分母上比分数，只会把网络抖动当成提示词变好了");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试被中断", e);
        }
    }
}
