package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.GuardrailHook;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GuardrailHook} 的契约测试包。行内实现完之后继承本类。
 *
 * <pre>{@code
 * class MyRiskGuardrailContractTest extends GuardrailHookContract {
 *     protected GuardrailHook guardrail() { return new MyRiskGuardrail(...); }
 *     protected CapabilityCard r2Card() { return ...; }
 * }
 * }</pre>
 *
 * <p><b>护栏这个扩展点的特殊之处：它的错误方向不对称。</b>误拦一笔正常业务，用户重试或
 * 走人工，损失有限且看得见；误放一笔该拦的，动的是真钱，而且没有任何信号——
 * 系统一切正常，日志干净，直到对账或投诉。所以下面每条都在验同一件事的不同侧面：
 * **拿不准的时候必须拒绝**。
 *
 * <p>这也是为什么没有「正常请求必须放行」那种用例。一个永远拒绝的护栏是难用的，
 * 但它不会造成资损；而为了让「必须放行」这条绿掉，实现方很容易把判定写松。
 * 好不好用由行内自己的业务用例负责，这里只守住不能越过的那条线。
 */
public abstract class GuardrailHookContract {

    /** 被测实现。 */
    protected abstract GuardrailHook guardrail();

    /**
     * 一张 R2（须显式确认）能力卡，且 {@code requiredSlots} 非空。
     *
     * <p>要 R2 是因为「未确认不得执行」这条只在 R2 上成立；要有必填槽位是因为
     * 「缺参数必须拦」需要一个能缺的参数。
     */
    protected abstract CapabilityCard r2Card();

    /** 一组能通过该能力全部必填槽位的参数。 */
    protected abstract Map<String, Object> completeParameters();

    @Test
    @DisplayName("前置条件：给的卡确实是 R2 且有必填槽位")
    void fixtureIsUsable() {
        CapabilityCard card = r2Card();
        assertThat(card).isNotNull();
        assertThat(card.riskLevel())
                .as("本 TCK 的多数用例只在 R2 上有意义")
                .isEqualTo(RiskLevel.R2);
        assertThat(card.requiredSlots())
                .as("需要至少一个必填槽位，才能构造出「缺参数」这个场景")
                .isNotEmpty();
        assertThat(completeParameters().keySet())
                .as("给的参数应当覆盖全部必填槽位，否则后面的用例分不清是缺参数还是别的原因被拦")
                .containsAll(card.requiredSlots());
    }

    /**
     * R2 没带确认凭据，必须拦。
     *
     * <p>「未确认就执行」是本系统最不能接受的一类缺陷。中控的调用顺序本已保证这一点，
     * 护栏这里再查一次是刻意的冗余——单点保证在这种后果上不够。
     */
    @Test
    @DisplayName("R2 缺确认凭据必须不通过")
    void r2WithoutConfirmationIsRejected() {
        GuardrailCheck check = guardrail().check(
                task(r2Card(), completeParameters(), Map.of()), r2Card());
        assertThat(check).isNotNull();
        assertThat(check.isPassed())
                .as("R2 是要动钱的那一档，没有用户确认就放行等于替用户做了决定")
                .isFalse();
    }

    @Test
    @DisplayName("缺必填槽位必须不通过")
    void missingRequiredSlotIsRejected() {
        CapabilityCard card = r2Card();
        String dropped = card.requiredSlots().getFirst();
        Map<String, Object> incomplete = new java.util.LinkedHashMap<>(completeParameters());
        incomplete.remove(dropped);

        GuardrailCheck check = guardrail().check(
                task(card, incomplete, Map.of("confirmed", true)), card);
        assertThat(check).isNotNull();
        assertThat(check.isPassed())
                .as("必填槽位 " + dropped + " 缺失却放行了。缺参数的执行结果无法预测，"
                        + "而这一步之后就要签发幂等键了")
                .isFalse();
    }

    /**
     * 能力卡为空必须拦。
     *
     * <p>卡为空意味着这个能力在注册中心里查不到——可能是刚下线、可能是资产没同步。
     * 此时护栏对这次调用一无所知：不知道风险等级、不知道必填什么、不知道限额多少。
     * 一无所知时的正确答案是拒绝，而不是「没查到限制所以放行」。
     */
    @Test
    @DisplayName("能力卡为 null 必须不通过，且不抛异常")
    void nullCardIsRejected() {
        GuardrailCheck check = guardrail().check(
                task(r2Card(), completeParameters(), Map.of("confirmed", true)), null);
        assertThat(check).isNotNull();
        assertThat(check.isPassed())
                .as("查不到能力卡时护栏对这次调用一无所知，一无所知的默认答案是拒绝")
                .isFalse();
    }

    @Test
    @DisplayName("判定是纯函数：同样的入参问两次，答案一样")
    void checkIsDeterministic() {
        UnifiedTask task = task(r2Card(), completeParameters(), Map.of("confirmed", true));
        GuardrailHook hook = guardrail();
        GuardrailCheck first = hook.check(task, r2Card());
        GuardrailCheck second = hook.check(task, r2Card());
        assertThat(second.status())
                .as("同一笔请求两次判定结果不同，事后无法复盘「当时为什么放行」。"
                        + "若判定依赖实时风控评分，请在实现内固定住这次调用的取值")
                .isEqualTo(first.status());
    }

    @Test
    @DisplayName("check 不抛异常")
    void checkDoesNotThrow() {
        // 抛异常会让中控走通用异常分支，丢掉护栏本可以给出的 reason code，
        // 面客那层就只剩一句通用兜底话术，客服也查不出到底为什么被拦
        assertThatCode(() -> guardrail().check(
                task(r2Card(), completeParameters(), Map.of("confirmed", true)), r2Card()))
                .doesNotThrowAnyException();
    }

    private static UnifiedTask task(CapabilityCard card, Map<String, Object> parameters,
                                    Map<String, Object> confirmation) {
        return new UnifiedTask(
                "tck-" + UUID.randomUUID(),
                "tck-trace-" + UUID.randomUUID(),
                Enums.TaskSource.FAST_PATH,
                "TCK 契约用例",
                card == null ? "cap.tck.unknown" : card.capabilityId(),
                parameters,
                card == null ? RiskLevel.R2 : card.riskLevel(),
                confirmation,
                // 护栏是在草稿态被调用的，此时还没有判定结果，也还没有幂等键
                GuardrailCheck.pending(),
                null,
                List.of(),
                null);
    }
}
