package com.huawei.finance.domain.fund;

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

@DisplayName("基金域 AgentNodeContract")
class FundAgentNodeContractTest extends AgentNodeContract {

    @Override
    protected AgentNode node() {
        FundDomainAgent leaf = new FundDomainAgent(principal ->
                new FundProductPort.ProductView("F", "基金", "基金", "R3", "3%", "开放式"));
        DomainAgentExecutor inner = new DomainAgentExecutor(leaf, new KeywordGoalResolver(Map.of(
                "cap.fund.product.query", List.of("基金", "产品A"))));
        return new DomainAgentNode("agent.fund_service", List.of("fund_service"),
                new IdempotentExecutor(inner), true);
    }

    @Override
    protected String ownedCapabilityId() {
        return "cap.fund.product.query";
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
