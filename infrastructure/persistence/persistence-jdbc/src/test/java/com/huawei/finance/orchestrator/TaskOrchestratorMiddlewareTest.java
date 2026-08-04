package com.huawei.finance.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.contracts.port.SessionLockManager;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.orchestrator.guardrail.GuardrailProperties;
import com.huawei.finance.orchestrator.guardrail.PolicyGuardrail;
import com.huawei.finance.orchestrator.continuation.TaskContinuationPort;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.JdbcTaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 中控对 Postgres 与 Redis 的集成测试。
 *
 * <p>不用内存替身：这里要验的正是 Flyway 迁移能不能跑、`jsonb` 列读写对不对、
 * 幂等键的唯一约束和「护栏未过不得有凭据」的 CHECK 约束是否真的生效——
 * 这些在 H2 上全都测不到。
 *
 * <p>中间件没起时整类跳过而不是失败：单测不该因为本地没开 Docker 就变红，
 * 但也不能悄悄换成假替身让人误以为验过了。
 */
class TaskOrchestratorMiddlewareTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String USER = "agent_platform";
    private static final String PASSWORD = "agent_platform";

    private static boolean infraUp;
    private static JdbcTemplate jdbc;
    private static RedissonClient redisson;

    private TaskRepository repository;
    private TaskOrchestrator orchestrator;
    private RecordingAgent agent;
    private RecordingDelegator delegator;

    @BeforeAll
    static void prepare() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            infraUp = true;
        } catch (Exception e) {
            infraUp = false;
            return;
        }

        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // 容忍「库里有、本模块 classpath 上没有」的迁移。各模块共用一条 Flyway 版本
                // 序列却各带各的迁移文件，本模块的测试 classpath 上就没有 context-engine 的 V2；
                // 应用跑过一次之后库里有了，校验便会失败。全量迁移集的校验归应用启动时管，
                // 这里只需要自己那几张表在。
                //
                // future 与 missing 都要忽略，而且必须两个都写。Flyway 按「版本号是否高于
                // 本地最大版本」把同一件事分成两类：context-engine 的 V2 低于本模块的 V4，算 missing；
                // 它后来加的 V5 高于 V4，就变成 future。只写 missing 的后果是——别的模块加一条
                // 迁移，本模块的测试就红，而红的原因跟本模块的改动毫无关系。
                .ignoreMigrationPatterns("*:missing", "*:future")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(ds);

        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        redisson = Redisson.create(config);
    }

    @BeforeEach
    void setUp() {
        assumeTrue(infraUp, "Postgres 未就绪，跳过中控集成测试");
        jdbc.execute("truncate table agent_idempotency, agent_task_transition, agent_task");

        repository = new JdbcTaskRepository(jdbc);
        agent = new RecordingAgent();
        // 委托替身承接一切能力且恒定成功。装上它之后，本类既有的护栏/租约用例
        // 全部变成「护栏拦得住委托吗」的双重验证——它们断言的 agent.invocations == 0
        // 与新增的 delegator.delegations == 0 是同一件事的两条通道
        delegator = new RecordingDelegator();
        orchestrator = new TaskOrchestrator(repository, new PolicyGuardrail(new GuardrailProperties()),
                testAgentInvoker(delegator, agent), lockManager(), new ContractValidator(),
                new SimpleMeterRegistry());
    }

    private static SessionLockManager lockManager() {
        return (key, wait, lease) -> {
            RLock lock = redisson.getLock(key);
            if (!lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
            return Optional.of(() -> {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            });
        };
    }

    private static AgentInvoker testAgentInvoker(CapabilityDelegator delegator, DomainAgent... agents) {
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "persistence-test-agent");
            thread.setDaemon(true);
            return thread;
        });
        return new AgentInvoker(List.of(agents), executor, new OrchestratorProperties(),
                new SimpleMeterRegistry(), delegator);
    }

    @Test
    @DisplayName("R2 转账先进 CONFIRM_PENDING，确认前查不到幂等凭据")
    void r2EntersConfirmPendingWithoutCredential() {
        RequestContext ctx = ctx();
        OrchestrationOutcome outcome = orchestrator.handle(request(ctx, false));

        assertThat(outcome.state()).isEqualTo(TaskState.CONFIRM_PENDING);
        assertThat(outcome.executed()).isFalse();
        assertThat(outcome.hasExecutableCredential()).isFalse();
        // 库里也不能有：凭据存在与否是可查的事实，不是内存里的一个标记
        assertThat(repository.idempotencyKeyOf(outcome.taskId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from agent_idempotency", Integer.class)).isZero();
        assertThat(agent.invocations).isZero();
    }

    @Test
    @DisplayName("确认之后才发凭据并执行，凭据与执行一一对应")
    void confirmationIssuesCredentialAndExecutes() {
        RequestContext first = ctx();
        OrchestrationOutcome pending = orchestrator.handle(request(first, false));
        assertThat(pending.state()).isEqualTo(TaskState.CONFIRM_PENDING);

        RequestContext second = ctx(first.sessionId());
        OrchestrationOutcome executed = orchestrator.handle(request(second, true));

        assertThat(executed.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(executed.hasExecutableCredential()).isTrue();
        assertThat(executed.result().success()).isTrue();
        assertThat(agent.invocations).isEqualTo(1);
        // 领域 Agent 手上必须拿到凭据，否则它自己无法防重放
        assertThat(agent.lastKey).isPresent();
        assertThat(repository.idempotencyKeyOf(executed.taskId())).contains(agent.lastKey.orElseThrow());
        // 状态路径要留痕，事故复盘看的是路径而不是终态
        assertThat(repository.transitionsOf(executed.taskId()))
                .containsExactly("-->CREATED", "CREATED->CONFIRM_PENDING", "CONFIRM_PENDING->RUNNING",
                        "RUNNING->SUCCEEDED");
    }

    @Test
    @DisplayName("同一凭据不会二次发放：超时重发落到同一把键时被拒")
    void idempotencyKeyIsIssuedOnce() {
        RequestContext first = ctx();
        OrchestrationOutcome pending = orchestrator.handle(request(first, false));
        OrchestrationOutcome executed = orchestrator.handle(request(ctx(first.sessionId()), true));

        assertThat(executed.taskId()).isEqualTo(pending.taskId());
        String key = repository.idempotencyKeyOf(executed.taskId()).orElseThrow();

        // 模拟超时重发：算出的是同一把键，中控必须识别为「已执行过」而不是再跑一遍
        assertThat(repository.attachIdempotencyKey(executed.taskId(), "cap.transfer", key)).isFalse();
        assertThat(agent.invocations).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_idempotency", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("护栏拒绝 → GUARDRAIL_BLOCKED 终态，且不留凭据")
    void guardrailBlockLeavesNoCredential() {
        RequestContext ctx = ctx();
        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx, fastExecute(), transferCard(),
                Map.of("payee", "张三", "amount", "80000"), "给张三转 80000", true, List.of(), trustedLease()));

        assertThat(outcome.state()).isEqualTo(TaskState.GUARDRAIL_BLOCKED);
        assertThat(outcome.guardrail().codes()).contains("AMOUNT_LIMIT_EXCEEDED");
        assertThat(repository.idempotencyKeyOf(outcome.taskId())).isEmpty();
        assertThat(agent.invocations).isZero();
    }

    @Test
    @DisplayName("上下文不可信 → 转账被拦下且不留凭据（FP-28 有副作用停止）")
    void untrustedContextStopsSideEffects() {
        RequestContext ctx = ctx();
        ContextLease degraded = ContextLease.degraded(
                ctx.sessionId(), "给张三转 1000", Instant.now().plusSeconds(30));

        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx, fastExecute(), transferCard(), Map.of("payee", "张三", "amount", "1000"),
                "给张三转 1000", true, List.of(), degraded));

        assertThat(outcome.state()).isEqualTo(TaskState.GUARDRAIL_BLOCKED);
        assertThat(outcome.guardrail().codes()).contains("CONTEXT_UNAVAILABLE");
        assertThat(repository.idempotencyKeyOf(outcome.taskId()))
                .as("上下文不明时发出的幂等键是一张依据不明的执行许可")
                .isEmpty();
        assertThat(agent.invocations).isZero();
    }

    @Test
    @DisplayName("护栏拦得住委托：超限转账不会因为改走 A2A 而绕过限额")
    void guardrailAlsoBlocksDelegation() {
        // 此前委托挂在 orchestrator.handle 外面、由入口二选一，护栏只长在 handle 里面，
        // 于是白名单域上一笔已确认的超限转账，AMOUNT_LIMIT_EXCEEDED 谁都不查（§12 第 4 条）
        delegator.enable();
        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx(), fastExecute(), transferCard(),
                Map.of("payee", "张三", "amount", "80000"), "给张三转 80000", true,
                List.of(), trustedLease()));

        assertThat(outcome.state()).isEqualTo(TaskState.GUARDRAIL_BLOCKED);
        assertThat(outcome.guardrail().codes()).contains("AMOUNT_LIMIT_EXCEEDED");
        assertThat(delegator.delegations)
                .as("护栏拒了就不该把这笔投出去——投出去之后下游可能已经动手")
                .isZero();
        assertThat(repository.idempotencyKeyOf(outcome.taskId())).isEmpty();
    }

    @Test
    @DisplayName("租约不可信同样拦得住委托：委托不是绕开副作用闸的通道")
    void untrustedContextAlsoBlocksDelegation() {
        RequestContext ctx = ctx();
        ContextLease degraded = ContextLease.degraded(
                ctx.sessionId(), "给张三转 1000", Instant.now().plusSeconds(30));

        delegator.enable();
        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx, fastExecute(), transferCard(), Map.of("payee", "张三", "amount", "1000"),
                "给张三转 1000", true, List.of(), degraded));

        assertThat(outcome.guardrail().codes()).contains("CONTEXT_UNAVAILABLE");
        assertThat(delegator.delegations).isZero();
    }

    @Test
    @DisplayName("委托办成后本地任务表有真值：taskId 不是 delegationId")
    void delegatedBusinessStillHasLocalTaskTruth() {
        delegator.enable();
        OrchestrationOutcome outcome = orchestrator.handle(request(ctx(), true));

        assertThat(delegator.delegations).as("这一笔确实走的是委托").isEqualTo(1);
        assertThat(outcome.state()).isEqualTo(TaskState.SUCCEEDED);

        // §8.3:网关台账记的是投递事实，不是业务任务事实。「这笔业务办过没有」
        // 必须能从本 Agent 自己的任务表回答
        assertThat(repository.findById(outcome.taskId()))
                .as("委托出去的业务也要在本地留档，否则审计只能问网关台账")
                .isPresent();
        assertThat(repository.findById(outcome.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);
        assertThat(repository.idempotencyKeyOf(outcome.taskId()))
                .as("入口侧幂等键照发:同一任务同参数重来一次要被本地拦住")
                .isPresent();
    }

    @Test
    @DisplayName("DELEGATE_GOAL 进入唯一委托执行链，并保留入口侧任务真值")
    void delegateGoalIsActuallyDispatched() {
        delegator.enable();
        RequestContext context = ctx();
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.DELEGATE_GOAL)
                .reasonCode(ReasonCode.SHORT_CIRCUIT_STRONG_RULE)
                .candidateIds(List.of("agent.finance_assistant"))
                .confidence(1.0)
                .shortCircuit(ShortCircuitLevel.L2_STRONG_RULE)
                .build();

        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                context, decision, financeAgentCard(), Map.of(),
                "请金融助手帮我查询基金产品C", false, List.of(), trustedLease()));

        assertThat(delegator.delegations).isEqualTo(1);
        assertThat(agent.invocations).as("AGENT 目标不能回落成本地叶子能力执行").isZero();
        assertThat(outcome.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(outcome.result()).isNotNull();
        assertThat(repository.findById(outcome.taskId()))
                .hasValueSatisfying(task -> {
                    assertThat(task.capabilityId()).isEqualTo("agent.finance_assistant");
                    assertThat(task.state()).isEqualTo(TaskState.SUCCEEDED);
                });
    }

    @Test
    @DisplayName("跨 Agent 只送卡声明的业务槽位及可信主体引用，抽到的多余槽位不出境")
    void onlyCardDeclaredSlotsCrossTheBoundary() {
        // 抽槽发生在选中能力之前，抽到的必然多于任何一张卡声明的范围。
        // 多送的那些，语义与校验责任都在领域方，而它从未承认过这个入参（§8.1 白名单）
        delegator.enable();
        orchestrator.handle(new OrchestrationRequest(
                ctx(), fastExecute(), transferCard(),
                Map.of("payee", "张三", "amount", "1000", "cardType", "信用卡"),
                "给张三转 1000", true, List.of(), trustedLease()));

        assertThat(delegator.lastTask.parameters())
                .containsOnlyKeys("payee", "amount", "principalRef")
                .containsEntry("principalRef", "u-1");
    }

    @Test
    @DisplayName("上下文不可信不影响只读查询：按策略降级，查一次余额最坏是多查一次")
    void untrustedContextStillAllowsReadOnly() {
        RequestContext ctx = ctx();
        ContextLease degraded = ContextLease.degraded(
                ctx.sessionId(), "查余额", Instant.now().plusSeconds(30));

        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx, balanceFastExecute(), balanceCard(), Map.of(), "查余额", false,
                List.of(), degraded));

        assertThat(outcome.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(agent.invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("租约过期同样拦：签发之后任务态可能已被另一路请求改写")
    void expiredLeaseStopsSideEffects() {
        RequestContext ctx = ctx();
        ContextLease expired = new ContextLease("lease-x", ctx.sessionId(), "转账", Map.of(),
                List.of(), List.of(), 4096, 10, List.of(), true, 1L, Instant.now().minusSeconds(1));

        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx, fastExecute(), transferCard(), Map.of("payee", "张三", "amount", "1000"),
                "给张三转 1000", true, List.of(), expired));

        assertThat(outcome.state()).isEqualTo(TaskState.GUARDRAIL_BLOCKED);
        assertThat(agent.invocations).isZero();
    }

    @Test
    @DisplayName("不传租约直接构造失败：这条防线不能靠调用点自觉")
    void missingLeaseIsRejectedAtConstruction() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new OrchestrationRequest(ctx(), fastExecute(), transferCard(), Map.of(),
                        "转账", true, List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少上下文租约");
    }

    @Test
    @DisplayName("执行时把租约 id 下发给领域方，contextRefs 不再恒为空")
    void leaseIdIsHandedToDomainAgent() {
        RequestContext ctx = ctx();
        orchestrator.handle(request(ctx, true));

        assertThat(agent.lastTask).isNotNull();
        assertThat(agent.lastTask.contextRefs())
                .as("领域方要能回查本次执行依据的是哪一份上下文")
                .anyMatch(ref -> ref.startsWith("lease:"));
    }

    @Test
    @DisplayName("澄清出口把待答槽位与候选答案存进任务，供下一轮续轮判定")
    void clarifyPersistsPendingSlot() {
        RequestContext ctx = ctx();
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .candidateIds(List.of("cap.transfer"))
                .missingSlots(List.of("payee"))
                .confidence(0.8)
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();

        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx, decision, transferCard(), Map.of("amount", "1000"), "转 1000", false,
                List.of("张三", "李四"), trustedLease()));

        assertThat(outcome.state()).isEqualTo(TaskState.CLARIFY_PENDING);
        assertThat(outcome.pendingSlot()).isEqualTo("payee");

        var reloaded = repository.findActiveBySession(ctx.agentId(), ctx.sessionId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().pendingSlot()).isEqualTo("payee");
        assertThat(reloaded.get().expectedAnswers()).containsExactly("张三", "李四");
        assertThat(reloaded.get().clarifyRounds()).isEqualTo(1);
        assertThat(reloaded.get().parameters()).containsEntry("amount", "1000");
    }

    @Test
    @DisplayName("领域 Agent 返回 NEED_USER 后，字符串缺槽写入任务并可由续办接口读取")
    void localNeedUserPersistsPendingSlotForContinuation() {
        assertNeedUserPersists(List.of("cardRef"));
    }

    @Test
    @DisplayName("A2A 返回 NEED_USER 后，结构化缺槽写入任务并可由续办接口读取")
    void a2aNeedUserPersistsPendingSlotForContinuation() {
        assertNeedUserPersists(List.of(Map.of(
                "slot", "cardRef",
                "questionKey", "clarify.cardRef")));
    }

    private void assertNeedUserPersists(List<?> missingSlots) {
        DomainAgent needUserAgent = new DomainAgent() {
            @Override
            public boolean supports(String capabilityId) {
                return "cap.account.balance.query".equals(capabilityId);
            }

            @Override
            public TaskResult execute(UnifiedTask task) {
                return new TaskResult(task.taskId(), Enums.TaskStatus.NEED_USER,
                        Enums.FailureClass.NEED_USER, Map.of("missingSlots", missingSlots),
                        task.idempotencyKey(), task.guardrailCheck());
            }
        };
        TaskOrchestrator needUserOrchestrator = new TaskOrchestrator(repository,
                new PolicyGuardrail(new GuardrailProperties()),
                testAgentInvoker(delegator, needUserAgent), lockManager(),
                new ContractValidator(), new SimpleMeterRegistry());
        RequestContext context = ctx();

        OrchestrationOutcome outcome = needUserOrchestrator.handle(new OrchestrationRequest(
                context, balanceFastExecute(), balanceCard(), Map.of(), "查信用卡账单", false,
                List.of(), trustedLease()));

        assertThat(outcome.state()).isEqualTo(TaskState.CLARIFY_PENDING);
        assertThat(outcome.pendingSlot()).isEqualTo("cardRef");
        assertThat(repository.findById(outcome.taskId()))
                .hasValueSatisfying(task -> {
                    assertThat(task.pendingSlot()).isEqualTo("cardRef");
                    assertThat(task.clarifyRounds()).isEqualTo(1);
                });

        var snapshot = new TaskContinuationPort(repository)
                .describe(context.spaceId(), context.agentId(), outcome.taskId());
        assertThat(snapshot.pendingInteraction().expectedSlot()).isEqualTo("cardRef");
        assertThat(snapshot.allowedEvents())
                .contains(com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event.FILL_SLOT);
    }

    @Test
    @DisplayName("话题切换时旧任务让位，而不是让唯一索引把新任务顶回来")
    void newIntentSupersedesActiveTask() {
        RequestContext ctx = ctx();
        RouteDecision clarify = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .missingSlots(List.of("payee"))
                .confidence(0.8)
                .build();
        OrchestrationOutcome first = orchestrator.handle(new OrchestrationRequest(
                ctx, clarify, transferCard(), Map.of(), "转账", false, List.of(), trustedLease()));
        assertThat(first.state()).isEqualTo(TaskState.CLARIFY_PENDING);

        // 同一会话换成另一个能力：一个会话只允许一个活跃任务，旧的必须先退场
        OrchestrationOutcome second = orchestrator.handle(new OrchestrationRequest(
                ctx(ctx.sessionId()), fastExecute(), balanceCard(), Map.of(), "查余额", false, List.of(), trustedLease()));

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(second.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(repository.findById(first.taskId()).orElseThrow().state())
                .isEqualTo(TaskState.CANCELLED);
        assertThat(repository.transitionsOf(first.taskId())).last()
                .isEqualTo("CLARIFY_PENDING->CANCELLED");
        // 新任务已终态，会话回到没有活跃任务的干净状态
        assertThat(repository.findActiveBySession(ctx.agentId(), ctx.sessionId())).isEmpty();
    }

    /**
     * 标准问答（FP-1I）使用 {@code DIRECT_KNOWLEDGE}，不得影响执行任务：
     * 那是「办不了」，这是「答完了」。共用出口的代价就落在这里——拒绝会把在办任务判成
     * 取消，而用户只是在转账确认途中问了一句手续费怎么算。
     */
    @Test
    @DisplayName("答一句标准问不许动在办的任务")
    void standardAnswerLeavesTheActiveTaskAlone() {
        RequestContext ctx = ctx();
        RouteDecision clarify = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .missingSlots(List.of("payee"))
                .confidence(0.8)
                .build();
        OrchestrationOutcome pending = orchestrator.handle(new OrchestrationRequest(
                ctx, clarify, transferCard(), Map.of(), "转账", false, List.of(), trustedLease()));
        assertThat(pending.state()).isEqualTo(TaskState.CLARIFY_PENDING);

        RouteDecision answered = RouteDecision.builder()
                .decision(Decision.HANDOFF)
                .reasonCode(ReasonCode.STANDARD_ANSWER)
                .confidence(1.0)
                .build();
        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                ctx(ctx.sessionId()), answered, null, Map.of(), "转账手续费怎么算", false,
                List.of(), trustedLease()));

        assertThat(outcome.taskId()).as("答一句话不建档").isNull();
        assertThat(repository.findById(pending.taskId()).orElseThrow().state())
                .as("那笔转账还在等用户补收款人，问一句手续费不该把它取消掉")
                .isEqualTo(TaskState.CLARIFY_PENDING);
        assertThat(repository.findActiveBySession(ctx.agentId(), ctx.sessionId()))
                .hasValueSatisfying(active -> assertThat(active.taskId()).isEqualTo(pending.taskId()));
    }

    @Test
    @DisplayName("数据库层面拒绝「护栏未过却带幂等键」")
    void databaseRejectsCredentialWithoutGuardrail() {
        RequestContext ctx = ctx();
        OrchestrationOutcome pending = orchestrator.handle(request(ctx, false));

        // 绕过服务层直接改库同样要被堵死，这是 §8.4 的最后一道闸
        assertThat(catchUpdate("update agent_task set idempotency_key = 'idem-forged' where task_id = ?",
                pending.taskId())).isNotNull();
    }

    private Exception catchUpdate(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    @Test
    @DisplayName("能力卡未声明的槽位，不得随任务下发给领域 Agent")
    void undeclaredSlotsAreNotSubmitted() {
        RequestContext ctx = ctx();
        // 「查信用卡余额」会抽出 cardType，但余额查询卡没声明它。主 Agent 替账户领域
        // 定义一个它从未承认的入参，出了错却由领域方兜——这条边界必须挡在下发之前
        orchestrator.handle(new OrchestrationRequest(
                ctx, balanceFastExecute(), balanceCard(),
                Map.of("cardType", "CREDIT", "amount", "1000"), "查信用卡余额", false, List.of(), trustedLease()));

        assertThat(agent.lastParameters)
                .containsOnlyKeys("principalRef")
                .containsEntry("principalRef", "u-1");
    }

    @Test
    @DisplayName("能力卡声明过的槽位照常下发，不因过滤而丢失")
    void declaredSlotsSurviveTheFilter() {
        RequestContext ctx = ctx();
        orchestrator.handle(new OrchestrationRequest(
                ctx, fastExecute(), transferCard(),
                Map.of("payee", "张三", "amount", "1000", "cardType", "DEBIT"),
                "用储蓄卡给张三转 1000", true, List.of(), trustedLease()));

        assertThat(agent.lastParameters)
                .containsEntry("payee", "张三")
                .containsEntry("amount", "1000")
                .doesNotContainKey("cardType");
    }

    /**
     * FP-29：会话锁。
     *
     * <p>一个会话同时只允许一个活跃任务，这条由数据库唯一索引兜底。但「兜底」和「设计」是
     * 两回事：用户在手机上连点两下，两个请求并发进来，双双读到「无活跃任务」、双双 insert，
     * 唯一索引会拒掉其中一个——被拒的那个用户看到的是一次没有任何解释的失败，
     * 而且哪一次失败是随机的。
     *
     * <p>会话锁把这种竞态挡在 insert 之前：后到的那个等锁，拿到锁时已经能看见前一个建好的
     * 任务，于是复用它。用例判的是这个结果——两次调用落到同一个任务、库里只有一行。
     *
     * <p>用 CLARIFY 而不是 EXECUTE_CAPABILITY：只有 CLARIFY 会留下一个持续活跃的任务，
     * 竞态才有得可争；EXECUTE_CAPABILITY 跑完就是终态，第二次本来就该建新任务。
     */
    @Test
    @DisplayName("同一会话并发两次澄清，落到同一个任务而非双双建档")
    void concurrentRequestsShareOneTask() throws Exception {
        String session = "s-" + UUID.randomUUID();
        RouteDecision clarify = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .missingSlots(List.of("payee"))
                .confidence(0.8)
                .build();

        int threads = 6;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<OrchestrationOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    // 光靠同时提交不够，线程池起线程有先后，凑不出真正的同时进入
                    startTogether.await(5, TimeUnit.SECONDS);
                    return orchestrator.handle(new OrchestrationRequest(
                            ctx(session), clarify, transferCard(), Map.of("amount", "1000"),
                            "转 1000", false, List.of("张三"), trustedLease()));
                }));
            }

            Set<String> taskIds = new HashSet<>();
            for (Future<OrchestrationOutcome> future : futures) {
                OrchestrationOutcome outcome = future.get(20, TimeUnit.SECONDS);
                // 谁也不该因为竞态而拿到一个空结果
                assertThat(outcome.taskId()).as("并发调用的返回").isNotNull();
                taskIds.add(outcome.taskId());
            }

            assertThat(taskIds).as("所有并发调用应落到同一个任务").hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from agent_task where session_id = ?", Integer.class, session))
                .isEqualTo(1);
        assertThat(repository.findActiveBySession(com.huawei.finance.common.context.RequestContext.AGENT_ENTRY, session)).isPresent();
    }

    /**
     * 锁等不到时宁可不做，而不是绕过它硬闯。
     *
     * <p>反向对照：占住锁再发一个请求，它必须在 {@code LOCK_WAIT} 内放弃并返回空结果，
     * 而不是超时之后接着往下走。没有这条，把 {@code tryLock} 的返回值忽略掉的实现
     * 也能让上面那条全绿——而那种实现恰恰在压力最大时失效。
     *
     * <p>占锁必须**另起一个线程**。Redisson 的锁同线程可重入，在测试线程里持锁再在同一线程
     * 调用中控，{@code tryLock} 会直接放行——这条用例的第一版就是这么写的，跑出来的「锁没拦住」
     * 是测试自己造的假象。
     */
    @Test
    @DisplayName("拿不到会话锁时放弃本轮，不绕过锁继续处置")
    void lockContentionYieldsInsteadOfProceeding() throws Exception {
        String session = "s-" + UUID.randomUUID();
        String lockKey = com.huawei.finance.common.context.ScopeKeys.sessionLock(new com.huawei.finance.common.context.RequestContext("t", session, "u", "MOBILE_BANK", "home", "", false));
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            holder.submit(() -> {
                RLock lock = redisson.getLock(lockKey);
                lock.lock(30, TimeUnit.SECONDS);
                acquired.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.forceUnlock();
                }
                return null;
            });
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

            OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                    ctx(session), balanceFastExecute(), balanceCard(), Map.of(),
                    "查余额", false, List.of(), trustedLease()));

            assertThat(outcome.taskId()).isNull();
            assertThat(agent.invocations).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from agent_task where session_id = ?", Integer.class, session))
                    .isZero();
        } finally {
            release.countDown();
            holder.shutdown();
        }
    }

    private static RouteDecision balanceFastExecute() {
        return RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .candidateIds(List.of("cap.account.balance.query"))
                .confidence(0.95)
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();
    }

    private static OrchestrationRequest request(RequestContext ctx, boolean confirmed) {
        return new OrchestrationRequest(ctx, fastExecute(), transferCard(),
                Map.of("payee", "张三", "amount", "1000"), "给张三转 1000", confirmed, List.of(), trustedLease());
    }

    /** 上下文正常时的租约。绝大多数用例关心的不是上下文，给一份可信的即可。 */
    private static ContextLease trustedLease() {
        return new ContextLease("lease-" + UUID.randomUUID(), "s-1", "转账", Map.of(), List.of(),
                List.of(), 4096, 100, List.of(), true, 1L, Instant.now().plusSeconds(30));
    }

    private static RouteDecision fastExecute() {
        return RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .reasonCode(ReasonCode.CONFIRMATION_REQUIRED)
                .candidateIds(List.of("cap.transfer"))
                .confidence(0.95)
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();
    }

    private static RequestContext ctx() {
        return ctx("s-" + UUID.randomUUID());
    }

    private static RequestContext ctx(String sessionId) {
        return new RequestContext("trace-" + UUID.randomUUID(), sessionId, "u-1",
                "MOBILE_BANK", "home", "", false);
    }

    private static CapabilityCard balanceCard() {
        return new CapabilityCard("cap.account.balance.query", "查余额", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.account", List.of("account"), "查询账户可用余额",
                List.of("查余额"), Map.of(), Map.of(), List.of("已登录"), List.of(),
                RiskLevel.R0, 3000, Enums.Idempotency.NONE, "账户领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of("查一下余额"), List.of("余额"), List.of(), null);
    }

    private static CapabilityCard financeAgentCard() {
        return new CapabilityCard("agent.finance_assistant", "金融助手", Enums.CapabilityType.AGENT,
                Enums.Granularity.AGENT, null, List.of("finance_assistant"),
                "金融领域自治目标", List.of(), Map.of(), Map.of(), List.of(), List.of(),
                RiskLevel.R0, 5000, Enums.Idempotency.SUPPORTED, "金融助手", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN);
    }

    private static CapabilityCard transferCard() {
        return new CapabilityCard("cap.transfer", "转账", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.payment", List.of("payment"), "向指定收款人转账",
                List.of("转账"), Map.of(), Map.of(), List.of("已登录"), List.of("资金划转"),
                RiskLevel.R2, 8000, Enums.Idempotency.REQUIRED, "支付领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of("我要转账"), List.of("转账"),
                List.of("payee", "amount"), null);
    }

    /**
     * 记录委托的替身。
     *
     * <p>它承接一切能力并恒定成功——本组用例要证的是「委托根本没被叫到」，
     * 所以这个替身越乐意办事，断言 {@code delegations == 0} 就越有力。
     */
    private static final class RecordingDelegator implements CapabilityDelegator {

        int delegations;
        UnifiedTask lastTask;

        /**
         * 默认不承接。
         *
         * <p>默认承接一切的话，本类既有的用例会全部改道走委托，
         * 而它们验的是本地执行那条路——两条路都要有用例，不能因为装了委托通道就把
         * 本地那条悄悄换掉。要验委托的用例自己调 {@link #enable()}。
         */
        private boolean enabled;

        void enable() {
            this.enabled = true;
        }

        @Override
        public boolean handles(String capabilityId) {
            return enabled;
        }

        @Override
        public Optional<TaskResult> delegate(UnifiedTask task, CapabilityCard card) {
            delegations++;
            lastTask = task;
            return Optional.of(new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("serialNo", "A2A0001"),
                    task.idempotencyKey(), task.guardrailCheck()));
        }
    }

    /** 记录调用次数的领域 Agent，用来证明「没执行」不是靠状态推断出来的。 */
    private static final class RecordingAgent implements DomainAgent {

        int invocations;
        Optional<String> lastKey = Optional.empty();
        Map<String, Object> lastParameters = Map.of();
        UnifiedTask lastTask;

        /**
         * 叶子能力与 AGENT 目标都承接。
         *
         * <p>只认 {@code cap.} 的话，{@code delegateGoalIsActuallyDispatched} 里那句
         * 「AGENT 目标不能回落成本地叶子能力执行」是**不可能红的**——本替身根本不声明
         * {@code agent.finance_assistant}，于是无论委托端怎么改，本地都接不到那一笔。
         * 断言写着一件事，守的却是空气。承接下来，这条回落才真的被守住。
         */
        @Override
        public boolean supports(String capabilityId) {
            return capabilityId.startsWith("cap.") || capabilityId.startsWith("agent.");
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            invocations++;
            lastKey = Optional.ofNullable(task.idempotencyKey());
            lastParameters = task.parameters();
            lastTask = task;
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    Map.of("serialNo", "TR000001"), task.idempotencyKey(), task.guardrailCheck());
        }
    }
}
