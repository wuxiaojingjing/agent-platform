package com.huawei.finance.domain.account;

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

/**
 * 账户域节点过 {@link AgentNodeContract}（v0.4 阶段 3a 门禁）。
 *
 * <p>入站去重按 delegationId 夹在执行器外：叶子是读查询，契约仍要求
 * 「二次到达不得产生第二笔副作用」的形状，与网关台账去重互补。
 */
@DisplayName("账户域 AgentNodeContract")
class AccountAgentNodeContractTest extends AgentNodeContract {

    @Override
    protected AgentNode node() {
        AccountDomainAgent leaf = new AccountDomainAgent(new AccountPort() {
            public AccountView accountView(String principalRef) {
                return new AccountView(List.of(new CardView(1, "测试账户", "100.00")));
            }
            public List<TransactionView> transactions(String principalRef) { return List.of(); }
        });
        DomainAgentExecutor inner = new DomainAgentExecutor(leaf, new KeywordGoalResolver(Map.of(
                "cap.account.balance.query", List.of("余额", "账户"))));
        return new DomainAgentNode("agent.account", List.of("account"),
                new IdempotentExecutor(inner), true);
    }

    @Override
    protected String ownedCapabilityId() {
        return "cap.account.balance.query";
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
