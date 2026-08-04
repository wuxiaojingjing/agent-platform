package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/**
 * 标准答案出口的固定约定（FP-1I）。
 *
 * <p>快路径产出答案、回复层渲染答案，两侧要对上模板键与槽位名。放在契约模块里是因为
 * 这几个字符串是**跨层约定**：写死在任一侧，另一侧改了名字不会有任何编译错误，
 * 只会在运行时渲染出一句空话。
 */
@Api
public final class StandardAnswer {

    /** 标准答案的渲染模板。模板体只有一个变量，真正的措辞在资产里。 */
    public static final String TEMPLATE_KEY = "tpl.answer.standard";

    /** 标准答案正文。 */
    public static final String SLOT_ANSWER = "answer";

    /** 可选动作入口的显示文案。 */
    public static final String SLOT_ACTION_LABEL = "actionLabel";

    /** 可选动作入口指向的能力。前端据此决定点了之后跳哪。 */
    public static final String SLOT_ACTION_CAPABILITY = "actionCapabilityId";

    private StandardAnswer() {
    }
}
