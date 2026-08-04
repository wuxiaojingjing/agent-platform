package com.huawei.finance.orchestrator.plan;

import com.huawei.finance.stability.Api;

/** 多意图计划的生命周期。 */
@Api
public enum PlanState {

    /** 在办。同一会话只允许一份，由唯一索引 {@code agent_intent_plan_active_per_session} 保证。 */
    IN_PROGRESS,
    WAITING_USER,
    WAITING_REVIEW,
    WAITING_CONFIRMATION,

    /** 每一件都走到终态。 */
    COMPLETED,

    /**
     * 中途作废。
     *
     * <p>用户改口去问别的、或者显式取消都归这里。与 {@link #COMPLETED} 分开是因为
     * 「没办完」和「办完了」在复盘时是两个完全不同的问题：前者要看用户为什么放弃。
     */
    ABANDONED,
    FAILED,
    CANCELLED
}
