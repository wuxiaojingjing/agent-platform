package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

@Api
public enum TaskShape {
    SINGLE_ACTION,
    FIXED_MULTI_STEP,
    CONDITIONAL_PLAN,
    OPEN_ENDED_DIAGNOSIS,
    OBSERVATION_DRIVEN,
    AMBIGUOUS_GOAL,
    UNSUPPORTED_GOAL
}
