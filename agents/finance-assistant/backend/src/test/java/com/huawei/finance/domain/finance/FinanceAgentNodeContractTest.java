package com.huawei.finance.domain.finance;

import com.huawei.finance.a2a.node.DomainAgentExecutor;
import com.huawei.finance.a2a.node.DomainAgentNode;
import com.huawei.finance.a2a.node.DomainCapabilityExecutor;
import com.huawei.finance.a2a.node.KeywordGoalResolver;
import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.tck.AgentNodeContract;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;

@DisplayName("金融助手域 AgentNodeContract")
class FinanceAgentNodeContractTest extends AgentNodeContract {

    @Override
    protected AgentNode node() {
        FinanceDomainAgent leaf = new FinanceDomainAgent(new NavigationCatalogPort() {
            public Map<String, Object> find(String capabilityId) {
                return Map.of("menuId", "m1", "menuName", "测试菜单", "bksPath", "/test");
            }
            public java.util.Set<String> capabilities() { return java.util.Set.of(ownedCapabilityId()); }
        });
        DomainAgentExecutor inner = new DomainAgentExecutor(leaf, new KeywordGoalResolver(Map.of(
                "cap.nav.wealth_aggregate_查询我的资产", List.of("资产", "查询"))));
        return new DomainAgentNode("agent.finance_assistant", List.of("finance_assistant"),
                new IdempotentExecutor(inner), true);
    }

    @Override
    protected String ownedCapabilityId() {
        return "cap.nav.wealth_aggregate_查询我的资产";
    }

    private static final class IdempotentExecutor implements DomainCapabilityExecutor {
        private final DomainAgentExecutor delegate;
        private final Map<String, Outcome> settled = new ConcurrentHashMap<>();

        IdempotentExecutor(DomainAgentExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean claims(DelegationEnvelope envelope) {
            return delegate.claims(envelope);
        }

        @Override
        public Optional<Boolean> owns(DelegationEnvelope envelope) {
            return delegate.owns(envelope);
        }

        @Override
        public Outcome execute(DelegationEnvelope envelope) {
            return settled.computeIfAbsent(envelope.delegationId(), id -> delegate.execute(envelope));
        }
    }
}
