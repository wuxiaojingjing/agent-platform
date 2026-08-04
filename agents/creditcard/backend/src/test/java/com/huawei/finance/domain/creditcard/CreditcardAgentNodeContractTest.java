package com.huawei.finance.domain.creditcard;

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

@DisplayName("信用卡域 AgentNodeContract")
class CreditcardAgentNodeContractTest extends AgentNodeContract {

    @Override
    protected AgentNode node() {
        CreditcardDomainAgent leaf = new CreditcardDomainAgent(new CreditcardPort() {
            public BillView bill(String principalRef, String cardRef) {
                return new BillView("100.00", "2026-08-10");
            }
            public OperationReceipt repay(RepayCommand command) {
                return new OperationReceipt("RP-TEST", command.amount(), "");
            }
            public OperationReceipt replace(ReplaceCommand command) {
                return new OperationReceipt("RC-TEST", "", command.cardType());
            }
        });
        DomainAgentExecutor inner = new DomainAgentExecutor(leaf, new KeywordGoalResolver(Map.of(
                "cap.creditcard.bill.query", List.of("账单", "信用卡"))));
        return new DomainAgentNode("agent.creditcard", List.of("creditcard_service"),
                new IdempotentExecutor(inner), true);
    }

    @Override
    protected String ownedCapabilityId() {
        return "cap.creditcard.bill.query";
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
