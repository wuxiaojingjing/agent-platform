package com.huawei.finance.fastpath.arbitration;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.TaskShape;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.FusionConfig;
import com.huawei.finance.fastpath.policy.ProductComparisonPolicyGate;
import com.huawei.finance.fastpath.recall.ProductComparisonGrounder;
import java.util.LinkedHashSet;
import java.util.List;
import com.huawei.finance.intent.ComparePlans;

/**
 * 规则仲裁。
 *
 * <p>它有两个身份：模型仲裁不可用或输出非法时的**回退路径**（v0.7 §3.3），
 * 以及模型出口的**复核基准**。两个身份共用一套判定表，保证「有没有模型」得到的
 * 安全边界是一致的——模型只能让结果更准，不能让结果更冒险。
 *
 * <p>判定顺序即安全顺序：多任务与跨域排在槽位检查之前。一句话里有两件事时，
 * 追问其中一件的参数是答非所问，会把用户带进错误的对话分支。
 */
public class RuleArbitrator {

    private final AssetBundle bundle;
    private final SlotGate slotGate;
    private final ProductComparisonPolicyGate productComparisonPolicyGate;
    private final ProductComparisonGrounder productComparisonGrounder;

    public RuleArbitrator(AssetBundle bundle, SlotGate slotGate) {
        this.bundle = bundle;
        this.slotGate = slotGate;
        this.productComparisonPolicyGate = new ProductComparisonPolicyGate(bundle);
        this.productComparisonGrounder = new ProductComparisonGrounder(bundle);
    }

