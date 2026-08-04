package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Objects;

/**
 * 一句话里拆出来的一件事。
 *
 * <p>{@code capabilityId} 允许为空。拆句子和认能力是两件独立的事，一句「查余额，再给老徐转
 * 1000」完全可能拆得干净却有一半认不出来。让它可空，是为了把「拆不开」和「认不出」在数据上
 * 分开：前者说明切分口径有问题，后者说明能力资产缺话术。合并成「拆不出来就整条丢掉」会让后者
 * 永远查不出来。
 *
 * @param order        在原句中的次序，从 0 开始
 * @param text         原句中属于这件事的片段，保留用户的原话
 * @param capabilityId 匹配到的能力，认不出时为 null
 * @param summary      给用户看的一句话。有能力卡时用卡的显示名，否则退回片段原文
 * @param relation     与前序子意图的关系
 * @param condition    条件表述原文（如「不足就别转」）。仅 {@code CONDITIONAL} 时非空
 * @param resolution   规则候选强度与 Planner 可选范围
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record SubIntent(
        int order,
        String text,
        String capabilityId,
        String summary,
        Enums.IntentRelation relation,
        String condition,
        PlanResolution resolution,
        String stepId,
        List<String> dependsOn,
        PlanCondition planCondition) {

    public SubIntent {
        if (order < 0) {
            throw new IllegalArgumentException("子意图次序不得为负：" + order);
        }
        if (relation == Enums.IntentRelation.CONDITIONAL && (condition == null || condition.isBlank())) {
            throw new IllegalArgumentException(
                    "标成条件依赖却说不出条件是什么，下游无从判断该不该执行：order=" + order);
        }
        if (relation != Enums.IntentRelation.CONDITIONAL && condition != null) {
            throw new IllegalArgumentException(
                    "带了条件却没标成条件依赖，这个条件会被下游静默忽略：order=" + order);
        }
        if (order == 0 && relation != Enums.IntentRelation.PARALLEL) {
            throw new IllegalArgumentException(
                    "第一件事没有前序可依赖，不能标成 " + relation);
        }
        Objects.requireNonNull(resolution, "子意图必须显式携带规划分级和候选证据");
        stepId = stepId == null || stepId.isBlank() ? "step-" + (order + 1) : stepId;
        dependsOn = dependsOn == null
                ? defaultDependencies(order, relation)
                : List.copyOf(dependsOn);
        if (planCondition == null && condition != null) {
            planCondition = PlanCondition.deferred(condition);
        } else if (planCondition != null && condition == null) {
            condition = planCondition.originalText();
        }
    }

    /** Source-compatible constructor for plans created before explicit step dependencies. */
    public SubIntent(int order, String text, String capabilityId, String summary,
                     Enums.IntentRelation relation, String condition, PlanResolution resolution) {
        this(order, text, capabilityId, summary, relation, condition, resolution,
                "step-" + (order + 1), defaultDependencies(order, relation),
                condition == null ? null : PlanCondition.deferred(condition));
    }

    private static List<String> defaultDependencies(int order, Enums.IntentRelation relation) {
        return order == 0 || relation == Enums.IntentRelation.PARALLEL
                ? List.of() : List.of("step-" + order);
    }

    /** 是否认出了承接这件事的能力。 */
    public boolean resolved() {
        return capabilityId != null;
    }

    /** 是否必须等前一件事做完。并行的可以同时下发，其余的不行。 */
    public boolean waitsForPredecessor() {
        return relation != Enums.IntentRelation.PARALLEL;
    }
}
