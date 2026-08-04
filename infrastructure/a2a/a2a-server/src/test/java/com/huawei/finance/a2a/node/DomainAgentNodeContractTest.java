package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.tck.AgentNodeContract;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基线自证：{@link DomainAgentNode} 这层适配器本身过 {@link AgentNodeContract}。
 *
 * <p>不跑这一遍的话，契约包就是一个没人验过的抽象类——26 个域照它实现，
 * 而它自己可能压根跑不绿。阶段 3a 门禁要的「首版用例绿」指的就是这个。
 *
 * <p>这里的执行器是最小合规实现,只做两件事:回结构化事实、按 delegationId 去重。
 * 域侧的真实业务逻辑不在本包的守备范围内。
 */
class DomainAgentNodeContractTest extends AgentNodeContract {

    /**
     * 本地入站去重。
     *
     * <p>键是 delegationId 而不是业务参数:业务参数相同的两次委托是两笔生意，
     * 按参数去重会把用户真的连转两笔当成重投给吞掉一笔。
     */
    private final Map<String, Map<String, Object>> settled = new ConcurrentHashMap<>();

    @Override
    protected AgentNode node() {
        return new DomainAgentNode("agent.account", java.util.List.of("account"),
                new DomainCapabilityExecutor() {
                    @Override
                    public boolean claims(DelegationEnvelope envelope) {
                        // GOAL 里认不出账户语义就不认领，让入口改投
                        String goal = envelope.goal();
                        return goal != null && (goal.contains("余额") || goal.contains("账户"));
                    }

                    @Override
                    public Outcome execute(DelegationEnvelope envelope) {
                        Map<String, Object> facts = settled.computeIfAbsent(
                                envelope.delegationId(),
                                id -> Map.of("balance", "12845.60", "currency", "CNY",
                                        "queriedAt", "2025-07-28T10:00:00Z"));
                        return Outcome.succeeded(facts);
                    }
                }, true);
    }

    @Override
    protected String ownedCapabilityId() {
        return "cap.account.balance.query";
    }
}
