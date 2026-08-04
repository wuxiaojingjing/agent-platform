package com.huawei.finance.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.contracts.port.DomainAgent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 委托接缝本身的行为（架构草案 §8.5 顺序图、§12 第 4 条）。
 *
 * <p>本类守 {@link AgentInvoker} 这一层:委托与本地执行**在同一个位置上被二选一**，
 * 而那个位置在护栏与幂等键之后。「护栏真的拦住了委托」由
 * {@code TaskOrchestratorMiddlewareTest} 端到端守（那条需要真库）。
 *
 * <p>此前委托挂在 {@code orchestrator.handle} 外面、由入口二选一，于是换一条执行通道
 * 顺带绕过了护栏、租约闸与本地任务真值——而且没有任何用例会因此变红。
 */
class DelegationUnderGuardrailTest {

    private RecordingDelegator delegator;
    private RecordingAgent local;
    private AgentInvoker invoker;

    @BeforeEach
    void setUp() {
        delegator = new RecordingDelegator();
        local = new RecordingAgent();
        invoker = new AgentInvoker(List.of(local), java.util.concurrent.Executors.newCachedThreadPool(
                r -> {
                    Thread t = new Thread(r, "test-delegation");
                    t.setDaemon(true);
                    return t;
                }),
                new OrchestratorProperties(), new SimpleMeterRegistry(), delegator);
    }

    @Test
    @DisplayName("委托承接时不再调本地领域 Agent：同一个位置上二选一")
    void delegatedCapabilitySkipsLocalAgent() {
        delegator.accept("cap.transfer", TaskResult.class);

        TaskResult result = invoker.invoke(task("cap.transfer"), card("cap.transfer", RiskLevel.R2));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
        assertThat(local.invocations).as("委托办成后不该再本地办一遍").isZero();
        assertThat(delegator.seen).hasSize(1);
    }

    @Test
    @DisplayName("委托拿到的任务已带幂等键与已过的护栏：位置在两者下游")
    void delegateReceivesGuardedTask() {
        delegator.accept("cap.transfer", TaskResult.class);

        invoker.invoke(task("cap.transfer"), card("cap.transfer", RiskLevel.R2));

        UnifiedTask seen = delegator.seen.get(0);
        assertThat(seen.idempotencyKey())
                .as("委托必须在幂等键之后发生，否则重投会在域内发第二把键")
                .isNotBlank();
        assertThat(seen.guardrailCheck().isPassed())
                .as("委托必须在护栏之后发生")
                .isTrue();
    }

    @Test
    @DisplayName("委托返回空 = 走不通：回落本地执行，用户不因内部路由选择办不成事")
    void unroutableDelegationFallsBackToLocal() {
        delegator.declineWithEmpty("cap.transfer");

        TaskResult result = invoker.invoke(task("cap.transfer"), card("cap.transfer", RiskLevel.R2));

        assertThat(local.invocations).as("走不通要回落本地，而不是让用户吃一个失败").isEqualTo(1);
        assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
    }

    @Test
    @DisplayName("不承接的能力照原路走本地，委托一次都不问信封")
    void unhandledCapabilityGoesLocal() {
        TaskResult result = invoker.invoke(task("cap.account.balance.query"),
                card("cap.account.balance.query", RiskLevel.R0));

        assertThat(delegator.seen).isEmpty();
        assertThat(local.invocations).isEqualTo(1);
        assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
    }

    @Test
    @DisplayName("没有装委托实现时行为不变：这条通道默认不存在")
    void absentDelegatorKeepsLocalPath() {
        AgentInvoker bare = TestAgentInvoker.of(local);

        assertThat(bare.invoke(task("cap.transfer"), card("cap.transfer", RiskLevel.R2)).status())
                .isEqualTo(Enums.TaskStatus.SUCCESS);
        assertThat(local.invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("慢路径意图经 A2A 到达目标后本地执行：两个维度互不替代")
    void a2aInboundSlowPathExecutesLocalLeafWithoutSelfDelegation() {
        delegator.accept("cap.transfer", TaskResult.class);
        UnifiedTask inbound = new UnifiedTask(
                "task-1", "trace-1", Enums.TaskSource.SLOW_PATH,
                Enums.InvocationOrigin.A2A, "给张三转 1000", "cap.transfer",
                Map.of("payee", "张三", "amount", "1000"), RiskLevel.R2,
                Map.of(), GuardrailCheck.passed(), "idem-1", List.of(),
                Instant.now().plusSeconds(30));

        TaskResult result = invoker.invoke(inbound, card("cap.transfer", RiskLevel.R2));

        assertThat(result.success()).isTrue();
        assertThat(local.invocations).isEqualTo(1);
        assertThat(delegator.seen).isEmpty();
    }

    // --- 脚手架 ---

    private static UnifiedTask task(String capabilityId) {
        return new UnifiedTask("task-1", "trace-1", Enums.TaskSource.FAST_PATH, "给张三转 1000",
                capabilityId, Map.of("payee", "张三", "amount", "1000"), RiskLevel.R2,
                Map.of(), GuardrailCheck.passed(), "idem-1", List.of(),
                Instant.now().plusSeconds(30));
    }

    private static CapabilityCard card(String capabilityId, RiskLevel risk) {
        return new CapabilityCard(capabilityId, capabilityId, Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.account", List.of("account"), "d", List.of(),
                Map.of(), Map.of(), List.of(), List.of(), risk, 5000,
                Enums.Idempotency.REQUIRED, "账户领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(), null);
    }

    private static final class RecordingDelegator implements CapabilityDelegator {
        private final List<UnifiedTask> seen = new ArrayList<>();
        private String handled;
        private boolean returnEmpty;

        void accept(String capabilityId, Class<TaskResult> ignored) {
            this.handled = capabilityId;
            this.returnEmpty = false;
        }

        void declineWithEmpty(String capabilityId) {
            this.handled = capabilityId;
            this.returnEmpty = true;
        }

        @Override
        public boolean handles(String capabilityId) {
            return capabilityId.equals(handled);
        }

        @Override
        public Optional<TaskResult> delegate(UnifiedTask task, CapabilityCard card) {
            seen.add(task);
            return returnEmpty ? Optional.empty()
                    : Optional.of(new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                            Enums.FailureClass.NONE, Map.of("via", "a2a"),
                            task.idempotencyKey(), task.guardrailCheck()));
        }
    }

    private static final class RecordingAgent implements DomainAgent {
        private int invocations;

        @Override
        public boolean supports(String capabilityId) {
            return true;
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            invocations++;
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("via", "local"),
                    task.idempotencyKey(), task.guardrailCheck());
        }
    }
}
