package com.huawei.finance.a2a.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 域节点适配器（架构草案 v0.3 §5.2、§7.1）。 */
class DomainAgentNodeTest {

    private static final Instant DEADLINE = Instant.parse("2025-07-28T10:00:30Z");

    @Test
    @DisplayName("TASK 的能力不属于本域：NOT_MINE，不勉强执行")
    void taskOutsideDomainIsNotMine() {
        DomainAgentNode node = node(executor(true, DomainCapabilityExecutor.Outcome
                .succeeded(Map.of("k", "v"))));

        var receipt = node.handle(task("cap.wealth.product.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.NOT_MINE);
    }

    @Test
    @DisplayName("TASK 的能力属于本域：照常执行")
    void taskInsideDomainExecutes() {
        DomainAgentNode node = node(executor(true, DomainCapabilityExecutor.Outcome
                .succeeded(Map.of("balance", "12845.60"))));

        var receipt = node.handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsEntry("balance", "12845.60");
    }

    @Test
    @DisplayName("GOAL 归属由域侧判——域侧不认就 NOT_MINE")
    void goalClaimIsDecidedByDomain() {
        // 由域侧判而不是入口判，正是 GOAL 的价值：下游比上游更懂自己那摊事
        DomainAgentNode node = node(executor(false, DomainCapabilityExecutor.Outcome
                .succeeded(Map.of("k", "v"))));

        var receipt = node.handle(goal("帮我买点理财"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.NOT_MINE);
    }

    @Test
    @DisplayName("域侧声称办成却没给结构化事实：在本域就拦下")
    void emptyFactsFromDomainIsFatalHere() {
        // 不拦而往上传的话，网关的强制信封也会判 FATAL，
        // 但那时日志指向「网关拒了回执」，排障要多绕一层才看到问题在本域实现里
        DomainAgentNode node = node(executor(true, new DomainCapabilityExecutor.Outcome(
                DelegationOutcome.SUCCEEDED, Map.of(), List.of(), null, "办好了呀")));

        var receipt = node.handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("DOMAIN_FACTS_EMPTY");
    }

    @Test
    @DisplayName("未建成的域：DOMAIN_NOT_OPEN，不回假数据")
    void scaffoldDomainSaysNotOpen() {
        DomainAgentNode node = node(executor(true,
                DomainCapabilityExecutor.Outcome.notOpen("本域尚未交付")));

        var receipt = node.handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
        assertThat(receipt.facts()).isEmpty();
    }

    @Test
    @DisplayName("纯执行器收到 GOAL：拒绝")
    void nonAutonomousRejectsGoal() {
        DomainAgentNode node = new DomainAgentNode("agent.account", List.of("account"),
                executor(true, DomainCapabilityExecutor.Outcome.succeeded(Map.of("k", "v"))),
                false);

        var receipt = node.handle(goal("查余额"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("GOAL_TO_NON_AUTONOMOUS");
    }

    private static DomainAgentNode node(DomainCapabilityExecutor executor) {
        return new DomainAgentNode("agent.account", List.of("account"), executor, true);
    }

    private static DomainCapabilityExecutor executor(boolean claims,
                                                     DomainCapabilityExecutor.Outcome outcome) {
        return new DomainCapabilityExecutor() {
            @Override
            public boolean claims(DelegationEnvelope envelope) {
                return claims;
            }

            @Override
            public Outcome execute(DelegationEnvelope envelope) {
                return outcome;
            }
        };
    }

    private static DelegationEnvelope task(String capabilityId) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t", "mobile-banking-assistant",
                "agent.account", "root", "parent", "src", "d-task", "trace",
                DelegationMode.TASK, null, capabilityId, Map.of(), List.of(),
                DEADLINE, List.of("mobile-banking-assistant"));
    }

    private static DelegationEnvelope goal(String goal) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t", "mobile-banking-assistant",
                "agent.account", "root", "parent", "src", "d-goal", "trace",
                DelegationMode.GOAL, goal, null, Map.of(), List.of(),
                DEADLINE, List.of("mobile-banking-assistant"));
    }
}
