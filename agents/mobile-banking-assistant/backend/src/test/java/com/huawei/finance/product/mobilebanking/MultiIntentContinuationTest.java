package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 场景 3 的完整走法：一句话说了两件事 → 逐件办 → 办完一件接着下一件。
 *
 * <p>这批用例守的是一个很容易悄悄退化的性质：**第二轮那句「查余额」必须真的去查余额**。
 * 计划落库、游标推进、条件闸门这些机制单独看都对，串起来却可能因为「续办时交给快路径的
 * 是「继续」两个字」而整体失效——那时用户看到的是兜底话术，而每一层的日志都显示自己没错。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "huawei.finance.agent.slowpath.enabled=false",
        "huawei.finance.agent.slowpath.execution-mode=CONFIRM_EACH"
})
@Import({StaticPlanAccountTestConfiguration.class, ContinuationModelFixtureConfiguration.class})
class MultiIntentContinuationTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String AGENT_ID = "agent.mobile-banking-assistant";
    private static final String MULTI = "查余额，再给老徐转 1000；不足就别转";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @Autowired
    private IntentPlanRepository plans;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过续办验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过续办验收");
    }

    @BeforeEach
    void clearDecisionCache() {
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    @DisplayName("多意图那轮把计划落了库，游标停在第一件")
    void multiIntentOpensAPlan() {
        String session = newSession();

        ChatResponseDto first = chat(session, MULTI);
        assertThat(first.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(first.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);

        PlanRecord plan = plans.findActiveBySession(AGENT_ID, session).orElseThrow();
        assertThat(plan.state()).isEqualTo(PlanState.IN_PROGRESS);
        assertThat(plan.cursor()).isZero();
        assertThat(plan.plan().items()).hasSize(2);
        assertThat(plan.next().orElseThrow().capabilityId())
                .isEqualTo("cap.account.balance.query");
        assertThat(plan.plan().items().get(1).condition())
                .as("「不足就别转」是转账的条件，落库时不能丢")
                .isNotBlank();
    }

    @Test
    @DisplayName("第二轮说「先办查余额」就真的去查余额，而不是掉进兜底")
    void pickingTheFirstItemActuallyRunsIt() {
        String session = newSession();
        chat(session, MULTI);

        ChatResponseDto picked = chat(session, "先办查余额");

        assertThat(picked.decision().decision())
                .as("续办要走回单意图计划，回到原多意图计划说明选择没有被解析")
                .isEqualTo(Decision.STATIC_PLAN);
        assertThat(picked.decision().reasonCode()).isEqualTo(ReasonCode.CONTINUATION);
        assertThat(picked.decision().selectedCandidateId()).isEqualTo("cap.account.balance.query");
        assertThat(picked.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(picked.text()).contains("可用余额");
    }

    @Test
    @DisplayName("第一件办完游标就推进，下一件变成转账")
    void finishingTheFirstAdvancesTheCursor() {
        String session = newSession();
        chat(session, MULTI);
        chat(session, "先办查余额");

        PlanRecord plan = plans.findActiveBySession(AGENT_ID, session).orElseThrow();
        assertThat(plan.cursor()).isEqualTo(1);
        assertThat(plan.remaining()).isEqualTo(1);
        // 第二件的能力规则解析不出来（词表里「转」要带收款人才够判），落库时 capabilityId 为空。
        // 这不影响续办：交给快路径的是子意图原文，能力由召回重新认
        assertThat(plan.next().orElseThrow().text()).contains("转");
    }

    @Test
    @DisplayName("序号选择同样认：「1」就是第一件")
    void ordinalSelectionWorks() {
        String session = newSession();
        chat(session, MULTI);

        ChatResponseDto picked = chat(session, "1");

        assertThat(picked.decision().selectedCandidateId()).isEqualTo("cap.account.balance.query");
    }

    @Test
    @DisplayName("用户改口去问别的，先询问切换并保留原计划")
    void changingSubjectRequiresSwitchReviewAndKeepsThePlan() {
        String session = newSession();
        chat(session, MULTI);

        ChatResponseDto other = chat(session, "算了，看看我的理财持仓");

        assertThat(plans.findActiveBySession(AGENT_ID, session))
                .as("接受切换前不能取消 Runtime；后续还要支持恢复")
                .isPresent();
        assertThat(other.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(other.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.SWITCH_REVIEW);
        assertThat(other.actions()).extracting(com.huawei.finance.contracts.model.ResponseAction::event)
                .containsExactly("SWITCH_ACCEPT", "SWITCH_REJECT");
    }

    /**
     * 资金安全不变量。
     *
     * <p>这条不断言具体出口——续办第二件时快路径可能判执行、也可能判澄清，那取决于召回质量。
     * 但无论走哪条，「用户从没确认过的转账被办掉了」都不允许发生。用不变量而不是具体出口
     * 来守，是因为前者不会因为词表调优而失效，而后者会——失效的方式还是悄悄变绿。
     */
    @Test
    @DisplayName("接着办第二件时，没经确认的转账绝不会被执行")
    void continuingNeverExecutesAnUnconfirmedTransfer() {
        String session = newSession();
        chat(session, MULTI);
        chat(session, "先办查余额");

        ChatResponseDto second = chat(session, "接着办");

        if (second.taskId() != null) {
            var task = taskRepository.findById(second.taskId()).orElseThrow();
            if ("cap.transfer".equals(task.capabilityId())) {
                assertThat(task.state())
                        .as("转账在用户确认之前只能停在待确认，不能是已成功")
                        .isEqualTo(TaskState.CONFIRM_PENDING);
                assertThat(taskRepository.idempotencyKeyOf(task.taskId()))
                        .as("幂等键即执行凭据，确认之前不该存在")
                        .isEmpty();
            }
        }
        assertThat(second.plan().responsePhase())
                .as("这一轮不可能是一次已完成的转账")
                .isNotEqualTo(Enums.ResponsePhase.FINAL);
    }

    private String newSession() {
        return "s-" + UUID.randomUUID();
    }

    private ChatResponseDto chat(String sessionId, String query) {
        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(new ChatRequestDto(sessionId, "u-1", query, "MOBILE_BANK", "home", ""),
                        TenantHeaderSupport.of("u-1", "MOBILE_BANK")),
                ChatResponseDto.class);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    private static boolean redisAnswersPing() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[8];
            int read = socket.getInputStream().read(buffer);
            return read > 0 && new String(buffer, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean postgresAccepts() {
        try (Connection ignored = DriverManager.getConnection(PG_URL, "agent_platform", "agent_platform")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
