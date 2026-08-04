package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 护栏检查结果，{@code UnifiedTask} 与 {@code TaskResult} 共用（v0.7 附录 B）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record GuardrailCheck(Enums.GuardrailStatus status, List<String> codes) {

    public GuardrailCheck {
        status = status == null ? Enums.GuardrailStatus.PENDING : status;
        codes = codes == null ? List.of() : List.copyOf(codes);
    }

    public static GuardrailCheck pending() {
        return new GuardrailCheck(Enums.GuardrailStatus.PENDING, List.of());
    }

    public static GuardrailCheck passed() {
        return new GuardrailCheck(Enums.GuardrailStatus.PASSED, List.of());
    }

    public static GuardrailCheck failed(List<String> codes) {
        return new GuardrailCheck(Enums.GuardrailStatus.FAILED, codes);
    }

    public boolean isPassed() {
        return status == Enums.GuardrailStatus.PASSED;
    }
}
