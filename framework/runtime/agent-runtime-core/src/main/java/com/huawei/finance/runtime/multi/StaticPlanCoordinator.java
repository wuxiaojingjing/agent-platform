package com.huawei.finance.runtime.multi;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.orchestrator.OrchestrationOutcome;
import com.huawei.finance.registry.asset.AssetBundle;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 固定能力集合的静态计划执行器；运行期不得追加能力。 */
public final class StaticPlanCoordinator {
    private final SlowPathExecutionCoordinator delegate;

    public StaticPlanCoordinator(SlowPathExecutionCoordinator delegate) {
        this.delegate = delegate;
    }

    public Optional<OrchestrationOutcome> execute(
            RequestContext context, IntentPlan plan, AssetBundle assets,
            Map<String, Object> parameters, ContextLease lease, Instant deadline,
            Enums.InvocationOrigin origin) {
        return execute(context, plan, assets, parameters, lease, deadline,
                origin, false, false, null);
    }

    public Optional<OrchestrationOutcome> execute(
            RequestContext context, IntentPlan plan, AssetBundle assets,
            Map<String, Object> parameters, ContextLease lease, Instant deadline,
            Enums.InvocationOrigin origin, boolean confirmed, boolean cancelled,
            Long expectedPlanVersion) {
        if (plan == null || !plan.fullyResolved()) return Optional.empty();
        return delegate.execute(context, plan, assets, parameters, lease, deadline, origin,
                confirmed, cancelled, expectedPlanVersion);
    }
}
