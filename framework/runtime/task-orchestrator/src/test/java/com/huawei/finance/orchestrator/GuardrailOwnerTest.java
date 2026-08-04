package com.huawei.finance.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.orchestrator.guardrail.GuardrailProperties;
import com.huawei.finance.orchestrator.guardrail.PolicyGuardrail;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 能力卡 {@code guardrailOwner}（§2.7.4 预留机制之一）。
 *
 * <p>S2 阶段**只留位不启用**，因此这一组用例的重点在最后一条：护栏行为不随这个字段变化。
 * 写它的理由是这类留位字段有一种典型的烂尾方式——加了字段、写了文档、没人消费，
 * 半年后有人以为它早就生效了。§7 变更规则第 5 条要求的「消费点存在」证据，
 * 在留位阶段的正确形态是反过来的：**明确断言现在没有消费点**。
 */
class GuardrailOwnerTest {

    @Test
    @DisplayName("未声明时缺省归领域方，不是缺省归主控")
    void defaultsToDomain() {
        assertThat(card(null).guardrailOwner()).isEqualTo(Enums.GuardrailOwner.DOMAIN);
    }

    @Test
    @DisplayName("声明 MAIN 时按声明记，两个取值都能表达")
    void explicitOwnerIsKept() {
        assertThat(card(Enums.GuardrailOwner.MAIN).guardrailOwner()).isEqualTo(Enums.GuardrailOwner.MAIN);
        assertThat(card(Enums.GuardrailOwner.DOMAIN).guardrailOwner()).isEqualTo(Enums.GuardrailOwner.DOMAIN);
    }

    @Test
    @DisplayName("与提参归属是两件事，不得互相推导")
    void independentFromSlotOwner() {
        // 领域方自己收参（requiredSlots 为空 → SlotOwner.AGENT），护栏却声明在主控。
        // 这个组合必须是合法的：谁问参数与谁在执行前拦，本来就可以分开
        CapabilityCard noSlotsButMainGuardrail = new CapabilityCard("cap.fund.purchase", "基金申购",
                Enums.CapabilityType.TOOL, Enums.Granularity.TOOL, "agent.wealth", List.of("wealth"),
                "申购基金", List.of(), Map.of(), Map.of(), List.of(), List.of("资金划转"),
                RiskLevel.R2, 8000, Enums.Idempotency.REQUIRED, "理财领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.MAIN);

        assertThat(noSlotsButMainGuardrail.slotOwner()).isEqualTo(Enums.SlotOwner.AGENT);
        assertThat(noSlotsButMainGuardrail.guardrailOwner()).isEqualTo(Enums.GuardrailOwner.MAIN);
    }

    /**
     * 留位不启用的判定。
     *
     * <p>现有护栏对所有能力一律校验，不读这个字段。启用它是 S3 的事，且要与 §6 阻断项 2、3
     * （R 分级口径、权威数据源）一起做——在主控还拿不到限额与适当性口径的时候，
     * 让一部分能力「按声明跳过主控护栏」只是把桩换成了空白。
     */
    @Test
    @DisplayName("留位不启用：声明 DOMAIN 也不会让主控护栏放行")
    void declaringDomainDoesNotDisableTheGuardrailYet() {
        PolicyGuardrail guardrail = new PolicyGuardrail(new GuardrailProperties());

        GuardrailCheck asDomain = guardrail.check(draft(), card(Enums.GuardrailOwner.DOMAIN));
        GuardrailCheck asMain = guardrail.check(draft(), card(Enums.GuardrailOwner.MAIN));

        // R2 缺确认凭据，两种声明下都拒
        assertThat(asDomain.isPassed()).isFalse();
        assertThat(asMain.isPassed()).isFalse();
        assertThat(asDomain.codes()).isEqualTo(asMain.codes());
    }

    private static CapabilityCard card(Enums.GuardrailOwner owner) {
        return new CapabilityCard("cap.transfer", "转账", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.payment", List.of("payment"), "向指定收款人转账",
                List.of("转账"), Map.of(), Map.of(), List.of("已登录"), List.of("资金划转"),
                RiskLevel.R2, 8000, Enums.Idempotency.REQUIRED, "支付领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of("我要转账"), List.of("转账"),
                List.of("payee", "amount"), owner);
    }

    private static UnifiedTask draft() {
        return new UnifiedTask("task-1", "trace-1", Enums.TaskSource.FAST_PATH, "给张三转 1000",
                "cap.transfer", Map.of("payee", "张三", "amount", "1000"), RiskLevel.R2, Map.of(),
                GuardrailCheck.pending(), null, List.of(), Instant.now().plusSeconds(30));
    }
}
