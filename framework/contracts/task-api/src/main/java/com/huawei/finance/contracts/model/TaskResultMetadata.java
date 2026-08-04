package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/** 跨 Runtime、A2A 和回复层传递的标准任务结果元数据键。 */
@Api
public final class TaskResultMetadata {

    /** GOAL 在目标 Agent 内最终识别并执行的叶子能力。 */
    public static final String TARGET_CAPABILITY_ID = "targetCapabilityId";

    private TaskResultMetadata() {
    }
}
