package com.huawei.finance.a2a.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.PrincipalContext;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 已有 TechDomainAgent 接进 A2A（架构草案 v0.3 阶段 3b）。 */
class DomainAgentExecutorTest {

    private static final Instant DEADLINE = Instant.parse("2025-07-28T10:00:30Z");

    @Test
    @DisplayName("TASK 主路径：域内办成，事实结构化且带上 capabilityId")
    void taskMainPathSucceeds() {
        var node = node(agent(Map.of("cap.account.balance.query",
                success(Map.of("balance", "12845.60")))));

        var receipt = node.handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsEntry("balance", "12845.60");
        assertThat(receipt.facts())
                .as("GOAL 模式下上游不知道域侧选了哪条能力，没这个字段无从核对")
                .containsEntry("capabilityId", "cap.account.balance.query");
    }

    @Test
    @DisplayName("未开放的能力：DOMAIN_NOT_OPEN，不是 NOT_MINE")
    void unsupportedCapabilityIsNotOpen() {
        // 域码对得上而能力没开放，是交付进度；回 NOT_MINE 会让入口白改投一次
        var node = node(agent(Map.of("cap.account.balance.query", success(Map.of("k", "v")))));

        var receipt = node.handle(task("cap.account.transaction.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
        assertThat(receipt.facts()).isEmpty();
    }

    @Test
    @DisplayName("GOAL 主路径：域侧自己把目标落到能力上")
    void goalIsResolvedByDomain() {
        var node = node(agent(Map.of("cap.account.balance.query",
                success(Map.of("balance", "100.00")))));

        var receipt = node.handle(goal("帮我查下余额"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsEntry("capabilityId", "cap.account.balance.query");
    }

    @Test
    @DisplayName("GOAL 落不到本域能力：NOT_MINE，不勉强办一个")
    void unresolvableGoalIsNotMine() {
        var node = node(agent(Map.of("cap.account.balance.query", success(Map.of("k", "v")))));

        var receipt = node.handle(goal("帮我买份重疾险"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.NOT_MINE);
    }

    @Test
    @DisplayName("域内 FAILED 且非 FATAL：译成 PARTIAL，不让上游对抖动做终态处理")
    void retryableFailureBecomesPartial() {
        var node = node(agent(Map.of("cap.account.balance.query",
                new TaskResult("t", Enums.TaskStatus.FAILED, Enums.FailureClass.RETRYABLE,
                        Map.of(), "k", GuardrailCheck.passed()))));

        var receipt = node.handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.PARTIAL);
    }

    @Test
    @DisplayName("域内明确 FATAL：译成 FATAL")
    void fatalStaysFatal() {
        var node = node(agent(Map.of("cap.account.balance.query",
                new TaskResult("t", Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL,
                        Map.of(), "k", GuardrailCheck.passed()))));

        var receipt = node.handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
    }

    @Test
    @DisplayName("域内 NEED_USER：回结构化缺槽，不回面客话术")
    void needUserReturnsStructuredSlots() {
        var node = node(agent(Map.of("cap.transfer",
                new TaskResult("t", Enums.TaskStatus.NEED_USER, Enums.FailureClass.NEED_USER,
                        Map.of("missingSlots", List.of(Map.of("slot", "payee",
                                "reasonCode", "MISSING"))), null, GuardrailCheck.passed()))));

        var receipt = node.handle(task("cap.transfer"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.NEED_USER);
        assertThat(receipt.missingSlots()).hasSize(1);
        assertThat(receipt.missingSlots().get(0).slot()).isEqualTo("payee");
    }

    @Test
    @DisplayName("域内抛异常：译成 PARTIAL 而不是穿透——穿透会让委托既无回执也无终态")
    void exceptionBecomesPartial() {
        TechDomainAgent throwing = new StubAgent(Map.of(), Set.of("cap.account.balance.query")) {
            @Override
            public TaskResult execute(UnifiedTask task) {
                throw new IllegalStateException("下游连接被重置");
            }
        };

        var receipt = node(throwing).handle(task("cap.account.balance.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.PARTIAL);
        assertThat(receipt.reasonCode()).isEqualTo("DOMAIN_EXECUTION_ERROR");
    }

    @Test
    @DisplayName("幂等键取 delegationId：重投不会在域内发第二把键")
    void idempotencyKeyIsDelegationId() {
        AtomicInteger executions = new AtomicInteger();
        java.util.List<String> keys = new java.util.ArrayList<>();
        TechDomainAgent recording = new StubAgent(Map.of(), Set.of("cap.account.balance.query")) {
            @Override
            public TaskResult execute(UnifiedTask task) {
                executions.incrementAndGet();
                keys.add(task.idempotencyKey());
                return success(Map.of("balance", "1.00"));
            }
        };
        var node = node(recording);
        var envelope = task("cap.account.balance.query");

        node.handle(envelope);
        node.handle(envelope);

        assertThat(executions.get()).isEqualTo(2);
        assertThat(keys).as("两次必须是同一把键，否则域内会做第二笔副作用")
                .containsExactly(envelope.delegationId(), envelope.delegationId());
    }

    @Test
    @DisplayName("A2A 任务的 source 单列一档：护栏谁跑的事后要分得出")
    void intentPathAndA2aInvocationOriginAreDistinct() {
        java.util.List<Enums.TaskSource> sources = new java.util.ArrayList<>();
        java.util.List<Enums.InvocationOrigin> origins = new java.util.ArrayList<>();
        TechDomainAgent recording = new StubAgent(Map.of(), Set.of("cap.account.balance.query")) {
            @Override
            public TaskResult execute(UnifiedTask task) {
                sources.add(task.source());
                origins.add(task.invocationOrigin());
                return success(Map.of("balance", "1.00"));
            }
        };

        node(recording).handle(task("cap.account.balance.query"));

        assertThat(sources).containsExactly(Enums.TaskSource.FAST_PATH);
        assertThat(origins).containsExactly(Enums.InvocationOrigin.A2A);
    }

    @Test
    @DisplayName("已验证主体以不透明 principalRef 注入域任务参数")
    void verifiedPrincipalIsInjectedIntoDomainTask() {
        java.util.concurrent.atomic.AtomicReference<String> principal = new java.util.concurrent.atomic.AtomicReference<>();
        TechDomainAgent recording = new StubAgent(Map.of(), Set.of("cap.account.balance.query")) {
            @Override
            public TaskResult execute(UnifiedTask task) {
                principal.set(String.valueOf(task.parameters().get("principalRef")));
                return success(Map.of("balance", "1.00"));
            }
        };
        DelegationEnvelope original = task("cap.account.balance.query");
        DelegationEnvelope authenticated = new DelegationEnvelope(original.version(), original.tenantId(),
                original.sourceAgentId(), original.targetAgentId(), original.rootTaskId(), original.parentTaskId(),
                original.sourceTaskId(), original.delegationId(), original.traceId(),
                new PrincipalContext("principal:opaque-token", "AUTHENTICATED", "MOBILE_BANK",
                        "session:opaque-token"), original.mode(), original.intentPath(), original.goal(),
                original.capabilityId(), original.parameters(), original.confirmedFacts(), original.deadline(),
                original.delegationPath());

        node(recording).handle(authenticated);

        assertThat(principal.get()).isEqualTo("principal:opaque-token");
    }

    // --- 脚手架 ---

    private static DomainAgentNode node(TechDomainAgent agent) {
        Map<String, List<String>> keywords = Map.of(
                "cap.account.balance.query", List.of("余额", "多少钱"),
                "cap.account.transaction.query", List.of("明细", "流水"),
                "cap.transfer", List.of("转账", "转给"));
        return new DomainAgentNode(agent.agentId(), List.of(agent.techDomainCode()),
                new DomainAgentExecutor(agent, new KeywordGoalResolver(keywords)), true);
    }

    private static TaskResult success(Map<String, Object> payload) {
        return new TaskResult("t", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                payload, "k", GuardrailCheck.passed());
    }

    private static TechDomainAgent agent(Map<String, TaskResult> results) {
        return new StubAgent(results, results.keySet());
    }

    private static class StubAgent implements TechDomainAgent {
        private final Map<String, TaskResult> results;
        private final Set<String> supported;

        StubAgent(Map<String, TaskResult> results, Set<String> supported) {
            this.results = results;
            this.supported = supported;
        }

        @Override
        public String agentId() {
            return "agent.account";
        }

        @Override
        public String techDomainCode() {
            return "account";
        }

        @Override
        public boolean supports(String capabilityId) {
            return supported.contains(capabilityId);
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            return results.get(task.capabilityId());
        }
    }

    private static DelegationEnvelope task(String capabilityId) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t", "mobile-banking-assistant",
                "agent.account", "root", "parent", "src-task", "d-" + capabilityId, "trace",
                DelegationMode.TASK, null, capabilityId, Map.of(), List.of(),
                DEADLINE, List.of("mobile-banking-assistant"));
    }

    private static DelegationEnvelope goal(String goal) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t", "mobile-banking-assistant",
                "agent.account", "root", "parent", "src-goal", "d-goal", "trace",
                DelegationMode.GOAL, goal, null, Map.of(), List.of(),
                DEADLINE, List.of("mobile-banking-assistant"));
    }
}
