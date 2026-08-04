package com.huawei.finance.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 投错域的改投规则（架构草案 v0.3 §7.1）。 */
class DelegationClientTest {

    private static final Instant NOW = Instant.parse("2025-07-28T10:00:00Z");

    @Test
    @DisplayName("第一个域回 NOT_MINE：改投第二个，且用新 delegationId")
    void reroutesOnceOnNotMine() {
        List<String> seenIds = new ArrayList<>();
        AgentNode wrong = node("agent.wealth", env -> {
            seenIds.add(env.delegationId());
            return receipt(env, DelegationOutcome.NOT_MINE);
        });
        AgentNode right = node("agent.account", env -> {
            seenIds.add(env.delegationId());
            return DelegationReceipt.succeeded(env.delegationId(), Map.of("balance", "1.00"));
        });

        DelegationReceipt result = client(wrong, right)
                .delegate(request(), List.of("agent.wealth", "agent.account"));

        assertThat(result.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(seenIds).as("改投必须换 id：复用会被入站去重当成重投").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("改投只有一次：第二个域还回 NOT_MINE 就不再投第三个")
    void doesNotWalkTheWholeTopK() {
        // 遍历 Top-K 等于让一次判错的代价变成 K 次委托的延迟与成本
        List<String> called = new ArrayList<>();
        AgentNode a = node("agent.a", env -> {
            called.add("a");
            return receipt(env, DelegationOutcome.NOT_MINE);
        });
        AgentNode b = node("agent.b", env -> {
            called.add("b");
            return receipt(env, DelegationOutcome.NOT_MINE);
        });
        AgentNode c = node("agent.c", env -> {
            called.add("c");
            return DelegationReceipt.succeeded(env.delegationId(), Map.of("k", "v"));
        });

        DelegationReceipt result = client(a, b, c)
                .delegate(request(), List.of("agent.a", "agent.b", "agent.c"));

        assertThat(called).containsExactly("a", "b");
        assertThat(result.outcome())
                .as("候选没耗尽也停手，交给澄清而不是继续猜")
                .isEqualTo(DelegationOutcome.NOT_MINE);
    }

    @Test
    @DisplayName("DOMAIN_NOT_OPEN 不改投——换个域投不会让这个域变成已建成")
    void doesNotRerouteOnDomainNotOpen() {
        List<String> called = new ArrayList<>();
        AgentNode notOpen = node("agent.a", env -> {
            called.add("a");
            return receipt(env, DelegationOutcome.DOMAIN_NOT_OPEN);
        });
        AgentNode other = node("agent.b", env -> {
            called.add("b");
            return DelegationReceipt.succeeded(env.delegationId(), Map.of("k", "v"));
        });

        DelegationReceipt result = client(notOpen, other)
                .delegate(request(), List.of("agent.a", "agent.b"));

        assertThat(called).containsExactly("a");
        assertThat(result.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
    }

    @Test
    @DisplayName("信封的 deadline 由客户端算，逐层收缩")
    void clientComputesShrinkingDeadline() {
        List<Instant> deadlines = new ArrayList<>();
        AgentNode probe = node("agent.a", env -> {
            deadlines.add(env.deadline());
            return DelegationReceipt.succeeded(env.delegationId(), Map.of("k", "v"));
        });

        client(probe).delegate(request(), List.of("agent.a"));

        // 上游 deadline 是 NOW+2s，回传预留 500ms，所以本层只能到 NOW+1.5s
        assertThat(deadlines).containsExactly(NOW.plusMillis(1500));
    }

    @Test
    @DisplayName("域路由没给候选：FATAL，不自己挑一个")
    void noCandidatesIsFatal() {
        DelegationReceipt result = client().delegate(request(), List.of());

        assertThat(result.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(result.reasonCode()).isEqualTo("NO_ROUTE_CANDIDATE");
    }

    @Test
    @DisplayName("经网关重投同一 delegationId：节点只执行一次（阶段 2 入口侧证据）")
    void gatewayReplaySameDelegationIdRunsOnce() {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        AgentNode counting = node("agent.account", env -> {
            calls.incrementAndGet();
            return DelegationReceipt.succeeded(env.delegationId(), Map.of("balance", "1"));
        });
        List<AgentCard> cards = List.of(new AgentCard(
                "agent.account", "agent.account", "agent.account", "account",
                List.of(), "R0", 5000, "o", "1.0.0", AgentCard.Status.ACTIVE, Map.of()));
        A2AGateway gateway = new A2AGateway(
                new AgentCardRegistry(cards, List.of(counting)),
                new InMemoryDelegationStore(), new A2AProperties(),
                new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
        DelegationEnvelope envelope = new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "t-1", "mobile-banking-assistant", "agent.account",
                "root-1", "p-1", "src-1", "deleg-replay-1", "trace-1",
                DelegationMode.TASK, null, "cap.account.balance.query", Map.of(), List.of(),
                NOW.plusSeconds(30), List.of("mobile-banking-assistant"));

        DelegationReceipt first = gateway.dispatch(envelope);
        DelegationReceipt second = gateway.dispatch(envelope);

        assertThat(calls.get()).as("重投不得重跑").isEqualTo(1);
        assertThat(second.facts()).isEqualTo(first.facts());
    }

    private static DelegationClient client(AgentNode... nodes) {
        List<AgentCard> cards = new ArrayList<>();
        for (AgentNode n : nodes) {
            cards.add(new AgentCard(n.agentId(), n.agentId(), n.agentId(), "d",
                    List.of(), "R0", 5000, "o", "1.0.0", AgentCard.Status.ACTIVE, Map.of()));
        }
        AgentCardRegistry registry = new AgentCardRegistry(cards, List.of(nodes));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        A2AProperties props = new A2AProperties();
        A2AGateway gateway = new A2AGateway(registry, new InMemoryDelegationStore(), props,
                new SimpleMeterRegistry(), clock);
        return new DelegationClient(gateway, props, new SimpleMeterRegistry(), clock);
    }

    private static DelegationClient.DelegationRequest request() {
        return new DelegationClient.DelegationRequest("t-1", "mobile-banking-assistant", "root-1", "p-1",
                "src-1", "trace-1", DelegationMode.GOAL, "查余额", null, Map.of(), List.of(),
                NOW.plusMillis(2000), 0L, List.of());
    }

    private static DelegationReceipt receipt(DelegationEnvelope env, DelegationOutcome outcome) {
        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, env.delegationId(),
                outcome, Map.of(), List.of(), outcome.name(), null);
    }

    private static AgentNode node(String agentId,
                                  java.util.function.Function<DelegationEnvelope,
                                          DelegationReceipt> handler) {
        return new AgentNode() {
            @Override
            public String agentId() {
                return agentId;
            }

            @Override
            public DelegationReceipt handle(DelegationEnvelope envelope) {
                return handler.apply(envelope);
            }
        };
    }
}
