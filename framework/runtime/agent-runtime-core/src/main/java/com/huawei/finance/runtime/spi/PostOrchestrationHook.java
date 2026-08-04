package com.huawei.finance.runtime.spi;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.orchestrator.OrchestrationOutcome;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.Map;
import java.util.Optional;

/**
 * 编排完成后、渲染前的产品扩展点（如跨域产品对比）。
 *
 * <p>返回非 empty 时替换渲染槽位。
 *
 * @deprecated 使用 {@link com.huawei.finance.runtime.extension.ResponseEnricher}。本接口暴露实现层的
 *             {@link OrchestrationOutcome} 与 {@link AssetBundle}，也无法形成有序多扩展链。
 */
@FunctionalInterface
@Deprecated(forRemoval = true)
public interface PostOrchestrationHook {

    Optional<Map<String, Object>> enrichRenderSlots(
            RequestContext ctx,
            RouteDecision decision,
            IntentPlan intentPlan,
            AssetBundle bundle,
            OrchestrationOutcome outcome,
            ContextLease lease,
            Map<String, Object> defaultSlots);
}
