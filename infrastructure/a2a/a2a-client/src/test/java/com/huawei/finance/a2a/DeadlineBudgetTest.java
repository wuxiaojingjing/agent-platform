package com.huawei.finance.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 超时预算逐层只能缩小（架构草案 v0.2 §6.3）。 */
class DeadlineBudgetTest {

    private static final Instant NOW = Instant.parse("2025-07-28T10:00:00Z");

    @Test
    @DisplayName("逐层收缩：三层之后总时长不是各层之和")
    void deadlineShrinksAcrossLayers() {
        // 不定这条规则时的形态：每层各取本地上限 8s，三层就是 24s，
        // 用户端是一次没有反馈的久等
        Instant layer1 = DeadlineBudget.next(null, NOW, 8000, 0, 500);
        Instant layer2 = DeadlineBudget.next(layer1, NOW, 8000, 0, 500);
        Instant layer3 = DeadlineBudget.next(layer2, NOW, 8000, 0, 500);

        assertThat(layer1).isEqualTo(NOW.plusMillis(8000));
        assertThat(layer2).isEqualTo(NOW.plusMillis(7500));
        assertThat(layer3).isEqualTo(NOW.plusMillis(7000));
        assertThat(layer3).as("三层总预算仍在 8s 内，不是 24s").isBeforeOrEqualTo(layer1);
    }

    @Test
    @DisplayName("卡上声明只能更小：声明 999 秒时按本地上限")
    void cardDeclarationCannotEnlarge() {
        // 能力卡由领域方维护。让他们声明「我要 999 秒」并照做，
        // 等于把用户的等待时长交给被调方决定
        Instant deadline = DeadlineBudget.next(null, NOW, 8000, 999_000, 500);

        assertThat(deadline).isEqualTo(NOW.plusMillis(8000));
    }

    @Test
    @DisplayName("卡上声明更小时按声明")
    void smallerCardDeclarationWins() {
        Instant deadline = DeadlineBudget.next(null, NOW, 8000, 3000, 500);

        assertThat(deadline).isEqualTo(NOW.plusMillis(3000));
    }

    @Test
    @DisplayName("上游预算比本地上限更紧时按上游")
    void upstreamCeilingWins() {
        Instant upstream = NOW.plusMillis(2000);

        Instant deadline = DeadlineBudget.next(upstream, NOW, 8000, 0, 500);

        assertThat(deadline).isEqualTo(NOW.plusMillis(1500));
    }

    @Test
    @DisplayName("已过期的剩余预算是零，不是负数")
    void remainingNeverNegative() {
        assertThat(DeadlineBudget.remaining(NOW.minusSeconds(5), NOW)).isZero();
        assertThat(DeadlineBudget.remaining(NOW.plusSeconds(5), NOW).toMillis()).isEqualTo(5000);
    }
}
