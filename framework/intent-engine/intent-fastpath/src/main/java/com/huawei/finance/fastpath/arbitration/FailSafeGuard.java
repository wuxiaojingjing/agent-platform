package com.huawei.finance.fastpath.arbitration;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.intent.ComparePlans;
import com.huawei.finance.fastpath.policy.ProductComparisonPolicyGate;
import com.huawei.finance.fastpath.recall.ProductComparisonGrounder;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型出口的安全复核（v0.7 §3.3 fail-safe：宁可降级，不可误执行）。
 *
 * <p>模型的两类误判代价完全不对称。「简单误判为复杂」只是多走一次慢路径，用户等久一点；
 * 「复杂误判为简单」会真的把钱转出去。所以这里只做单向收紧：能把出口往更保守的方向改，
 * 不能把 STATIC_PLAN 或 START_LOOP 提升成直接能力执行。
 */
public class FailSafeGuard {

    private static final Logger log = LoggerFactory.getLogger(FailSafeGuard.class);

    private final AssetBundle bundle;
    private final SlotGate slotGate;
    private final ProductComparisonPolicyGate productComparisonPolicyGate;
    private final ProductComparisonGrounder productComparisonGrounder;

    public FailSafeGuard(AssetBundle bundle, SlotGate slotGate) {
        this.bundle = bundle;
        this.slotGate = slotGate;
        this.productComparisonPolicyGate = new ProductComparisonPolicyGate(bundle);
        this.productComparisonGrounder = new ProductComparisonGrounder(bundle);
    }

