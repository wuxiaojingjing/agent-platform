package com.huawei.finance.common.event;

import com.huawei.finance.stability.Api;

/**
 * 多轮输入事件（v0.7 §3.5 表）。
 *
 * <p>{@link #NEW_TASK} 不在 v0.7 的六类表里，代表「会话无活跃任务」这一前置情形。
 * 表中六类描述的都是「已有任务时来了新输入」，把无任务的情况也归入同一枚举，
 * 调用方就不必再区分 null 与事件两套分支。
 */
@Api
public enum InputEvent {

    /** 补充当前任务信息：更新当前任务后继续。 */
    SUPPLEMENT,

    /** 纠正已确认信息：回滚受影响且未执行的步骤，重新校验并规划。 */
    CORRECTION,

    /** 取消当前任务：终止任务并执行必要补偿。 */
    CANCEL,

    /** 新建并行任务：保持原任务状态，新建任务并隔离作用域。 */
    NEW_PARALLEL_TASK,

    /**
     * 切换话题：挂起原任务及计划版本。
     *
     * <p>只短路「挂起动作」，新话题输入必须按新请求走完整快路径重新路由，
     * 不得让主 Agent 为新话题做意图识别（v0.7 §3.3 原则 2）。
     */
    TOPIC_SWITCH,

    /** 确认指定动作：只对该动作生效，不解释为新的宽泛授权。 */
    CONFIRMATION,

    REVIEW_ACCEPT,
    SWITCH_ACCEPT,
    SWITCH_REJECT,
    RESUME_SUSPENDED,

    /** 会话无活跃任务，本轮为全新请求。 */
    NEW_TASK;

    /**
     * 该事件能否跳过完整多路召回，直接交中控/主 Agent 续跑（v0.7 §3.3 活跃慢任务续轮短路）。
     *
     * <p>{@code TOPIC_SWITCH} 返回 false：它只短路挂起动作，新话题本身仍要走完整快路径。
     */
    public boolean allowsContinuationShortCircuit() {
        return this == SUPPLEMENT || this == CORRECTION || this == CONFIRMATION
                || this == REVIEW_ACCEPT || this == SWITCH_ACCEPT || this == SWITCH_REJECT
                || this == RESUME_SUSPENDED || this == CANCEL;
    }
}
