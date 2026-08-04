package com.huawei.finance.sample.workflow;

import com.huawei.finance.stability.Api;

/**
 * 叶子操作显式声明这次失败该归成哪一类。
 *
 * <p>操作抛普通异常时，归类按流程声明里的 {@code onError} 走；抛本异常则以本异常为准。
 * 存在这条覆盖通道，是因为同一个操作的失败可能不同类：提交划转返回「余额不足」是 FATAL，
 * 返回「系统忙」是 RETRYABLE，而声明只能写一个默认值。
 */
@Api
public class StepFailure extends RuntimeException {

    private final FlowSpec.OnError classification;

    public StepFailure(FlowSpec.OnError classification, String message) {
        this(classification, message, null);
    }

    public StepFailure(FlowSpec.OnError classification, String message, Throwable cause) {
        super(message, cause);
        this.classification = classification == null ? FlowSpec.OnError.FATAL : classification;
    }

    public FlowSpec.OnError classification() {
        return classification;
    }

    /** 缺信息，要回去问用户。 */
    public static StepFailure needUser(String message) {
        return new StepFailure(FlowSpec.OnError.NEED_USER, message);
    }

    /** 下游临时不可用，原样重放有意义。 */
    public static StepFailure retryable(String message) {
        return new StepFailure(FlowSpec.OnError.RETRYABLE, message);
    }

    /** 重放也不会变的失败。 */
    public static StepFailure fatal(String message) {
        return new StepFailure(FlowSpec.OnError.FATAL, message);
    }
}
