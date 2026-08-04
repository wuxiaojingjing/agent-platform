package com.huawei.finance.a2a.server;

import com.huawei.finance.contracts.a2a.AgentNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LocalAgentNodeRegistry {

    private final Map<String, AgentNode> nodes;

    public LocalAgentNodeRegistry(List<AgentNode> nodes) {
        this.nodes = nodes.stream().collect(Collectors.toUnmodifiableMap(
                AgentNode::agentId, Function.identity(), (first, ignored) -> first));
    }

    public Optional<AgentNode> find(String agentId) {
        return Optional.ofNullable(nodes.get(agentId));
    }
}
