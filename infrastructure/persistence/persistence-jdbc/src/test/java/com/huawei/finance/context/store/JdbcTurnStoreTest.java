package com.huawei.finance.context.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.context.ConversationTurn;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcTurnStoreTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String USER = "agent_platform";
    private static final String PASSWORD = "agent_platform";
    private static boolean infraUp;
    private static JdbcTemplate jdbc;
    private JdbcTurnStore store;

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
    }

    @BeforeEach
    void setUp() {
        assumeTrue(infraUp, "Postgres 未就绪，跳过对话历史 JDBC 集成测试");
        jdbc.execute("truncate table agent_conversation_turn");
        store = new JdbcTurnStore(jdbc);
    }

    @Test
    void sameSessionIsTenantIsolatedAndMessagesRoundTripExactly() {
        ConversationTurn.Message assistant = new ConversationTurn.Message("assistant-1",
                ConversationTurn.MessageRole.ASSISTANT, ConversationTurn.MessageType.TEXT,
                null, null, "实际展示文本", Map.of("actions", List.of("继续")), true, true);
        store.append(turn("tenant-a", "trace-a", "租户 A", assistant));
        store.append(turn("tenant-b", "trace-b", "租户 B", assistant));

        List<ConversationTurn> tenantA = store.recent("tenant-a", "agent-a", "same-session", 10);
        List<ConversationTurn> tenantB = store.recent("tenant-b", "agent-a", "same-session", 10);

        assertThat(tenantA).singleElement().satisfies(turn -> {
            assertThat(turn.userText()).isEqualTo("租户 A");
            assertThat(turn.messages()).singleElement().isEqualTo(assistant);
        });
        assertThat(tenantB).singleElement().extracting(ConversationTurn::userText)
                .isEqualTo("租户 B");
        assertThat(store.recent("tenant-c", "agent-a", "same-session", 10)).isEmpty();
    }

    private static ConversationTurn turn(String tenant, String trace, String text,
                                         ConversationTurn.Message assistant) {
        return new ConversationTurn(tenant, "agent-a", "same-session", 0, trace, null, text,
                Decision.DIRECT_KNOWLEDGE, null, null, null, Enums.PendingAction.NONE,
                List.of(), Map.of(), Instant.now(), List.of(assistant));
    }
}
