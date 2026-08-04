package com.huawei.finance.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-26a：领域 Agent 调用的主控侧强制超时。
 *
 * <p>此前能力卡上的 {@code timeoutMs} 是个没有消费点的字段——值在、Schema 校验也在，
 * 调用处却是同步等到底，{@code UnifiedTask.deadline} 还被硬编码成 30 秒且无人读取。
 * 外部同类系统一次派发干等 74 秒无切断（§2.7.4）；我们这版更隐蔽，因为看起来处处都配好了。
 *
 * <p>下面分三组：上限计算、超时是否真的生效、以及超时后的判定。第三组最要紧——
 * 超时不等于没执行。
 */
class AgentTimeoutTest {

    private static UnifiedTask task(String capabilityId) {
        return new UnifiedTask("task-1", "trace-1", Enums.TaskSource.FAST_PATH, "转账",
                capabilityId, Map.of(), RiskLevel.R2, Map.of(),
                GuardrailCheck.passed(), "idem-key-0001", List.of(),
                Instant.now().plusSeconds(5));
    }

    private static CapabilityCard card(String id, int timeoutMs, RiskLevel risk,
                                       List<String> sideEffects) {
        return new CapabilityCard(id, "测试能力", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.test", List.of("test"), "描述",
                List.of(), Map.of(), Map.of(), List.of(), sideEffects, risk, timeoutMs,
                Enums.Idempotency.REQUIRED, "测试域", "1.0.0", Enums.CapabilityStatus.ACTIVE,
                List.of(), List.of(), List.of(), null);
    }

    @Nested
    @DisplayName("超时上限")
    class Ceiling {

        @Test
        @DisplayName("能力卡声明值小于上限时按声明值")
        void declaredBelowCeilingWins() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(10_000);
            AgentInvoker invoker = TestAgentInvoker.of(props);

            assertThat(invoker.effectiveTimeoutMs(card("cap.a", 8000, RiskLevel.R2, List.of())))
                    .isEqualTo(8000);
        }

        /**
         * 这是本条功能点的核心：声明值**只能更小**。领域方填 999 秒不能让用户等 999 秒——
         * 超时值归领域方填，但用户能等多久归主控定。
         */
        @Test
        @DisplayName("能力卡声明值超过上限时被压到上限")
        void declaredAboveCeilingIsClamped() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(10_000);
            AgentInvoker invoker = TestAgentInvoker.of(props);

