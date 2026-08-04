package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.port.TechDomainAgent;
import com.huawei.finance.stability.Spi;
import java.util.Optional;

/**
 * GOAL → 本域能力（架构草案 v0.3 §6.1、阶段 3a）。
 *
 * <p>这是 GOAL 模式的核心那一步:上游只给一句目标，**由域侧决定落到哪条能力**。
 * 放在域侧而不是入口，理由是入口不该实现领域业务语义（前提第 10 条）——
 * 「第几张卡」「转一半」这类解析属于对应科技域。
 *
 * <p>基线给的是一个保守实现（{@link KeywordGoalResolver}）:它只在有把握时才认领。
 * <b>宁可不认领。</b>认领了办错，用户看到的是一次错误办理;不认领，入口还会改投一次
 * 或转澄清。行内接管这个扩展点时应当保持同样的方向——把它做成「尽量认领」，
 * 收益是少一次澄清，代价是把一句没听懂的话办成了某件事。
 */
@Spi
public interface GoalCapabilityResolver {

    /**
     * @param goal 目标文本，可能为 null（TASK 模式）
     * @param agent 本域 Agent，用于核对候选能力是否真的被承接
     * @return 落到的能力 ID；拿不准返回 {@link Optional#empty()}
     */
    Optional<String> resolve(String goal, TechDomainAgent agent);
}
