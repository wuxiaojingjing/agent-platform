package com.huawei.finance.contracts.validation;

import java.util.List;

/**
 * 校验结论。
 *
 * @param valid    是否通过
 * @param messages 未通过时的具体违规信息，用于打点与排障
 */
public record ValidationOutcome(boolean valid, List<String> messages) {

    public ValidationOutcome {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ValidationOutcome ok() {
        return new ValidationOutcome(true, List.of());
    }

    public String summary() {
        return valid ? "OK" : String.join("; ", messages);
    }

    /** 校验不过就抛，适用于本方产出的契约——自己生成的数据不合自己的 Schema 属于代码缺陷。 */
    public void orThrow(String what) {
        if (!valid) {
            throw new ContractViolationException(what + " 不符合契约：" + summary());
        }
    }
}
