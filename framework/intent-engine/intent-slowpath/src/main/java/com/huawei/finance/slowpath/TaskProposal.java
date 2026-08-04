package com.huawei.finance.slowpath;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.stability.Api;
import java.util.Map;

/**
 * 慢路径 Agent 规划出的一步：调哪个能力、带什么参数。
 *
 * <p>它**不是** {@code UnifiedTask}，差的正是幂等键与护栏结论——那两样只能由中控发，
 * 且必须按「建档 → 护栏 → 发键」的顺序（实施架构 §8.4）。让 Agent 直接产出 UnifiedTask
 * 等于让它自带执行许可，那时护栏还没看过这笔业务。
 *
 * @param capability 要调用的能力卡
 * @param slots      Agent 给出的参数。未经槽位校验，可能缺项或类型不对
 * @param reason     Agent 给出的临时理由；Grounding 前不得写入计划、日志或面客文本
 */
@Api
public record TaskProposal(CapabilityCard capability, Map<String, Object> slots, String reason) {

    public TaskProposal {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }
}
