package com.huawei.finance.a2a;

import com.huawei.finance.contracts.a2a.AgentNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AgentCard 注册与发现（架构草案 v0.2 §6 职责第 1 条）。
 *
 * <p>路由表里**只有 AgentNode**。纯执行器（节点内 {@code DomainAgent}）进不来——
 * 它们挂在某个节点的本地执行器下，对 A2A 不可寻址（v0.3 §6）。
 *
 * <p>卡与节点分开存:卡可能存在而节点未注册（Scaffold 域，目录可见但接委托应明确失败），
 * 这正是 {@code DOMAIN_NOT_OPEN} 与 {@code NOT_MINE} 能分开归因的前提。
 */
public class AgentCardRegistry {

    private final Map<String, AgentCard> cards = new LinkedHashMap<>();
    private final Map<String, AgentNode> nodes = new LinkedHashMap<>();

    public AgentCardRegistry(List<AgentCard> cards, List<AgentNode> nodes) {
        if (cards != null) {
            cards.forEach(c -> this.cards.put(c.agentId(), c));
        }
        if (nodes != null) {
            nodes.forEach(n -> this.nodes.put(n.agentId(), n));
        }
    }

    public Optional<AgentCard> find(String agentId) {
        return Optional.ofNullable(cards.get(agentId));
    }

    public Optional<AgentNode> node(String agentId) {
        return Optional.ofNullable(nodes.get(agentId));
    }

    /** 全部卡，含 Scaffold——目录要能看见尚未交付的域（v0.3 §5.4）。 */
    public List<AgentCard> all() {
        return List.copyOf(cards.values());
    }

    /** 按科技域码找卡。域路由的结论是域码，落到 agentId 要经这一步。 */
    public Optional<AgentCard> byTechDomain(String techDomainCode) {
        return cards.values().stream()
                .filter(c -> techDomainCode != null && techDomainCode.equals(c.techDomainCode()))
                .findFirst();
    }

    public int size() {
        return cards.size();
    }
}
