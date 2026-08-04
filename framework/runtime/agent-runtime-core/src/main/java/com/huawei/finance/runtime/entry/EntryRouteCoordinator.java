package com.huawei.finance.runtime.entry;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.intent.IntentResult;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts;
import com.huawei.finance.orchestrator.continuation.ContinuationCoordinator;
import java.util.Optional;

public class EntryRouteCoordinator {
    private final IntentEvidenceBuilder evidenceBuilder;
    private final DeterministicEntryRules rules;
    private final LoopEntryPolicyGate policy;
    private final MeterRegistry meters;
    private final Optional<ContinuationCoordinator> continuation;
    public EntryRouteCoordinator(IntentEvidenceBuilder evidenceBuilder,DeterministicEntryRules rules,LoopEntryPolicyGate policy){
        this(evidenceBuilder, rules, policy, null, Optional.empty());}
    public EntryRouteCoordinator(IntentEvidenceBuilder evidenceBuilder, DeterministicEntryRules rules,
                                 LoopEntryPolicyGate policy, MeterRegistry meters) {
        this(evidenceBuilder, rules, policy, meters, Optional.empty());
    }
    public EntryRouteCoordinator(IntentEvidenceBuilder evidenceBuilder, DeterministicEntryRules rules,
                                 LoopEntryPolicyGate policy, MeterRegistry meters,
                                 Optional<ContinuationCoordinator> continuation) {
        this.evidenceBuilder=evidenceBuilder;this.rules=rules;this.policy=policy;this.meters=meters;
        this.continuation=continuation==null?Optional.empty():continuation;
    }

    /** 完整入口的第一阶段；PendingGoal 的 routeResolvedGoal 不得调用本方法。 */
    public Optional<ContinuationContracts.Decision> routeContinuation(
            String tenantId, String agentId, String sessionId, String input,
            ContinuationContracts.StructuredAction action) {
        return continuation.map(router -> router.decide(tenantId, agentId, sessionId, input, action));
    }

    public Optional<ContinuationContracts.Context> continuationContext(
            String tenantId, String agentId, String sessionId) {
        return continuation.map(router -> router.context(tenantId, agentId, sessionId));
    }

    public Optional<ContinuationContracts.Decision> routeContinuation(
            String tenantId, String agentId, String sessionId, String input,
            ContinuationContracts.StructuredAction action,
            Optional<ContinuationContracts.Context> context) {
        return routeContinuation(tenantId, agentId, sessionId, input, action, context, null);
    }

    public Optional<ContinuationContracts.Decision> routeContinuation(
            String tenantId, String agentId, String sessionId, String input,
            ContinuationContracts.StructuredAction action,
            Optional<ContinuationContracts.Context> context,
            IntentContext intentContext) {
        if (continuation.isEmpty() || context == null || context.isEmpty()) return Optional.empty();
        return Optional.of(continuation.get().decide(
                tenantId, agentId, sessionId, input, action, context.get(), intentContext));
    }

    /** 入口证据已经由 IntentEngine 生成后，完成确定性规则与单向收紧。 */
    public RouteDecision routeResolvedGoal(IntentResult result,AssetBundle assets){
        IntentEvidence evidence=evidenceBuilder.build(result);
        RouteDecision decision=policy.tighten(rules.normalize(evidence,assets),evidence,assets);
        if(meters!=null) meters.counter(AgentMetrics.ENTRY_ROUTE,
                AgentMetrics.TAG_DECISION,decision.decision().name(),AgentMetrics.TAG_REASON_CODE,
                decision.reasonCode()==null?"NONE":decision.reasonCode().name()).increment();
        return decision;
    }
}
