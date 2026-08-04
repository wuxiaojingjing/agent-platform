package com.huawei.finance.orchestrator.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcTaskContextStoreTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String USER = "agent_platform";
    private static final String PASSWORD = "agent_platform";
    private static boolean infraUp;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private JdbcTaskContextStore store;
    private PlatformTaskContextManager manager;

    @BeforeAll
    static void prepare() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            infraUp = true;
        } catch (Exception e) {
            return;
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        dataSource.setDriverClassName("org.postgresql.Driver");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .baselineOnMigrate(true).ignoreMigrationPatterns("*:missing", "*:future")
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        assumeTrue(infraUp, "Postgres 未就绪，跳过平台任务 JDBC 集成测试");
        jdbc.execute("truncate table agent_pending_goal, agent_pending_switch, agent_conversation_focus, agent_platform_task");
        store = new JdbcTaskContextStore(jdbc, transactions);
        manager = new PlatformTaskContextManager(store);
    }

    @Test
    void reserveBindAreScopedAndRejectStaleVersions() {
        PlatformTask reserved = manager.reserve("tenant-a", "agent-a", "session-a", "route-1",
                new RouteTarget(RouteTarget.Type.CAPABILITY, "cap.card.replace"), RuntimeType.TASK);
        assertThat(store.task("tenant-b", "agent-a", reserved.platformTaskId())).isEmpty();
        PlatformTask bound = manager.bind("tenant-a", "agent-a", reserved.platformTaskId(), RuntimeType.TASK,
                "runtime-1", reserved.version());
        assertThat(bound.bindingState()).isEqualTo(BindingState.BOUND);
        assertThatThrownBy(() -> manager.bind("tenant-a", "agent-a", reserved.platformTaskId(), RuntimeType.TASK,
                "runtime-2", reserved.version())).hasMessageContaining("TASK_BINDING_CONFLICT");
    }

    @Test
    void uniqueForegroundResumeCasAndSuspendedLimit() {
        FocusFrame foreground = manager.foreground("tenant-a", "agent-a", "session-a", "task-0");
        assertThatThrownBy(() -> manager.foreground("tenant-a", "agent-a", "session-a", "task-x"))
                .isInstanceOf(PlatformTaskContextManager.TaskContextConflict.class);

        for (int i = 0; i < PlatformTaskContextManager.MAX_SUSPENDED; i++) {
            FocusTransition transition = manager.switchFocus("tenant-a", "agent-a", "session-a",
                    foreground.frameId(), foreground.version(), "goal-" + i);
            foreground = store.bindPendingGoal("tenant-a", "agent-a", transition.foreground().frameId(),
                    "goal-" + i, "task-" + (i + 1), transition.foreground().version());
        }
        FocusFrame current = foreground;
        assertThatThrownBy(() -> manager.switchFocus("tenant-a", "agent-a", "session-a",
                current.frameId(), current.version(), "goal-over-limit"))
                .isInstanceOf(PlatformTaskContextManager.TaskContextConflict.class)
                .hasMessageContaining("SUSPENDED_TASK_LIMIT");

        FocusFrame closed = store.closeFrame("tenant-a", "agent-a", current.frameId(), current.version());
        assertThat(closed.state()).isEqualTo(FocusState.CLOSED);
        FocusFrame suspended = store.focus("tenant-a", "agent-a", "session-a").suspended().getFirst();
        FocusFrame resumed = manager.resume("tenant-a", "agent-a", "session-a", suspended.frameId(), suspended.version());
        assertThat(resumed.state()).isEqualTo(FocusState.FOREGROUND);
        assertThatThrownBy(() -> store.resume("tenant-a", "agent-a", "session-a",
                suspended.frameId(), suspended.version())).hasMessageContaining("FOCUS_VERSION_CONFLICT");
    }

    @Test
    void switchAcceptIsAtomicAndDoubleAcceptIsRejected() {
        FocusFrame foreground = manager.foreground("tenant-a", "agent-a", "session-a", "task-0");
        SwitchCoordinator switches = new SwitchCoordinator(store, manager);
        PendingSwitch pending = switches.propose("tenant-a", "agent-a", "session-a",
                "turn-1", "查账单", 0, 3);
        PendingGoal goal = switches.accept("tenant-a", "agent-a", "session-a",
                pending.switchId(), pending.version(), "turn-2");
        assertThat(goal.state()).isEqualTo(PendingGoalState.ROUTING);
        assertThat(store.focus("tenant-a", "agent-a", "session-a").suspended())
                .extracting(FocusFrame::frameId).contains(foreground.frameId());
        assertThat(store.focus("tenant-a", "agent-a", "session-a").foreground().subjectType())
                .isEqualTo(FocusSubjectType.PENDING_GOAL);
        assertThatThrownBy(() -> switches.accept("tenant-a", "agent-a", "session-a",
                pending.switchId(), pending.version(), "turn-3"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void staleFocusRollsBackSwitchAndPendingGoalTogether() {
        FocusFrame foreground = manager.foreground("tenant-a", "agent-a", "session-a", "task-0");
        SwitchCoordinator switches = new SwitchCoordinator(store, manager);
        PendingSwitch pending = switches.propose("tenant-a", "agent-a", "session-a",
                "turn-1", "查账单", 0, 3);
        store.closeFrame("tenant-a", "agent-a", foreground.frameId(), foreground.version());

        assertThatThrownBy(() -> switches.accept("tenant-a", "agent-a", "session-a",
                pending.switchId(), pending.version(), "turn-2"))
                .hasMessageContaining("FOCUS_VERSION_CONFLICT");
        assertThat(store.pendingSwitch("tenant-a", "agent-a", "session-a")).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from agent_pending_goal where tenant_id='tenant-a'",
                Integer.class)).isZero();
    }

    @Test
    void pendingGoalTransitionsCoverDirectAndRuntimePaths() {
        PendingGoalCoordinator goals = new PendingGoalCoordinator(store);
        PendingGoal direct = store.createPendingGoal(goal("goal-direct", "switch-direct"));
        PendingGoal completed = goals.completeDirect("tenant-a", "agent-a", direct.pendingGoalId(),
                "route-direct", direct.version());
        assertThat(completed.state()).isEqualTo(PendingGoalState.COMPLETED);

        PendingGoal runtime = store.createPendingGoal(goal("goal-runtime", "switch-runtime"));
        PendingGoal starting = goals.startRuntime("tenant-a", "agent-a", runtime.pendingGoalId(),
                "route-runtime", runtime.version());
        PendingGoal bound = goals.bind("tenant-a", "agent-a", runtime.pendingGoalId(),
                "platform-task", starting.version());
        assertThat(bound.state()).isEqualTo(PendingGoalState.BOUND);
        assertThat(bound.boundPlatformTaskId()).isEqualTo("platform-task");
        assertThatThrownBy(() -> goals.bind("tenant-a", "agent-a", runtime.pendingGoalId(),
                "other-task", starting.version())).hasMessageContaining("PENDING_GOAL_VERSION_CONFLICT");
    }

    @Test
    void bindRuntimeAndFocusIsAtomic() {
        PlatformTask reserved = manager.reserve("tenant-a", "agent-a", "session-a", "route-atomic",
                new RouteTarget(RouteTarget.Type.LOOP, "goal-1"), RuntimeType.AGENT_LOOP);
        manager.foreground("tenant-a", "agent-a", "session-a", "existing-task");

        assertThatThrownBy(() -> manager.bindAndFocus("tenant-a", "agent-a", "session-a",
                reserved.platformTaskId(), RuntimeType.AGENT_LOOP, "loop-1", reserved.version(),
                null, "route-atomic")).hasMessageContaining("FOREGROUND_ALREADY_EXISTS");

        PlatformTask rolledBack = store.task("tenant-a", "agent-a", reserved.platformTaskId()).orElseThrow();
        assertThat(rolledBack.bindingState()).isEqualTo(BindingState.RESERVED);
        assertThat(rolledBack.runtimeRef()).isNull();
    }

    @Test
    void failedRuntimeRegistrationClosesReservationAndRestoresPreviousFocus() {
        FocusFrame previous = manager.foreground("tenant-a", "agent-a", "session-a", "task-before");
        SwitchCoordinator switches = new SwitchCoordinator(store, manager);
        PendingSwitch pending = switches.propose("tenant-a", "agent-a", "session-a",
                "turn-1", "查账单", 0, 3);
        PendingGoal goal = switches.accept("tenant-a", "agent-a", "session-a",
                pending.switchId(), pending.version(), "turn-2");
        PlatformTask reserved = manager.reserve("tenant-a", "agent-a", "session-a", "route-failed",
                new RouteTarget(RouteTarget.Type.CAPABILITY, "cap.bill.query"), RuntimeType.TASK);

        store.failRuntimeRegistration("tenant-a", "agent-a", "session-a",
                reserved.platformTaskId(), goal.pendingGoalId(), "PLATFORM_REGISTRATION_FAILED");

        PlatformTask closed = store.task("tenant-a", "agent-a", reserved.platformTaskId()).orElseThrow();
        assertThat(closed.status()).isEqualTo(PlatformTaskStatus.CLOSED);
        assertThat(closed.closeReason()).isEqualTo("PLATFORM_REGISTRATION_FAILED");
        assertThat(store.pendingGoal("tenant-a", "agent-a", goal.pendingGoalId()).orElseThrow().state())
                .isEqualTo(PendingGoalState.FAILED);
        FocusView focus = store.focus("tenant-a", "agent-a", "session-a");
        assertThat(focus.foreground().frameId()).isEqualTo(previous.frameId());
        assertThat(focus.suspended()).isEmpty();
    }

    @Test
    void pendingGoalRoutingFailureRestoresFocusWithoutAReservedPlatformTask() {
        FocusFrame previous = manager.foreground("tenant-a", "agent-a", "session-a", "task-before");
        SwitchCoordinator switches = new SwitchCoordinator(store, manager);
        PendingSwitch pending = switches.propose("tenant-a", "agent-a", "session-a",
                "turn-1", "查账单", 0, 3);
        PendingGoal goal = switches.accept("tenant-a", "agent-a", "session-a",
                pending.switchId(), pending.version(), "turn-2");

        store.failRuntimeRegistration("tenant-a", "agent-a", "session-a",
                null, goal.pendingGoalId(), "PENDING_GOAL_ROUTING_FAILED");

        assertThat(store.pendingGoal("tenant-a", "agent-a", goal.pendingGoalId()).orElseThrow().state())
                .isEqualTo(PendingGoalState.FAILED);
        FocusView focus = store.focus("tenant-a", "agent-a", "session-a");
        assertThat(focus.foreground().frameId()).isEqualTo(previous.frameId());
        assertThat(focus.suspended()).isEmpty();
    }

    @Test
    void pendingGoalBindingCommitsTaskFocusAndGoalTogether() {
        manager.foreground("tenant-a", "agent-a", "session-a", "task-before");
        SwitchCoordinator switches = new SwitchCoordinator(store, manager);
        PendingSwitch pending = switches.propose("tenant-a", "agent-a", "session-a",
                "turn-1", "查账单", 0, 3);
        PendingGoal goal = switches.accept("tenant-a", "agent-a", "session-a",
                pending.switchId(), pending.version(), "turn-2");
        PlatformTask reserved = manager.reserve("tenant-a", "agent-a", "session-a", "route-bound",
                new RouteTarget(RouteTarget.Type.CAPABILITY, "cap.bill.query"), RuntimeType.TASK);

        PlatformTask bound = manager.bindAndFocus("tenant-a", "agent-a", "session-a",
                reserved.platformTaskId(), RuntimeType.TASK, "runtime-1", reserved.version(),
                goal.pendingGoalId(), "route-bound");

        assertThat(bound.bindingState()).isEqualTo(BindingState.BOUND);
        FocusFrame foreground = store.focus("tenant-a", "agent-a", "session-a").foreground();
        assertThat(foreground.subjectType()).isEqualTo(FocusSubjectType.PLATFORM_TASK);
        assertThat(foreground.subjectRef()).isEqualTo(bound.platformTaskId());
        PendingGoal completed = store.pendingGoal("tenant-a", "agent-a", goal.pendingGoalId()).orElseThrow();
        assertThat(completed.state()).isEqualTo(PendingGoalState.BOUND);
        assertThat(completed.boundPlatformTaskId()).isEqualTo(bound.platformTaskId());
    }

    private static PendingGoal goal(String id, String switchId) {
        java.time.Instant now = java.time.Instant.now();
        return new PendingGoal("tenant-a", id, "agent-a", "session-a", switchId,
                "previous-frame", "turn-1", 0, 3, "hash", PendingGoalState.ROUTING,
                null, null, 0, now, now);
    }
}
