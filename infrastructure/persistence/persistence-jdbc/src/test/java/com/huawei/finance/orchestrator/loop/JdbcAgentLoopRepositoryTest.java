package com.huawei.finance.orchestrator.loop;

import static com.huawei.finance.orchestrator.loop.LoopContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcAgentLoopRepositoryTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String USER = "agent_platform";
    private static final String PASSWORD = "agent_platform";
    private static boolean infraUp;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private JdbcAgentLoopRepository repository;

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
        assumeTrue(infraUp, "Postgres 未就绪，跳过 Loop JDBC 集成测试");
        jdbc.execute("truncate table agent_loop_step, agent_loop_run");
        repository = new JdbcAgentLoopRepository(jdbc, transactions);
    }

    @Test
    void casPartialTenantIsolationAndRestartRecovery() {
        Run opened = repository.open(new StartRequest("tenant-a", "agent-a", "session-a", "pt-1",
                "trace-1", "排查工资未到账", List.of("cap.payroll.query"),
                com.huawei.finance.contracts.model.TaskShape.OPEN_ENDED_DIAGNOSIS,
                Instant.now().plusSeconds(30), 4));
        assertThat(repository.find("tenant-b", "agent-a", opened.loopId())).isEmpty();
        assertThat(repository.find("tenant-a", "agent-b", opened.loopId())).isEmpty();

        Action action = new Action(ActionType.CALL_CAPABILITY, "cap.payroll.query", Map.of(), Map.of(),
                "CHECK_ARRIVAL", "fp-1");
        assertThat(repository.propose("tenant-a", "agent-a", opened.loopId(), opened.version(), action)).isTrue();
        Run proposed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(repository.propose("tenant-a", "agent-a", opened.loopId(), opened.version(), action)).isFalse();
        assertThat(repository.claim("tenant-a", "agent-a", opened.loopId(), 0, proposed.version())).isTrue();
        Run claimed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(repository.claim("tenant-a", "agent-a", opened.loopId(), 0, claimed.version())).isFalse();

        Observation partial = new Observation(ObservationStatus.PARTIAL, "CAPABILITY", "cap.payroll.query",
                Map.of(), "BACKEND_UNKNOWN", "PARTIAL", "task-1", null, false, Map.of());
        Run failed = repository.complete("tenant-a", "agent-a", opened.loopId(), 0,
                claimed.version(), partial, Status.FAILED);
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(repository.reasonCode("tenant-a", "agent-a", opened.loopId()))
                .contains("BACKEND_UNKNOWN");
        assertThat(repository.steps("tenant-a", "agent-a", opened.loopId()).getFirst().status())
                .isEqualTo(StepStatus.UNKNOWN_OUTCOME);

        JdbcAgentLoopRepository restarted = new JdbcAgentLoopRepository(jdbc, transactions);
        assertThat(restarted.find("tenant-a", "agent-a", opened.loopId()).orElseThrow().iteration())
                .isEqualTo(1);
        assertThat(restarted.steps("tenant-a", "agent-a", opened.loopId())).hasSize(1);
    }

    @Test
    void staleClaimBecomesUnknownOutcomeAndCannotBeReplayed() {
        Run opened = repository.open(new StartRequest("tenant-a", "agent-a", "session-a", "pt-1",
                "trace-1", "排查工资未到账", List.of("cap.payroll.query"),
                com.huawei.finance.contracts.model.TaskShape.OPEN_ENDED_DIAGNOSIS,
                Instant.now().plusSeconds(30), 4));
        Action action = new Action(ActionType.CALL_CAPABILITY, "cap.payroll.query", Map.of(), Map.of(),
                "CHECK_ARRIVAL", "fp-recovery");
        assertThat(repository.propose("tenant-a", "agent-a", opened.loopId(), opened.version(), action)).isTrue();
        Run proposed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(repository.claim("tenant-a", "agent-a", opened.loopId(), 0, proposed.version())).isTrue();
        Run claimed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();

        assertThat(repository.recoverClaimed("tenant-a", "agent-a", opened.loopId(), 0,
                claimed.version(), Instant.now().minusSeconds(1), "LOOP_RESTART_UNKNOWN_OUTCOME")).isFalse();
        assertThat(repository.recoverClaimed("tenant-a", "agent-a", opened.loopId(), 0,
                claimed.version(), Instant.now().plusSeconds(1), "LOOP_RESTART_UNKNOWN_OUTCOME")).isTrue();

        Run recovered = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(Status.FAILED);
        assertThat(recovered.pendingAction()).isNull();
        assertThat(repository.reasonCode("tenant-a", "agent-a", opened.loopId()))
                .contains("LOOP_RESTART_UNKNOWN_OUTCOME");
        assertThat(repository.steps("tenant-a", "agent-a", opened.loopId()).getFirst().status())
                .isEqualTo(StepStatus.UNKNOWN_OUTCOME);
        assertThat(repository.claim("tenant-a", "agent-a", opened.loopId(), 0, recovered.version())).isFalse();
    }

    @Test
    void confirmedSlotsSurviveRestartAndUserInputCancelsTheOldProposal() {
        Run opened = repository.open(new StartRequest("tenant-a", "agent-a", "session-a", "pt-1",
                "trace-1", "换卡排查", List.of("cap.card.query"),
                com.huawei.finance.contracts.model.TaskShape.OPEN_ENDED_DIAGNOSIS,
                Instant.now().plusSeconds(30), 4, Map.of("channel", "MOBILE")));
        Action action = new Action(ActionType.CALL_CAPABILITY, "cap.card.query", Map.of(), Map.of(),
                "NEED_CARD_TYPE", "fp-card");
        assertThat(repository.propose("tenant-a", "agent-a", opened.loopId(), opened.version(), action)).isTrue();
        Run proposed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(repository.waitForInput("tenant-a", "agent-a", opened.loopId(), proposed.version(),
                List.of("cardType"), "MISSING_SLOT")).isTrue();

        JdbcAgentLoopRepository restarted = new JdbcAgentLoopRepository(jdbc, transactions);
        Run waiting = restarted.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(waiting.confirmedSlots()).containsEntry("channel", "MOBILE");
        assertThat(waiting.pendingSlots()).containsExactly("cardType");
        assertThat(restarted.reasonCode("tenant-a", "agent-a", opened.loopId()))
                .contains("MISSING_SLOT");

        Run resumed = restarted.resume("tenant-a", "agent-a", opened.loopId(), waiting.version(),
                Status.WAITING_USER, Map.of("cardType", "CREDIT"));

        assertThat(resumed.status()).isEqualTo(Status.RUNNING);
        assertThat(resumed.iteration()).isEqualTo(1);
        assertThat(resumed.pendingAction()).isNull();
        assertThat(resumed.pendingSlots()).isEmpty();
        assertThat(resumed.confirmedSlots()).containsEntry("channel", "MOBILE")
                .containsEntry("cardType", "CREDIT");
        assertThat(restarted.reasonCode("tenant-a", "agent-a", opened.loopId())).isEmpty();
        assertThat(restarted.steps("tenant-a", "agent-a", opened.loopId()).getFirst().status())
                .isEqualTo(StepStatus.CANCELLED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> restarted.resume(
                        "tenant-a", "agent-a", opened.loopId(), waiting.version(),
                        Status.WAITING_USER, Map.of("cardType", "DEBIT")))
                .hasMessage("LOOP_RESUME_CONFLICT");
    }

    @Test
    void needUserObservationResumesWithoutCancellingOrAdvancingTwice() {
        Run opened = repository.open(new StartRequest("tenant-a", "agent-a", "session-a", "pt-1",
                "trace-1", "排查卡状态", List.of("cap.card.query"),
                com.huawei.finance.contracts.model.TaskShape.OPEN_ENDED_DIAGNOSIS,
                Instant.now().plusSeconds(30), 4));
        Action action = new Action(ActionType.CALL_CAPABILITY, "cap.card.query", Map.of(), Map.of(),
                "CHECK_CARD", "fp-card-need-user");
        assertThat(repository.propose("tenant-a", "agent-a", opened.loopId(), opened.version(), action)).isTrue();
        Run proposed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        assertThat(repository.claim("tenant-a", "agent-a", opened.loopId(), 0, proposed.version())).isTrue();
        Run claimed = repository.find("tenant-a", "agent-a", opened.loopId()).orElseThrow();
        Observation needUser = new Observation(ObservationStatus.NEED_USER, "CAPABILITY", "cap.card.query",
                Map.of(), "MISSING_SLOT", "NEED_USER", "task-1", null, false,
                Map.of("missingSlots", List.of("cardType")));
        Run waiting = repository.complete("tenant-a", "agent-a", opened.loopId(), 0,
                claimed.version(), needUser, Status.WAITING_USER);

        assertThat(waiting.iteration()).isEqualTo(1);
        assertThat(waiting.pendingAction()).isNull();
        assertThat(waiting.pendingSlots()).containsExactly("cardType");
        assertThat(repository.steps("tenant-a", "agent-a", opened.loopId()).getFirst().status())
                .isEqualTo(StepStatus.COMPLETED);

        Run resumed = repository.resume("tenant-a", "agent-a", opened.loopId(), waiting.version(),
                Status.WAITING_USER, Map.of("cardType", "DEBIT"));
        assertThat(resumed.iteration()).isEqualTo(1);
        assertThat(resumed.status()).isEqualTo(Status.RUNNING);
        assertThat(resumed.confirmedSlots()).containsEntry("cardType", "DEBIT");
        assertThat(repository.steps("tenant-a", "agent-a", opened.loopId()).getFirst().status())
                .isEqualTo(StepStatus.COMPLETED);
    }
}
