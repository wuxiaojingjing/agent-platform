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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A2A 网关闸门与幂等（架构草案 v0.2 §6.1–6.3）。 */
class A2AGatewayTest {

    private static final Instant NOW = Instant.parse("2025-07-28T10:00:00Z");
    private static final String TARGET = "agent.account";

    @Test
    @DisplayName("GOAL 打到只会说自然语言的节点：整单 FATAL，不记成功")
    void goalAgainstGenericChatHandlerIsFatal() {
        // 这正是 OJ 接入踩过的坑：模型回一句「已为您转账 1000 元」，
        // 没有异常、没有告警，中控把一笔从未发生的转账记为成功。
        // 那个 Handler 交不出版本正确的信封，所以必须死在网关这一层
        AgentNode chatty = node(TARGET, true, env -> null);

        DelegationReceipt receipt = gateway(chatty).dispatch(goal("d-1", List.of("mobile-banking-assistant")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("A2A_RECEIPT_MISSING");
    }

    @Test
    @DisplayName("声称 SUCCEEDED 但没有结构化事实：也是 FATAL")
    void successWithoutFactsIsFatal() {
        AgentNode liar = node(TARGET, true, env -> new DelegationReceipt(
                DelegationEnvelope.CURRENT_VERSION, env.delegationId(),
                DelegationOutcome.SUCCEEDED, Map.of(), List.of(), null,
                "已为您转账 1000 元"));

        DelegationReceipt receipt = gateway(liar).dispatch(goal("d-2", List.of("mobile-banking-assistant")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("A2A_RECEIPT_FACTS_EMPTY");
    }

    @Test
    @DisplayName("同一 delegationId 二次到达：返回首次结果，节点只被调一次")
    void sameDelegationIdRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        AgentNode counting = node(TARGET, true, env -> {
            calls.incrementAndGet();
            return DelegationReceipt.succeeded(env.delegationId(), Map.of("balance", "12845.60"));
        });
        A2AGateway gateway = gateway(counting);

        DelegationEnvelope envelope = goal("d-3", List.of("mobile-banking-assistant"));
        DelegationReceipt first = gateway.dispatch(envelope);
        DelegationReceipt second = gateway.dispatch(envelope);

        assertThat(calls.get()).as("重投不得重跑：重跑会发第二把幂等键").isEqualTo(1);
        assertThat(second.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(second.facts()).isEqualTo(first.facts());
    }

    @Test
    @DisplayName("环路：路径上已出现目标 agentId 即拒绝")
    void loopIsRejected() {
        AgentNode ok = node(TARGET, true, env ->
                DelegationReceipt.succeeded(env.delegationId(), Map.of("k", "v")));

        // 路径长度取 2 而不是 3：3 会先撞上深度闸门（上限 3），
        // 那样这条用例就变成了第二个深度用例，环路判定其实没被验到
        DelegationReceipt receipt = gateway(ok)
                .dispatch(goal("d-4", List.of("mobile-banking-assistant", TARGET)));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("DELEGATION_LOOP");
    }

    @Test
    @DisplayName("深度超限：FATAL，不静默截断")
    void depthExceededIsFatal() {
        AgentNode ok = node(TARGET, true, env ->
                DelegationReceipt.succeeded(env.delegationId(), Map.of("k", "v")));

        DelegationReceipt receipt = gateway(ok)
                .dispatch(goal("d-5", List.of("a", "b", "c")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("DELEGATION_DEPTH_EXCEEDED");
    }

    @Test
    @DisplayName("纯执行器收到 GOAL：拒绝，不猜一个能力去执行")
    void goalToNonAutonomousIsRejected() {
        AgentNode executor = node(TARGET, false, env ->
                DelegationReceipt.succeeded(env.delegationId(), Map.of("k", "v")));

        DelegationReceipt receipt = gateway(executor).dispatch(goal("d-6", List.of("mobile-banking-assistant")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("GOAL_TO_NON_AUTONOMOUS");
    }

    @Test
    @DisplayName("有卡但没节点：DOMAIN_NOT_OPEN 而不是 NOT_MINE——两者归因不同")
    void cardWithoutNodeIsDomainNotOpen() {
        // 合并成一种「失败」之后，入口判错会永远被计成「域没做完」（§7.1）
        AgentCard card = new AgentCard(TARGET, "account", "账户助手", "d",
                List.of("account"), "R0", 5000, "账户领域", "1.0.0",
                AgentCard.Status.SCAFFOLD, Map.of());
        A2AGateway gateway = new A2AGateway(new AgentCardRegistry(List.of(card), List.of()),
                new InMemoryDelegationStore(), new A2AProperties(), new SimpleMeterRegistry(),
                fixed());

        DelegationReceipt receipt = gateway.dispatch(goal("d-7", List.of("mobile-banking-assistant")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
    }

    @Test
    @DisplayName("连卡都没有：AGENT_UNKNOWN——那是资产配置错，不是交付进度问题")
    void missingCardIsAgentUnknown() {
        // 两者混为一谈的话，「这个域码根本不存在」在监控上和「这个域还没做」
        // 是同一条曲线，而前者本该立刻有人去改资产
        A2AGateway gateway = new A2AGateway(new AgentCardRegistry(List.of(), List.of()),
                new InMemoryDelegationStore(), new A2AProperties(), new SimpleMeterRegistry(),
                fixed());

        DelegationReceipt receipt = gateway.dispatch(goal("d-7b", List.of("mobile-banking-assistant")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("AGENT_UNKNOWN");
    }

    @Test
    @DisplayName("节点抛异常：收口成 FATAL，不穿透")
    void nodeExceptionBecomesFatal() {
        AgentNode boom = node(TARGET, true, env -> {
            throw new IllegalStateException("下游炸了");
        });

        DelegationReceipt receipt = gateway(boom).dispatch(goal("d-8", List.of("mobile-banking-assistant")));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
        assertThat(receipt.reasonCode()).isEqualTo("NODE_THREW");
    }

    private static A2AGateway gateway(AgentNode node) {
        AgentCard card = new AgentCard(TARGET, "account", "账户助手", "d",
                List.of("account"), "R0", 5000, "账户领域", "1.0.0",
                AgentCard.Status.ACTIVE, Map.of());
        AgentCardRegistry registry = new AgentCardRegistry(List.of(card), List.of(node));
        return new A2AGateway(registry, new InMemoryDelegationStore(), new A2AProperties(),
                new SimpleMeterRegistry(), fixed());
    }

    private static Clock fixed() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static DelegationEnvelope goal(String delegationId, List<String> path) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t-1", "mobile-banking-assistant",
                TARGET, "root-1", "parent-1", "src-1", delegationId, "trace-1",
                DelegationMode.GOAL, "查一下我的余额", null, Map.of(), List.of(),
                NOW.plusSeconds(30), path);
    }

    /** 最小 AgentNode 替身。 */
    private static AgentNode node(String agentId, boolean autonomous,
                                  java.util.function.Function<DelegationEnvelope,
                                          DelegationReceipt> handler) {
        return new AgentNode() {
            @Override
            public String agentId() {
                return agentId;
            }

            @Override
            public boolean autonomous() {
                return autonomous;
            }

            @Override
            public DelegationReceipt handle(DelegationEnvelope envelope) {
                return handler.apply(envelope);
            }
        };
    }
}
