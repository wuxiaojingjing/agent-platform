package com.huawei.finance.runtime.loop;

import com.huawei.finance.orchestrator.loop.LoopContracts.Action;

@FunctionalInterface
public interface AgentLoopPlanner {
    Action nextAction(LoopContext context);
}