    public RouteDecision arbitrate(ArbitrationInput input, ShortCircuitLevel level,
                                         boolean fallbackFromModel) {
        FusionConfig.Thresholds thresholds = bundle.fusion().getThresholds();
        int maxClarifyRounds = bundle.fusion().getClarify().getMaxRounds();
        RecallResult recall = input.recall().result();

        RouteDecision.Builder builder = RouteDecision.builder()
                .configVersion(bundle.assetVersion())
                .promptVersion(fallbackFromModel ? bundle.arbitrationSkill().getVersion() : "none")
                .shortCircuit(level);

        ProductComparisonPolicyGate.Outcome comparison = productComparisonGrounder
                .ground(input.normalizedQuery()).resolvedRequest()
                .map(productComparisonPolicyGate::evaluate).orElse(null);
        if (comparison != null && comparison.incompatible()) {
            return builder.decision(bundle.productComparisonPolicy().getOnIncompatible().decision())
                    .taskShape(TaskShape.AMBIGUOUS_GOAL)
                    .reasonCode(bundle.productComparisonPolicy().getOnIncompatible().reasonCode())
                    .confidence(1.0)
                    .evidenceRefs(List.of(
                            "product:" + comparison.left().entityId() + ":" + comparison.left().productType(),
                            "product:" + comparison.right().entityId() + ":" + comparison.right().productType(),
                            "policy:" + bundle.productComparisonPolicy().getVersion()))
                    .build();
        }

        RecallResult.Candidate top1 = input.recall().top1();

        // 无候选：说明没听懂。此时既没有可执行的能力，也没有可追问的槽位——
        // 追问「您要办什么」等于没听懂还硬撑，不如直接交给人工。
        if (top1 == null) {
            return builder.decision(Decision.HANDOFF)
                    .reasonCode(ReasonCode.NO_CANDIDATE)
                    .confidence(0.0)
                    .build();
        }

        double top1Score = input.recall().top1Score();
        double margin = input.recall().margin();

        if (input.recall().multiTask().multiTask()) {
            boolean resolved = input.recall().intentPlan() != null && input.recall().intentPlan().fullyResolved();
            boolean conditional = resolved && input.recall().intentPlan().hasConditional();
            return builder.decision(resolved ? Decision.STATIC_PLAN : Decision.CLARIFY)
                    .taskShape(resolved ? (conditional ? TaskShape.CONDITIONAL_PLAN : TaskShape.FIXED_MULTI_STEP)
                            : TaskShape.AMBIGUOUS_GOAL)
                    .reasonCode(conditional ? ReasonCode.RESULT_RULE : ReasonCode.MULTI_INTENT)
                    .confidence(top1Score)
                    .candidateIds(resolved ? List.of() : clarifyCandidates(recall))
                    .evidenceRefs(List.of(input.recall().multiTask().evidence()))
                    .build();
        }

        if (recall.domainRouting().routingMode() == Enums.RoutingMode.MULTI) {
            boolean resolved = input.recall().intentPlan() != null && input.recall().intentPlan().fullyResolved();
            boolean conditional = resolved && input.recall().intentPlan().hasConditional();
            if (resolved && ComparePlans.isComparePlan(input.recall().intentPlan())) {
                List<String> productQueries = input.recall().intentPlan().items().stream()
                        .map(item -> item.capabilityId()).toList();
                return builder.decision(Decision.START_LOOP)
                        .taskShape(TaskShape.OBSERVATION_DRIVEN)
                        .reasonCode(ReasonCode.AFTER_OBSERVATION)
                        .confidence(top1Score)
                        .candidateIds(productQueries)
                        .evidenceRefs(List.of("product-catalog:cross-agent-compare"))
                        .build();
            }
            return builder.decision(resolved ? Decision.STATIC_PLAN : Decision.CLARIFY)
                    .taskShape(resolved ? (conditional ? TaskShape.CONDITIONAL_PLAN : TaskShape.FIXED_MULTI_STEP)
                            : TaskShape.AMBIGUOUS_GOAL)
                    .reasonCode(conditional ? ReasonCode.RESULT_RULE : ReasonCode.CROSS_DOMAIN)
                    .confidence(top1Score)
                    .candidateIds(resolved ? List.of() : clarifyCandidates(recall))
                    .build();
        }

        // Top1 不达标或与 Top2 拉不开差距，都属于「证据不足以支撑执行」。
        // 附录 B 基线原因码里 LOW_MARGIN 最贴近，不为此新增码——原因码发散会让看板失去可比性。
        if (top1Score < thresholds.getTop1Min() || margin < thresholds.getMarginMin()) {
            return builder.decision(Decision.CLARIFY)
                    .taskShape(TaskShape.AMBIGUOUS_GOAL)
                    .reasonCode(ReasonCode.LOW_MARGIN)
                    .confidence(top1Score)
                    .candidateIds(clarifyCandidates(recall))
                    .build();
        }

        List<String> missing = slotGate.missingSlots(bundle.capability(top1.candidateId()), input.filledSlots());
        if (!missing.isEmpty()) {
            // 澄清已到上限仍不收敛：A 线一律转人工，不得再降为 CLARIFY，
            // 否则「问 → 没听懂 → 再问」会形成死循环（v0.7 §3.3 降级映射第 1 条）
            if (input.clarifyRounds() >= maxClarifyRounds) {
                return builder.decision(Decision.HANDOFF)
                        .reasonCode(ReasonCode.CLARIFY_EXHAUSTED)
                        .confidence(top1Score)
                        .candidateIds(List.of(top1.candidateId()))
                        .build();
            }
            return builder.decision(Decision.CLARIFY)
                    .taskShape(TaskShape.SINGLE_ACTION)
                    .reasonCode(ReasonCode.MISSING_SLOT)
                    .confidence(top1Score)
                    .candidateIds(List.of(top1.candidateId()))
                    .missingSlots(missing)
                    .build();
        }

        // 可执行能力仍需按能力卡的确认策略进入 Review 或显式确认。
        // 中控据此进入 CONFIRM_PENDING，禁止因高置信静默执行（v0.7 §3.3 末行）
        var card = bundle.capability(top1.candidateId());
        ReasonCode reason = card != null && card.confirmationPolicy() == ConfirmationPolicy.REVIEW_ONLY
                ? ReasonCode.REVIEW_REQUIRED
                : card != null && card.confirmationPolicy() == ConfirmationPolicy.EXPLICIT
                        ? ReasonCode.CONFIRMATION_REQUIRED : ReasonCode.HIGH_CONFIDENCE;

        return builder.decision(directDecision(top1))
                .taskShape(TaskShape.SINGLE_ACTION)
                .reasonCode(reason)
                .confidence(top1Score)
                .candidateIds(List.of(top1.candidateId()))
                .evidenceRefs(top1.matchedEvidence())
                .build();
    }

    private static Decision directDecision(RecallResult.Candidate candidate) {
        if (candidate.candidateType() == Enums.CandidateType.AGENT) return Decision.DELEGATE_GOAL;
        if (candidate.candidateType() == Enums.CandidateType.WORKFLOW) return Decision.START_WORKFLOW;
        if (candidate.candidateId().startsWith("cap.nav.")) return Decision.NAVIGATION;
        return Decision.EXECUTE_CAPABILITY;
    }

    private static List<String> clarifyCandidates(RecallResult recall) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        recall.candidates().stream().limit(3).forEach(candidate -> ids.add(candidate.candidateId()));
        return List.copyOf(ids);
    }
}