    /**
     * @return 收紧后的出口；返回空表示模型结论无法安全使用，调用方须回退规则仲裁
     */
    public Optional<RouteDecision> tighten(RouteDecision model, ArbitrationInput input) {
        RouteDecision.Builder builder = RouteDecision.builder()
                .decision(model.decision())
                .candidateIds(model.candidateIds())
                .taskShape(model.taskShape())
                .intentPlan(model.intentPlan())
                .confidence(model.confidence())
                .reasonCode(model.reasonCode())
                .missingSlots(model.missingSlots())
                .evidenceRefs(model.evidenceRefs())
                .modelVersion(model.modelVersion())
                .promptVersion(model.promptVersion())
                .configVersion(model.configVersion())
                .shortCircuit(model.shortCircuit());

        int maxClarifyRounds = bundle.fusion().getClarify().getMaxRounds();

        // A model may recognize the comparison intent, but it cannot loosen a published
        // comparability rule. Returning empty delegates construction of the configured CLARIFY
        // result to RuleArbitrator, including the stable policy evidence.
        if (productComparisonGrounder.ground(input.normalizedQuery()).resolvedRequest()
                .map(productComparisonPolicyGate::evaluate)
                .map(ProductComparisonPolicyGate.Outcome::incompatible)
                .orElse(false)) {
            return Optional.empty();
        }

        // 规则层检出多任务而模型未按多意图收口：以规则为准。
        // 不只拦直接执行：模型给出没有可执行计划的低置信结论时同样无法推进多意图任务，
        // 续办整条链都会断（场景 3）
        if (input.recall().multiTask().multiTask()
                && model.decision() != Decision.HANDOFF
                && !(model.decision() == Decision.STATIC_PLAN
                        && model.intentPlan() != null && model.intentPlan().fullyResolved())) {
            log.info("多任务信号与模型出口冲突，按多任务收紧 model={} reason={} evidence={}",
                    model.decision(), model.reasonCode(), input.recall().multiTask().evidence());
            // 不在这里伪造 Static Plan。规则仲裁持有真实 IntentPlan 和澄清候选，
            // 回退给它才能在计划未闭合时返回可选择的 CLARIFY。
            return Optional.empty();
        }

        // 跨域（含产品对比）同理：规则路由已是 MULTI，模型却直出单能力执行——
        // 那是「复杂误判为简单」，比多走一次慢路径贵得多（场景 4）
        if (input.recall().result().domainRouting().routingMode() == Enums.RoutingMode.MULTI
                && !input.recall().multiTask().multiTask()
                && !hasResolvedContextDependency(input)
                && model.decision() != Decision.HANDOFF
                && !(model.decision() == Decision.STATIC_PLAN
                        && model.intentPlan() != null && model.intentPlan().fullyResolved())
                && !(model.decision() == Decision.START_LOOP
                        && (model.taskShape() == com.huawei.finance.contracts.model.TaskShape.OBSERVATION_DRIVEN
                            || model.taskShape() == com.huawei.finance.contracts.model.TaskShape.OPEN_ENDED_DIAGNOSIS))) {
            log.info("跨域路由与模型出口冲突，按跨域收紧 model={} reason={}",
                    model.decision(), model.reasonCode());
            // RoutingMode.MULTI 可能只是多个领域候选难分伯仲，并不等于已经得到完整计划。
            // 让规则仲裁依据 IntentPlan 完整性选择 STATIC_PLAN 或带候选的 CLARIFY。
            return Optional.empty();
        }

        if (ComparePlans.isComparePlan(input.recall().intentPlan())
                && model.decision() != Decision.HANDOFF
                && !(model.decision() == Decision.START_LOOP
                        && model.taskShape() == com.huawei.finance.contracts.model.TaskShape.OBSERVATION_DRIVEN)) {
            log.info("跨领域产品比较必须等待查询 Observation，拒绝模型降级为固定直出 model={}",
                    model.decision());
            return Optional.empty();
        }

        if (model.decision() == Decision.EXECUTE_CAPABILITY) {
            String capabilityId = model.selectedCandidateId();
            CapabilityCard card = bundle.capability(capabilityId);
            if (card == null) {
                return Optional.empty();
            }

            List<String> missing = slotGate.missingSlots(card, input.filledSlots());
            if (!missing.isEmpty()) {
                if (input.clarifyRounds() >= maxClarifyRounds) {
                    return Optional.of(builder
                            .decision(Decision.HANDOFF)
                            .reasonCode(ReasonCode.CLARIFY_EXHAUSTED)
                            .missingSlots(missing)
                            .build());
                }
                return Optional.of(builder
                        .decision(Decision.CLARIFY)
                        .reasonCode(ReasonCode.MISSING_SLOT)
                        .missingSlots(missing)
                        .build());
            }

            // ConfirmationPolicy is validated when the card is loaded: R2 cannot be relaxed,
            // while R1 may explicitly choose REVIEW_ONLY. The model cannot override either case.
            if (card.confirmationPolicy() == ConfirmationPolicy.EXPLICIT)
                return Optional.of(builder.reasonCode(ReasonCode.CONFIRMATION_REQUIRED).build());
            if (card.confirmationPolicy() == ConfirmationPolicy.REVIEW_ONLY)
                return Optional.of(builder.reasonCode(ReasonCode.REVIEW_REQUIRED).build());
            return Optional.of(builder.build());
        }

        if (model.decision() == Decision.CLARIFY) {
            if (input.clarifyRounds() >= maxClarifyRounds) {
                return Optional.of(builder
                        .decision(Decision.HANDOFF)
                        .reasonCode(ReasonCode.CLARIFY_EXHAUSTED)
                        .build());
            }
            if (model.missingSlots().isEmpty()) {
                // 要澄清却说不出缺什么，回复层无从生成问题，只能回退规则仲裁
                log.warn("模型给出 CLARIFY 但未指明缺失槽位，回退规则仲裁");
                return Optional.empty();
            }
        }

        return Optional.of(builder.build());
    }

    /**
     * Account/card evidence used to resolve a parameter dependency is not a second user goal. An
     * explicit multi-task signal is handled before this method, so this exemption cannot collapse
     * a real "query, then transfer" request into one action.
     */
    private static boolean hasResolvedContextDependency(ArbitrationInput input) {
        var contextual = input.contextualQuery();
        if (contextual == null || !contextual.consumedContext()) return false;
        return contextual.resolutions().stream()
                .filter(item -> "ORDINAL_REFERENCE".equals(item.resolutionType())
                        || "REQUERY_THEN_HALF".equals(item.resolutionType()))
                .anyMatch(item -> contextual.usedContextRefs().contains(item.contextRef()));
    }
}
