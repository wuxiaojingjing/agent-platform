package com.huawei.finance.domain.insurance;

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

@DisplayName("保险域 AgentNodeContract")
class InsuranceAgentNodeContractTest extends AgentNodeContract {

    @Override
    protected AgentNode node() {
        InsuranceDomainAgent leaf = new InsuranceDomainAgent(principal ->
                new InsuranceProductPort.ProductView("I", "保险", "保险", "R2", "-", "终身"));
        DomainAgentExecutor inner = new DomainAgentExecutor(leaf, new KeywordGoalResolver(Map.of(
                "cap.insurance.product.query", List.of("保险", "产品B"))));
        return new DomainAgentNode("agent.insurance_service", List.of("insurance_service"),
                new IdempotentExecutor(inner), true);
    }

    @Override
    protected String ownedCapabilityId() {
        return "cap.insurance.product.query";
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
