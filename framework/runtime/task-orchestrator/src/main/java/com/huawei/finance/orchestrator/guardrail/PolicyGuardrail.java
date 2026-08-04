package com.huawei.finance.orchestrator.guardrail;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.GuardrailHook;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 护栏桩实现。
 *
 * <p>这四条不是随手挑的示例，它们各自代表真实护栏必然要覆盖的一类：能力可用性、
 * 参数完整性、金额限额、高危动作的确认凭据。真实护栏替换的是判定来源（权限中心、风控），
 * 不是这四类的存在与否。
 *
 * <p>限额从 {@link GuardrailProperties} 读，但那只是让行外联调不必重新编译——
 * 一个全局常数对不上任何一家行的真实风控口径。要落地就实现 {@link GuardrailHook}
 * 整段接管。
 */
public class PolicyGuardrail implements GuardrailHook {

    private final BigDecimal singleTransferLimit;

    public PolicyGuardrail(GuardrailProperties props) {
        this.singleTransferLimit = props.getSingleTransferLimit();
    }

    @Override
    public GuardrailCheck check(UnifiedTask draft, CapabilityCard card) {
        List<String> codes = new ArrayList<>();

        if (card == null) {
            return GuardrailCheck.failed(List.of("CAPABILITY_NOT_FOUND"));
        }
        if (card.status() == Enums.CapabilityStatus.DISABLED) {
            codes.add("CAPABILITY_DISABLED");
        }

        for (String slot : card.requiredSlots()) {
            Object value = draft.parameters().get(slot);
            if (value == null || String.valueOf(value).isBlank()) {
                codes.add("MISSING_SLOT:" + slot);
            }
        }

        Object amount = draft.parameters().get("amount");
        if (amount != null) {
            try {
                if (new BigDecimal(String.valueOf(amount)).compareTo(singleTransferLimit) > 0) {
                    codes.add("AMOUNT_LIMIT_EXCEEDED");
                }
            } catch (NumberFormatException e) {
                // 金额解析不了就当越限：护栏的默认答案是拒绝，不是放行
                codes.add("AMOUNT_UNPARSEABLE");
            }
        }

        // R2 到这一步必须已经带上确认凭据。中控的调用顺序本已保证，
        // 这里再查一次是因为「未确认就执行」是本系统最不能接受的一类缺陷
        if (card.riskLevel().requiresExplicitConfirmation() && draft.confirmation().isEmpty()) {
            codes.add("CONFIRMATION_MISSING");
        }

        return codes.isEmpty() ? GuardrailCheck.passed() : GuardrailCheck.failed(codes);
    }
}
