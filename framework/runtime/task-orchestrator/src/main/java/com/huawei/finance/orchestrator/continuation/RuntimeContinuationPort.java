package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.stability.Spi;

@Spi
public interface RuntimeContinuationPort {
    RuntimeType runtimeType();
    Snapshot describe(String tenantId, String agentId, String runtimeRef);
    default Snapshot resume(String tenantId, String agentId, String runtimeRef,
                            Resolution resolution, long expectedStateVersion) {
        return describe(tenantId, agentId, runtimeRef);
    }
}