            assertThat(invoker.effectiveTimeoutMs(card("cap.b", 999_000, RiskLevel.R2, List.of())))
                    .isEqualTo(10_000);
        }

        @Test
        @DisplayName("能力卡没声明时取上限")
        void undeclaredFallsBackToCeiling() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(7000);

            assertThat(TestAgentInvoker.of(props).effectiveTimeoutMs(null)).isEqualTo(7000);
        }
    }

    @Nested
    @DisplayName("超时确实被施加")
    class Enforcement {

        @Test
        @DisplayName("领域 Agent 迟迟不返回时，主控在超时后自行了断")
        void slowAgentIsCutOff() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(120);
            SleepingAgent agent = new SleepingAgent(30_000);

            long started = System.currentTimeMillis();
            TaskResult result = TestAgentInvoker.of(props, agent)
                    .invoke(task("cap.slow"), card("cap.slow", 100, RiskLevel.R0, List.of()));
            long elapsed = System.currentTimeMillis() - started;

            assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
            assertThat(result.resultPayload()).containsEntry("error", "TIMEOUT");
            // 没有强制超时的话这里会等满 30 秒
            assertThat(elapsed).isLessThan(5000);
        }

        @Test
        @DisplayName("正常返回的 Agent 不受影响")
        void fastAgentPassesThrough() {
            TaskResult result = TestAgentInvoker.of(new InstantAgent())
                    .invoke(task("cap.fast"), card("cap.fast", 5000, RiskLevel.R0, List.of()));

            assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
        }

        @Test
        @DisplayName("超时后工作线程被中断，不会挂在那里空耗")
        void timedOutWorkerIsInterrupted() throws InterruptedException {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(100);
            SleepingAgent agent = new SleepingAgent(30_000);

            TestAgentInvoker.of(props, agent)
                    .invoke(task("cap.slow"), card("cap.slow", 100, RiskLevel.R0, List.of()));

            assertThat(agent.interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @DisplayName("超时不等于没执行")
    class UnknownOutcome {

        /**
         * 转账超时后中断线程并没有撤回那笔钱。判成可重试、回一句「请重试」，
         * 就是在诱导用户再转一次——幂等键挡得住经由本系统的重放，挡不住用户重新发起。
         */
        @Test
        @DisplayName("有副作用的能力超时判 PARTIAL（结果未知），不判可重试")
        void sideEffectingTimeoutIsPartial() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(100);

            TaskResult result = TestAgentInvoker.of(props, new SleepingAgent(30_000))
                    .invoke(task("cap.transfer"),
                            card("cap.transfer", 100, RiskLevel.R2, List.of("资金划转")));

            assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.PARTIAL);
        }

        @Test
        @DisplayName("纯查询超时判 RETRYABLE，重试是安全的")
        void queryTimeoutIsRetryable() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(100);

            TaskResult result = TestAgentInvoker.of(props, new SleepingAgent(30_000))
                    .invoke(task("cap.query"), card("cap.query", 100, RiskLevel.R0, List.of()));

            assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);
        }

        /**
         * 一张 R2 的卡忘了写 sideEffects，不能因此按「可安全重试」处理。
         * 这一处宁可判错方向也不能判错代价。
         */
        @Test
        @DisplayName("R2 但漏填 sideEffects 时，仍按结果未知处理")
        void r2WithoutDeclaredSideEffectsStillPartial() {
            OrchestratorProperties props = new OrchestratorProperties();
            props.setAgentTimeoutCeilingMs(100);

            TaskResult result = TestAgentInvoker.of(props, new SleepingAgent(30_000))
                    .invoke(task("cap.r2"), card("cap.r2", 100, RiskLevel.R2, List.of()));

            assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.PARTIAL);
        }
    }

    @Test
    @DisplayName("没有 Agent 承接时判 FATAL，不抛异常穿透")
    void missingAgentIsFatal() {
        TaskResult result = TestAgentInvoker.of()
                .invoke(task("cap.nobody"), card("cap.nobody", 1000, RiskLevel.R0, List.of()));

        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.FATAL);
        assertThat(result.resultPayload()).containsEntry("error", "NO_AGENT_FOR_CAPABILITY");
    }

    @Test
    @DisplayName("领域 Agent 抛异常时转 FATAL，不穿透到用户")
    void throwingAgentIsFatal() {
        TaskResult result = TestAgentInvoker.of(new ThrowingAgent())
                .invoke(task("cap.boom"), card("cap.boom", 1000, RiskLevel.R0, List.of()));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.FATAL);
    }

    /** 睡到天荒地老，用于触发超时；被中断时放行闩锁。 */
    private static final class SleepingAgent implements DomainAgent {

        private final long sleepMs;
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final AtomicBoolean completed = new AtomicBoolean();

        SleepingAgent(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public boolean supports(String capabilityId) {
            return true;
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            try {
                Thread.sleep(sleepMs);
                completed.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    Map.of(), task.idempotencyKey(), task.guardrailCheck());
        }
    }

    private static final class InstantAgent implements DomainAgent {

        @Override
        public boolean supports(String capabilityId) {
            return true;
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    Map.of(), task.idempotencyKey(), task.guardrailCheck());
        }
    }

    private static final class ThrowingAgent implements DomainAgent {

        @Override
        public boolean supports(String capabilityId) {
            return true;
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            throw new IllegalStateException("下游炸了");
        }
    }
}
