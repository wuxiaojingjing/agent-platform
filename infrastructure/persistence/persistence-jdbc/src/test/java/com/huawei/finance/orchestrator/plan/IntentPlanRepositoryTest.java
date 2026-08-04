package com.huawei.finance.orchestrator.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.ConditionExpression;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 计划落库。
 *
 * <p>打真库而不是内存替身：这里要验的一多半是数据库约束本身——游标越界、长度下限、
 * 同会话唯一在办。这些约束写在库里正是因为绕过服务层直接改库的路径同样要被堵死，
 * 用替身测等于把要验的东西替换掉了。
 */
class IntentPlanRepositoryTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String USER = "agent_platform";
    private static final String PASSWORD = "agent_platform";

    private static boolean infraUp;
    private static JdbcTemplate jdbc;

    private IntentPlanRepository repository;

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
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // 理由同 TaskOrchestratorMiddlewareTest：本模块 classpath 上没有别的模块的迁移。
                // future 与 missing 都要忽略——版本号高于本地最大版本的算 future，低于的算 missing，
                // 是同一件事的两个类别
                .ignoreMigrationPatterns("*:missing", "*:future")
                .load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void setUp() {
        assumeTrue(infraUp, "Postgres 未就绪，跳过计划落库测试");
        jdbc.execute("truncate table agent_intent_plan_condition, agent_intent_plan_step, agent_intent_plan");
        repository = new JdbcIntentPlanRepository(jdbc);
    }

    private static IntentPlan twoThings() {
        return new IntentPlan("查余额，再给老徐转 1000；不足就别转", List.of(
                new SubIntent(0, "查余额", "cap.balance", "余额查询",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked("cap.balance", "test:fixture")),
                new SubIntent(1, "给老徐转 1000", "cap.transfer", "转账",
                        Enums.IntentRelation.CONDITIONAL, "不足就别转",
                        PlanResolution.locked("cap.transfer", "test:fixture"))),
                IntentPlan.Source.RULE);
    }

    @Test
    @DisplayName("落盘再读出来，条件与次序一个都不能少")
    void roundTripKeepsConditionAndOrder() {
        repository.open("agent.entry", "s-1", "t-1", twoThings());

        PlanRecord loaded = repository.findActiveBySession("agent.entry", "s-1").orElseThrow();

        assertThat(loaded.cursor()).isZero();
        assertThat(loaded.stateVersion()).isZero();
        assertThat(loaded.state()).isEqualTo(PlanState.IN_PROGRESS);
        assertThat(loaded.plan().items()).extracting(SubIntent::capabilityId)
                .containsExactly("cap.balance", "cap.transfer");
        assertThat(loaded.plan().items().get(1).condition())
                .as("条件丢了就等于无条件转账")
                .isEqualTo("不足就别转");
        assertThat(loaded.next().orElseThrow().capabilityId()).isEqualTo("cap.balance");
    }

    @Test
    @DisplayName("新计划的候选强度、分数、证据和允许候选完整往返")
    void resolutionRoundTrips() {
        PlanResolution resolution = new PlanResolution(PlanResolution.Strength.LOCKED,
                0.91, 0.43, List.of("cap.balance", "cap.asset"),
                List.of("utterance:查余额"));
        IntentPlan plan = new IntentPlan("查余额，然后查基金", List.of(
                new SubIntent(0, "查余额", "cap.balance", "余额查询",
                        Enums.IntentRelation.PARALLEL, null, resolution),
                new SubIntent(1, "查基金", "cap.fund", "基金查询",
                        Enums.IntentRelation.SEQUENTIAL, null,
                        new PlanResolution(PlanResolution.Strength.PREFERRED,
                                0.42, 0.08, List.of("cap.fund", "cap.wealth"),
                                List.of("keyword:基金")))), IntentPlan.Source.HYBRID);

        repository.open("agent.entry", "s-1", "t-1", plan);
        IntentPlan loaded = repository.findActiveBySession("agent.entry", "s-1").orElseThrow().plan();

        assertThat(loaded).isEqualTo(plan);
        assertThat(loaded.items().get(0).resolution()).isEqualTo(resolution);
    }

    @Test
    @DisplayName("缺少 resolution 的旧 JSON 直接拒绝，不保留开发期双轨读取")
    void jsonWithoutResolutionIsRejected() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());
        String historical = """
                [
                  {"order":0,"text":"查余额","capabilityId":"cap.balance","summary":"余额查询","relation":"PARALLEL","condition":null},
                  {"order":1,"text":"未知事项","capabilityId":null,"summary":"未知事项","relation":"SEQUENTIAL","condition":null}
                ]
                """;
        jdbc.update("update agent_intent_plan set items = cast(? as jsonb) where plan_id = ?",
                historical, opened.planId());

        assertThat(catchThrowable(() ->
                repository.findActiveBySession("agent.entry", "s-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计划反序列化失败");
    }

    @Test
    @DisplayName("推进游标后下一件变成第二件，办完自动收口")
    void advanceMovesCursorAndCompletes() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());

        assertThat(repository.advance(opened.planId(), 0)).isTrue();
        PlanRecord mid = repository.findActiveBySession("agent.entry", "s-1").orElseThrow();
        assertThat(mid.next().orElseThrow().capabilityId()).isEqualTo("cap.transfer");
        assertThat(mid.previous().orElseThrow().capabilityId())
                .as("条件依赖要看的正是上一件的结果")
                .isEqualTo("cap.balance");
        assertThat(mid.remaining()).isEqualTo(1);

        assertThat(repository.advance(opened.planId(), 1)).isTrue();
        assertThat(repository.findActiveBySession("agent.entry", "s-1"))
                .as("全办完就不该再算在办，否则下一句话会被当成对旧计划的选择")
                .isEmpty();
    }

    @Test
    @DisplayName("并发推进只有一方成功：否则同一件事会被下发两次")
    void concurrentAdvanceHasOneWinner() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());

        assertThat(repository.advance(opened.planId(), 0)).isTrue();
        assertThat(repository.advance(opened.planId(), 0))
                .as("第二个请求带着同一个旧游标来，必须落空")
                .isFalse();
    }

    @Test
    @DisplayName("等待确认与恢复使用 stateVersion CAS，过期动作不能改变计划")
    void waitingAndResumeAreVersioned() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());

        assertThat(repository.waitFor(opened.planId(), opened.stateVersion(),
                PlanState.WAITING_CONFIRMATION, "task-transfer", null, List.of())).isTrue();
        PlanRecord waiting = repository.findActiveBySession("agent.entry", "s-1").orElseThrow();
        assertThat(waiting.state()).isEqualTo(PlanState.WAITING_CONFIRMATION);
        assertThat(waiting.stateVersion()).isEqualTo(1);
        assertThat(waiting.pendingInteraction().taskId()).isEqualTo("task-transfer");

        assertThat(repository.transition(opened.planId(), PlanState.WAITING_CONFIRMATION,
                PlanState.IN_PROGRESS, opened.stateVersion())).isFalse();
        assertThat(repository.transition(opened.planId(), PlanState.WAITING_CONFIRMATION,
                PlanState.IN_PROGRESS, waiting.stateVersion())).isTrue();
        PlanRecord resumed = repository.findActiveBySession("agent.entry", "s-1").orElseThrow();
        assertThat(resumed.state()).isEqualTo(PlanState.IN_PROGRESS);
        assertThat(resumed.stateVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("步骤事实与游标原子提交，竞争者不能覆盖已完成步骤")
    void stepFactsAndCursorAdvanceAtomically() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());
        PlanStepRecord step = new PlanStepRecord(opened.planId(), 0, "cap.balance",
                "task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("balance", "100.00"), null, Instant.now());

        assertThat(repository.saveStepAndAdvance(step, 0)).isTrue();
        assertThat(repository.saveStepAndAdvance(new PlanStepRecord(
                opened.planId(), 0, "cap.balance", "task-duplicate",
                Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("balance", "999.00"), null, Instant.now()), 0)).isFalse();

        assertThat(repository.steps(opened.planId())).singleElement().satisfies(saved -> {
            assertThat(saved.taskId()).isEqualTo("task-1");
            assertThat(saved.facts()).containsEntry("balance", "100.00");
        });
        assertThat(repository.findActiveBySession("agent.entry", "s-1").orElseThrow().cursor())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Static Plan 参数由 Runtime 持久化，并强制 Agent 作用域")
    void runtimeParametersRoundTripWithinAgentScope() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());

        repository.saveParameters("agent.entry", opened.planId(),
                Map.of("amount", "1000", "payee", "老徐"));

        assertThat(repository.parameters("agent.entry", opened.planId()))
                .containsEntry("amount", "1000")
                .containsEntry("payee", "老徐");
        assertThat(repository.parameters("agent.other", opened.planId())).isEmpty();
        assertThat(catchThrowable(() -> repository.saveParameters(
                "agent.other", opened.planId(), Map.of("amount", "1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STATIC_PLAN_NOT_ACTIVE");
        assertThat(repository.parameters("agent.entry", opened.planId()))
                .containsEntry("amount", "1000");
    }

    @Test
    @DisplayName("延迟条件解析结果按事实摘要持久化并恢复")
    void conditionResolutionRoundTrips() {
        PlanRecord opened = repository.open("agent.entry", "s-1", "t-1", twoThings());
        ConditionExpression expression = new ConditionExpression(ConditionExpression.Operator.EQ, List.of(
                ConditionExpression.Operand.stepOutput("step-1", "/ready"),
                ConditionExpression.Operand.literal(true)));
        PlanConditionResolutionRecord resolution = new PlanConditionResolutionRecord(
                opened.planId(), 1, "ready then continue", expression,
                PlanConditionResolutionRecord.Outcome.RESOLVED, "facts-1",
                "model-1", "prompt-1", Instant.now());

        repository.saveConditionResolution(resolution);

        assertThat(repository.findConditionResolution(opened.planId(), 1, "facts-1"))
                .get().satisfies(saved -> {
                    assertThat(saved.expression()).isEqualTo(expression);
                    assertThat(saved.modelVersion()).isEqualTo("model-1");
                    assertThat(saved.promptVersion()).isEqualTo("prompt-1");
                });
    }

    @Test
    @DisplayName("用户重提一串诉求，旧计划作废而不是插入失败")
    void reopeningSupersedesTheOldPlan() {
        PlanRecord first = repository.open("agent.entry", "s-1", "t-1", twoThings());
        PlanRecord second = repository.open("agent.entry", "s-1", "t-2", twoThings());

        assertThat(second.planId()).isNotEqualTo(first.planId());
        assertThat(repository.findActiveBySession("agent.entry", "s-1").orElseThrow().planId())
                .isEqualTo(second.planId());
        assertThat(jdbc.queryForObject(
                "select state from agent_intent_plan where plan_id = ?", String.class, first.planId()))
                .isEqualTo(PlanState.ABANDONED.name());
    }

    @Test
    @DisplayName("会话之间互不影响")
    void plansAreScopedToTheirSession() {
        repository.open("agent.entry", "s-1", "t-1", twoThings());
        repository.open("agent.entry", "s-2", "t-2", twoThings());

        assertThat(repository.findActiveBySession("agent.entry", "s-1")).isPresent();
        assertThat(repository.findActiveBySession("agent.entry", "s-2")).isPresent();
    }

    @Test
    @DisplayName("游标越界在构造期就拦：越界的进度会让下游按不存在的下标取事")
    void cursorOutOfRangeIsRejected() {
        assertThat(catchThrowable(() ->
                new PlanRecord("p-1", "agent.entry", "s-1", "t-1", twoThings(), 3, PlanState.IN_PROGRESS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游标越界");
    }

    @Test
    @DisplayName("没有在办计划时作废是空操作，不报错")
    void abandoningNothingIsFine() {
        repository.abandonActive("agent.entry", "s-never", "cleanup");

        assertThat(repository.findActiveBySession("agent.entry", "s-never")).isEqualTo(Optional.empty());
    }
}
