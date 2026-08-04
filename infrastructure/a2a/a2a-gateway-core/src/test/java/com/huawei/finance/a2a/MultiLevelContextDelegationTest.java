package com.huawei.finance.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.a2a.PrincipalContext;
import com.huawei.finance.contracts.model.ContextDelta;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MultiLevelContextDelegationTest {

    @Test
    void entryToWealthToFundKeepsLineageAndOnlyProjectsMinimalContext() {
        List<DelegationEnvelope> delivered = new ArrayList<>();
        AtomicReference<DelegationClient> clientRef = new AtomicReference<>();
        AtomicReference<DelegationReceipt> fundReceipt = new AtomicReference<>();
        A2ADispatcher dispatcher = envelope -> {
            delivered.add(envelope);
            if ("agent.wealth_aggregate".equals(envelope.targetAgentId())) {
                SubtaskContextEnvelope childContext = new SubtaskContextEnvelope(
                        "lease-fund", envelope.subtaskContext().baseStateVersion(),
                        envelope.deadline(), "找出基金持仓中风险最高的产品", Map.of(),
                        envelope.subtaskContext().facts().stream()
                                .filter(fact -> fact.ref().equals("fact:fund-holdings"))
                                .toList(),
                        List.of("cap.fund.product.query"),
                        List.of(SubtaskContextEnvelope.Scope.SUBTASK,
                                SubtaskContextEnvelope.Scope.DOMAIN),
                        SubtaskContextEnvelope.Scope.SUBTASK);
                DelegationReceipt child = clientRef.get().delegate(new DelegationClient.DelegationRequest(
                        envelope.tenantId(), "agent.wealth_aggregate", envelope.rootTaskId(),
                        "wealth-task", "wealth-task", envelope.traceId(), DelegationMode.TASK,
                        Enums.TaskSource.FAST_PATH, envelope.principal(), childContext.goal(),
                        "cap.fund.product.query", Map.of("holdingRefs", List.of("opaque-fund-a")),
                        List.of(), envelope.deadline(), 5000, envelope.delegationPath(), childContext),
                        List.of("agent.fund_service"));
                fundReceipt.set(child);
                assertThat(child.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
                ContextEvidence aggregate = fact("fact:wealth:risk-product",
                        Map.of("riskProductRef", child.facts().get("riskProductRef")),
                        "agent.wealth_aggregate", "wealth-task");
                return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION,
                        envelope.delegationId(), DelegationOutcome.SUCCEEDED,
                        Map.of("targetTaskId", "wealth-task",
                                "riskProductRef", child.facts().get("riskProductRef")),
                        List.of(), null, null,
                        new ContextDelta(envelope.subtaskContext().baseStateVersion(),
                                List.of(aggregate), List.of(), List.of(), List.of()));
            }
            if ("agent.fund_service".equals(envelope.targetAgentId())) {
                ContextEvidence result = fact("fact:fund:risk-product",
                        Map.of("riskProductRef", "opaque-product-a"),
                        "agent.fund_service", "fund-task");
                return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION,
                        envelope.delegationId(), DelegationOutcome.SUCCEEDED,
                        Map.of("targetTaskId", "fund-task", "riskProductRef", "opaque-product-a"),
                        List.of(), null, null,
                        new ContextDelta(envelope.subtaskContext().baseStateVersion(),
                                List.of(result), List.of(), List.of(), List.of()));
            }
            return DelegationReceipt.fatal(envelope.delegationId(), "UNEXPECTED_TARGET", null);
        };
        DelegationClient client = new DelegationClient(dispatcher, new A2AProperties(),
                new SimpleMeterRegistry(), Clock.systemUTC());
        clientRef.set(client);
        SubtaskContextEnvelope rootContext = new SubtaskContextEnvelope(
                "lease-wealth", 11, Instant.now().plusSeconds(30),
                "分析我的基金持仓并找出风险最高的产品", Map.of(),
                List.of(fact("fact:fund-holdings",
                        Map.of("holdingRefs", List.of("opaque-fund-a", "opaque-fund-b")),
                        "agent.mobile-banking-assistant", "root-task")),
                List.of("agent.wealth_aggregate"),
                List.of(SubtaskContextEnvelope.Scope.SUBTASK,
                        SubtaskContextEnvelope.Scope.DOMAIN),
                SubtaskContextEnvelope.Scope.SUBTASK);

        DelegationReceipt root = client.delegate(new DelegationClient.DelegationRequest(
                "tenant", "agent.mobile-banking-assistant", "root-task", "root-task",
                "root-task", "trace-three-level", DelegationMode.GOAL, null,
                new PrincipalContext("opaque-principal", "STRONG", "MOBILE", "source-session"),
                rootContext.goal(), "agent.wealth_aggregate", Map.of(), List.of(),
                Instant.now().plusSeconds(30), 5000, List.of(), rootContext),
                List.of("agent.wealth_aggregate"));

        assertThat(root.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(delivered).hasSize(2);
        DelegationEnvelope wealth = delivered.get(0);
        DelegationEnvelope fund = delivered.get(1);
        assertThat(wealth.rootTaskId()).isEqualTo("root-task");
        assertThat(fund.rootTaskId()).isEqualTo("root-task");
        assertThat(wealth.traceId()).isEqualTo("trace-three-level");
        assertThat(fund.traceId()).isEqualTo("trace-three-level");
        assertThat(wealth.delegationPath())
                .containsExactly("agent.mobile-banking-assistant");
        assertThat(fund.delegationPath())
                .containsExactly("agent.mobile-banking-assistant", "agent.wealth_aggregate");
        assertThat(wealth.delegationId()).isNotEqualTo(fund.delegationId());
        assertThat(root.facts().get("targetTaskId")).isEqualTo("wealth-task");
        assertThat(fundReceipt.get().contextDelta().upserts().getFirst().sourceTaskId())
                .isEqualTo("fund-task");
        assertThat(root.contextDelta().upserts().getFirst().sourceTaskId()).isEqualTo("wealth-task");
        assertThat(fund.subtaskContext().facts())
                .extracting(ContextEvidence::ref)
                .containsExactly("fact:fund-holdings");
        assertThat(delivered).allSatisfy(envelope ->
                assertThat(envelope.subtaskContext().facts())
                        .noneMatch(fact -> fact.kind() == ContextEvidence.Kind.USER_TURN));
    }

    private static ContextEvidence fact(
            String ref, Map<String, Object> value, String sourceAgent, String sourceTask) {
        return new ContextEvidence(ref, ContextEvidence.Kind.TOOL_FACT, value, sourceAgent,
                sourceTask, null, Instant.now(), null, ContextEvidence.Sensitivity.SENSITIVE);
    }
}
