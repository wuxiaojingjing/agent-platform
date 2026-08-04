package com.huawei.finance.runtime;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;

/** Routes failed platform registration back to the authoritative Runtime store. */
@FunctionalInterface
public interface RuntimeRegistrationCompensator {
    void compensate(RequestContext context, RuntimeType runtimeType, String runtimeRef, String reason);

    RuntimeRegistrationCompensator NOOP = (context, runtimeType, runtimeRef, reason) -> { };
}
