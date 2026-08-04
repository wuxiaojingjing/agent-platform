package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * 一次多意图请求的拆解结果。
 *
 * <p>存在的理由是：在此之前「多意图」在系统里只是一个布尔值，A 线除了回一句「请您逐项办理」
 * 之外做不了任何事——拆出来的东西没有容身之处，也就传不下去。有了这份计划，话术能说清是哪
 * 几件事，中控能知道先办哪一件，慢路径能拿它当规划的起点。
 *
 * <p>刻意不带执行状态。这里只回答「用户说了哪几件事」，不回答「办到第几件了」——后者属于任务
 * 态，权威在 Postgres。两者混在一个对象里，就会出现计划说办完了而任务表说没有的局面。
 *
 * @param original 用户原话
 * @param items    按原句次序排列的子意图，至少两条
 * @param source   拆解来源，用于区分规则拆解与慢路径模型规划
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record IntentPlan(String original, List<SubIntent> items, Source source) {

    /** 拆解来源。 */
    public enum Source {
        /** A 线规则切分。零模型调用，同步链路上立刻可用。 */
        RULE,
        /** B 线慢路径模型规划。 */
        PLANNER,
        /** 规则步骤边界保留，模型只消歧或补全了部分能力。 */
        HYBRID
    }

    public IntentPlan {
        items = items == null ? List.of() : List.copyOf(items);

        if (items.size() < 2) {
            throw new IllegalArgumentException(
                    "只有 " + items.size() + " 件事就不该产生拆解计划。拆不出两件事时应当不给计划，"
                            + "而不是给一份长度为 1 的——后者会让下游以为这是多意图");
        }
        Set<String> stepIds = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).order() != i) {
                throw new IllegalArgumentException(
                        "子意图次序与列表位置不一致（位置 " + i + " 的 order 是 "
                                + items.get(i).order() + "）。次序决定执行先后，错位就是执行错序");
            }
            SubIntent item = items.get(i);
            if (stepIds.contains(item.stepId())) {
                throw new IllegalArgumentException("重复的计划步骤 ID：" + item.stepId());
            }
            if (!stepIds.containsAll(item.dependsOn())) {
                throw new IllegalArgumentException(
                        "步骤依赖必须引用前序步骤：step=" + item.stepId()
                                + " dependsOn=" + item.dependsOn());
            }
            stepIds.add(item.stepId());
        }
    }

    /** 第一件该办的事。多意图澄清时用它做默认建议。 */
    public SubIntent first() {
        return items.get(0);
    }

    /** 是否每一件事都认出了能力。有认不出的就不能整条自动执行。 */
    public boolean fullyResolved() {
        return items.stream().allMatch(SubIntent::resolved);
    }

    /** 是否存在条件依赖。有条件依赖就不能简单地按顺序全下发。 */
    public boolean hasConditional() {
        return items.stream()
                .anyMatch(item -> item.relation() == Enums.IntentRelation.CONDITIONAL);
    }

    /** 给用户看的逐条描述，进多意图澄清话术的 {@code taskSummaries}。 */
    public List<String> summaries() {
        return items.stream().map(SubIntent::summary).toList();
    }
}
