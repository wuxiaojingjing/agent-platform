package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/** 入口路由的完整执行去向。 */
@Api
public enum Decision {
    DIRECT_KNOWLEDGE,
    NAVIGATION,
    EXECUTE_CAPABILITY,
    START_WORKFLOW,
    STATIC_PLAN,
    DELEGATE_GOAL,
    START_LOOP,
    RESUME_TASK,
    RESUME_LOOP,
    CLARIFY,
    CANCEL,
    REJECT,
    HANDOFF
}
