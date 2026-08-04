package com.huawei.finance.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.a2a.node.DomainAgentNode;
import com.huawei.finance.a2a.node.DomainCapabilityExecutor;
import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A2A 版的「打通用对话 Handler」端到端。
 *
 * <p>现成先例是 {@code OjEndToEndTest.AgainstGenericChatHandler}——真起一个只会回自然语言的
 * Handler，验这条线守得住。架构草案 v0.2 §6.1 要求 A2A 的端到端**照这个形状再来一遍**，
 * 这个类就是那一遍。
 *
 * <p>要守住的事只有一句:一个会说「已为您转账 1000 元」的下游，
 * 不能让中控把一笔从未发生的转账记为成功。
 */
class A2AAgainstGenericChatNodeTest {

    private static final Instant NOW = Instant.parse("2025-07-28T10:00:00Z");
    private static final String TARGET = "agent.transfer";

    /**
     * 只会说自然语言的节点。
     *
     * <p>它不是恶意实现,而是最常见的一种错配:把 A2A 目标指到了一个通用对话服务上。
     * 那个服务会尽责地回一段像样的话,而「像样」正是问题所在。
     */
    private static final class GenericChatNode implements AgentNode {

        @Override
        public String agentId() {
            return TARGET;
        }

        @Override
        public DelegationReceipt handle(DelegationEnvelope envelope) {
            // 它根本不知道 DelegationReceipt 这回事,能给的只有一段话。
            // 用 null 表达这一点：交不出信封
            return null;
        }
    }

    @Test
    @DisplayName("通用对话节点编出的成功，不进事实集")
    void generatedSuccessNeverBecomesFact() {
        DelegationReceipt receipt = gateway(new GenericChatNode())
                .dispatch(transferGoal("d-chat"));

        assertThat(receipt.outcome())
                .as("宁可整单拒绝，也不把一句话当成办成了")
                .isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.facts()).isEmpty();
    }

    @Test
    @DisplayName("回执版本不符：同样整单 FATAL")
    void versionMismatchIsFatal() {
        AgentNode oldVersion = new AgentNode() {
            @Override
            public String agentId() {
                return TARGET;
            }

            @Override
            public DelegationReceipt handle(DelegationEnvelope envelope) {
                return new DelegationReceipt("a2a/0", envelope.delegationId(),
                        DelegationOutcome.SUCCEEDED, Map.of("amount", "1000"),
                        List.of(), null, null);
            }
        };

        DelegationReceipt receipt = gateway(oldVersion).dispatch(transferGoal("d-ver"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("A2A_RECEIPT_VERSION_MISMATCH");
    }

    @Test
    @DisplayName("回执 delegationId 对不上：FATAL——不能拿别人的结果当本次结果")
    void mismatchedDelegationIdIsFatal() {
        AgentNode confused = new AgentNode() {
            @Override
            public String agentId() {
                return TARGET;
            }

            @Override
            public DelegationReceipt handle(DelegationEnvelope envelope) {
                return DelegationReceipt.succeeded("别的委托", Map.of("amount", "1000"));
            }
        };

        DelegationReceipt receipt = gateway(confused).dispatch(transferGoal("d-mix"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("A2A_RECEIPT_DELEGATION_MISMATCH");
    }

    @Test
    @DisplayName("规矩的域节点：办成了，事实是结构化的")
    void wellBehavedNodeSucceeds() {
        DomainAgentNode transfer = new DomainAgentNode(TARGET, List.of("transfer"),
                new DomainCapabilityExecutor() {
                    @Override
                    public boolean claims(DelegationEnvelope envelope) {
                        return true;
                    }

                    @Override
                    public Outcome execute(DelegationEnvelope envelope) {
                        return Outcome.succeeded(Map.of(
                                "amount", "1000.00", "toAccount", "****8888",
                                "serialNo", "T20250728100000001"));
                    }
                }, true);

        DelegationReceipt receipt = gateway(transfer).dispatch(transferGoal("d-ok"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsKeys("amount", "toAccount", "serialNo");
    }

    private static A2AGateway gateway(AgentNode node) {
        AgentCard card = new AgentCard(TARGET, "transfer", "转账助手", "d",
                List.of("transfer"), "R2", 8000, "支付领域", "1.0.0",
                AgentCard.Status.ACTIVE, Map.of());
        return new A2AGateway(new AgentCardRegistry(List.of(card), List.of(node)),
                new InMemoryDelegationStore(), new A2AProperties(), new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DelegationEnvelope transferGoal(String delegationId) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t-1", "mobile-banking-assistant",
                TARGET, "root-1", "parent-1", "src-1", delegationId, "trace-1",
                DelegationMode.GOAL, "给张三转 1000 块", null, Map.of(), List.of(),
                NOW.plusSeconds(30), List.of("mobile-banking-assistant"));
    }
}
