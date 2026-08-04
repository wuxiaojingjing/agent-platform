package com.huawei.finance.intent;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.stability.Spi;
import java.util.List;
import java.util.Optional;

/**
 * 慢路径规划器：把一句复合诉求变成一份可执行次序的计划。
 *
 * <p>做成接口是为了让换实现是替换而不是重写。当前实现基于 OJ {@code DeepAgent}
 *（ADR-004）：任务环与内部规划打开，工具仍是 {@link ProposalTool}。
 * 执行权威不移交中控；多实例本地 Workspace 靠 FP-66 实例亲和消解。
 *
 * <p><b>只规划不执行。</b>返回的是计划，不是结果。执行仍走中控，由它建档、过护栏、
 * 发幂等键。
 *
 * <p><b>不产出面客文本。</b>模型理由只在本次规划调用内存中短暂存在，不写计划、日志或回复。
 */
@Spi
public interface IntentPlanner {

    /**
     * @param goal         用户原始诉求
     * @param candidates   可用能力。由调用方按召回结果给，不是全量——全量塞进提示词
     *                     既超预算又会让模型在几百个能力里挑花眼
     * @param ruleFallback A 线规则切分出来的锚定计划。Planner 只允许在其步骤候选内消歧，
     *                     不负责凭空生成、删除或重排步骤
     * @return 计划。规划失败或结果不可用时返回原始规则计划
     */
    Optional<IntentPlan> plan(String goal, List<CapabilityCard> candidates, IntentPlan ruleFallback);
}
