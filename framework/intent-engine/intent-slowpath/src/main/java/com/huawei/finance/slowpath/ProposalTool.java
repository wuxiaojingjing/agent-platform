package com.huawei.finance.slowpath;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.openjiuwen.core.foundation.tool.Tool;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 规划用的能力工具：调它只**记下一条提案**，不执行。
 *
 * <p>慢路径 Agent 负责把一句复合诉求拆成几步；每一步该不该做、能不能做、做过没有，
 * 由中控判定（ADR-002「不引入第二个状态权威」）。让 Agent 边想边执行，
 * 一次规划失误就是一笔真实的资金划转，而且中间失败时没有人知道该补偿到哪一步。
 *
 * <p>工具卡经 {@link CapabilityToolCards} 投影，与资产侧描述同源。
 */
public class ProposalTool extends Tool {

    private final CapabilityCard capability;
    private final List<TaskProposal> sink;

    /**
     * @param sink 收集提案的列表。由调用方持有，一次 Agent 会话一个
     */
    public ProposalTool(CapabilityCard capability, List<TaskProposal> sink) {
        super(CapabilityToolCards.toToolCard(capability));
        this.capability = capability;
        this.sink = sink;
    }

    /**
     * 记下提案，回一句确认。
     *
     * <p>返回文本里明确写「已列入计划，尚未执行」，是为了不让模型据此往下推理成
     * 「钱已经转了，接下来查一下余额确认」——它看不到执行与否的区别，只能靠这句话。
     */
    @Override
    public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
        Map<String, Object> slots = inputs == null ? Map.of() : inputs;
        String reason = kwargs == null ? null : String.valueOf(kwargs.getOrDefault("reason", ""));
        sink.add(new TaskProposal(capability, slots, reason));
        return Map.of(
                "accepted", true,
                "note", "已列入执行计划，尚未执行；是否执行、按什么顺序执行由中控判定");
    }

    @Override
    public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
        throw new UnsupportedOperationException("规划工具没有中间态可流");
    }
}
