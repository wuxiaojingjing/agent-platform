package com.huawei.finance.runtime.task;

import com.huawei.finance.stability.Api;

/**
 * Agent 产品扩展调用本 Agent 中控的稳定门面。
 *
 * <p>实现必须保留建档、护栏、幂等、执行和结果落盘顺序；业务代码不得直接调用领域执行器。
 */
@Api
@FunctionalInterface
public interface AgentTaskExecutor {

    AgentTaskOutcome execute(AgentTaskRequest request);
}
