package com.huawei.finance.orchestrator.task;

import com.huawei.finance.stability.Api;

/**
 * 任务生命周期状态。
 *
 * <p>与 {@code TaskResult.status} 是两回事：那个描述「这次执行的结局」，这个描述
 * 「任务现在停在哪」。合成一个枚举会立刻遇到 CONFIRM_PENDING 这类无法表达为执行结局的状态。
 */
@Api
public enum TaskState {

    /** 已建档，尚未进入执行或等待。 */
    CREATED,

    /** 等用户补充槽位。 */
    CLARIFY_PENDING,

    /** 等用户复核 R1 参数摘要。此状态下不得存在幂等键。 */
    REVIEW_PENDING,

    /** 等用户对 R2 动作显式确认。此状态下不得存在幂等键。 */
    CONFIRM_PENDING,

    /** 护栏拒绝，终态。 */
    GUARDRAIL_BLOCKED,

    /** 已交领域 Agent 执行。 */
    RUNNING,

    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == GUARDRAIL_BLOCKED;
    }

    public boolean active() {
        return !terminal();
    }
}
