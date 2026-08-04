package com.huawei.finance.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.orchestrator.guardrail.GuardrailProperties;
import com.huawei.finance.orchestrator.guardrail.PolicyGuardrail;
import com.huawei.finance.orchestrator.idempotency.IdempotencyKeys;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 护栏与幂等键的先后顺序（实施架构 §8.4）。 */
class GuardrailIdempotencyTest {

    private static CapabilityCard transferCard() {
        return new CapabilityCard("cap.transfer", "转账", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.payment", List.of("payment"), "向指定收款人转账",
                List.of("转账"), Map.of(), Map.of(), List.of("已登录"), List.of("资金划转"),
                RiskLevel.R2, 8000, Enums.Idempotency.REQUIRED, "支付领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of("我要转账"), List.of("转账"),
                List.of("payee", "amount"), null);
    }

    private static UnifiedTask draft(Map<String, Object> parameters, Map<String, Object> confirmation,
                                    GuardrailCheck guardrail, String idempotencyKey) {
        return new UnifiedTask("task-1", "trace-1", Enums.TaskSource.FAST_PATH, "给张三转 1000",
                "cap.transfer", parameters, RiskLevel.R2, confirmation, guardrail, idempotencyKey,
                List.of(), Instant.now().plusSeconds(30));
    }

    @Test
    @DisplayName("护栏未通过时构造带幂等键的任务直接失败")
    void keyBeforeGuardrailIsRejected() {
        assertThatThrownBy(() -> draft(Map.of("payee", "张三", "amount", "1000"), Map.of(),
                GuardrailCheck.pending(), "idem-xxxx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("§8.4");
    }

    @Test
    @DisplayName("R2 缺确认凭据 → 护栏拒绝")
    void r2WithoutConfirmationIsBlocked() {
        GuardrailCheck check = new PolicyGuardrail(new GuardrailProperties()).check(
                draft(Map.of("payee", "张三", "amount", "1000"), Map.of(), GuardrailCheck.pending(), null),
                transferCard());

        assertThat(check.isPassed()).isFalse();
        assertThat(check.codes()).contains("CONFIRMATION_MISSING");
    }

    @Test
    @DisplayName("超过单笔限额 → 护栏拒绝")
    void overLimitIsBlocked() {
        GuardrailCheck check = new PolicyGuardrail(new GuardrailProperties()).check(
                draft(Map.of("payee", "张三", "amount", "80000"),
                        Map.of("confirmedAt", "now"), GuardrailCheck.pending(), null),
                transferCard());

        assertThat(check.codes()).contains("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("金额解析不了按越限处理：护栏的默认答案是拒绝")
    void unparseableAmountIsBlocked() {
        GuardrailCheck check = new PolicyGuardrail(new GuardrailProperties()).check(
                draft(Map.of("payee", "张三", "amount", "一千"),
                        Map.of("confirmedAt", "now"), GuardrailCheck.pending(), null),
                transferCard());

        assertThat(check.codes()).contains("AMOUNT_UNPARSEABLE");
    }

    @Test
    @DisplayName("参数齐全且已确认 → 护栏通过，此后才允许发凭据")
    void confirmedAndCompletePasses() {
        UnifiedTask task = draft(Map.of("payee", "张三", "amount", "1000"),
                Map.of("confirmedAt", "now"), GuardrailCheck.pending(), null);

        GuardrailCheck check = new PolicyGuardrail(new GuardrailProperties()).check(task, transferCard());
        assertThat(check.isPassed()).isTrue();

        UnifiedTask executable = draft(task.parameters(), task.confirmation(), check,
                IdempotencyKeys.of(task.taskId(), task.capabilityId(), task.parameters()));
        assertThat(executable.executable()).isTrue();
    }

    @Test
    @DisplayName("幂等键确定性：同参数同键，改金额即换键")
    void idempotencyKeyIsDeterministic() {
        Map<String, Object> params = Map.of("payee", "张三", "amount", "1000");
        String first = IdempotencyKeys.of("task-1", "cap.transfer", params);
        String again = IdempotencyKeys.of("task-1", "cap.transfer", Map.of("amount", "1000", "payee", "张三"));

        // 超时重发要算出同一把键，否则重复扣款挡不住；Map 顺序不同不影响结果
        assertThat(first).isEqualTo(again);

        String changed = IdempotencyKeys.of("task-1", "cap.transfer",
                Map.of("payee", "张三", "amount", "2000"));
        // 改了金额是另一笔业务，不该被上一笔的记录挡住
        assertThat(changed).isNotEqualTo(first);
    }
}
