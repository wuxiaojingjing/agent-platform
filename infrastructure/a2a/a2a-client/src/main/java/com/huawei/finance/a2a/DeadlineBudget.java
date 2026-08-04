package com.huawei.finance.a2a;

import java.time.Duration;
import java.time.Instant;

/**
 * 超时预算逐层收缩（架构草案 v0.2 §6.3）。
 *
 * <p>规则一句话:{@code deadline} 是绝对时刻，逐层**只能缩小**，取
 * {@code min(上游 deadline − 回传预留, 本地上限, 卡上声明)}。
 *
 * <p>不定这条的后果:现在超时上限由主控强制施加（{@code min(卡上声明, 主控上限)}），
 * 跨 A2A 之后若每层各取本地上限，总时长是各层之和——用户端就是一次没有反馈的久等。
 *
 * <p>回传预留是从上游 deadline 里**先扣掉**的:不扣的话最深那层用完全部预算，
 * 回传路上必然超时，而上游看到的是「结果未知」而不是「下游算超了」。
 */
public final class DeadlineBudget {

    private DeadlineBudget() {
    }

    /**
     * 算出本层该用的 deadline。
     *
     * @param upstream 上游 deadline；null 表示本层是根委托
     * @param now 当前时刻
     * @param localTimeoutMs 本地上限
     * @param cardTimeoutMs 卡上声明；&lt;=0 表示未声明
     * @param returnReserveMs 回传预留
     * @return 绝对时刻，保证不晚于 {@code upstream − 回传预留}
     */
    public static Instant next(Instant upstream, Instant now, long localTimeoutMs,
                              long cardTimeoutMs, long returnReserveMs) {
        Instant local = now.plusMillis(localTimeoutMs);
        Instant candidate = local;

        if (cardTimeoutMs > 0) {
            Instant declared = now.plusMillis(cardTimeoutMs);
            // 卡上声明只能更小。声明得比本地上限大时按本地上限——
            // 能力卡由领域方维护，让他们声明「我要 999 秒」并照做，
            // 等于把用户的等待时长交给被调方决定
            candidate = declared.isBefore(candidate) ? declared : candidate;
        }

        if (upstream != null) {
            Instant ceiling = upstream.minusMillis(returnReserveMs);
            candidate = ceiling.isBefore(candidate) ? ceiling : candidate;
        }
        return candidate;
    }

    /** 剩余预算。已过期返回零而不是负数——负的剩余时间没有调用方能正确处理。 */
    public static Duration remaining(Instant deadline, Instant now) {
        if (deadline == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
