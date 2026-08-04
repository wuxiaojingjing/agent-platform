package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.intent.IntentPlanner;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 场景 3 在 {@code huawei.finance.agent.slowpath.enabled=true} 时的续办回归。
 *
 * <p>用 stub 规划器而不是真模型：本轮要证明的是「开关打开后协议与条件不丢、条件 STOP 可测」，
 * 不是规划质量。真模型 live 另开可选门，不进 CI 硬门。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "huawei.finance.agent.slowpath.enabled=true",
        "huawei.finance.agent.slowpath.execution-mode=CONFIRM_EACH"
})
@Import({MultiIntentPlannerContinuationTest.StubPlannerConfig.class,
        StaticPlanAccountTestConfiguration.class, ContinuationModelFixtureConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiIntentPlannerContinuationTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";
    /** 金额大于 mock 余额，便于续办时触发条件 STOP。 */
    private static final String MULTI = "查余额，再给老徐转 99999；不足就别转";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @Autowired
    private IntentPlanRepository plans;

    @Autowired
    private AtomicInteger plannerCalls;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过规划器续办验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过规划器续办验收");
    }

    @BeforeEach
    void clearDecisionCache() {
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    @DisplayName("确定性步骤已锁定时，即使 enabled=true 也不调用 Planner")
    void deterministicPlanBypassesPlannerAndKeepsCondition() {
        String session = newSession();

        ChatResponseDto first = chat(session, MULTI);
        assertThat(first.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(first.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);

        PlanRecord plan = plans.findActiveBySession("agent.mobile-banking-assistant", session).orElseThrow();
        assertThat(plan.state()).isEqualTo(PlanState.IN_PROGRESS);
        assertThat(plan.plan().source()).isEqualTo(IntentPlan.Source.RULE);
        assertThat(plannerCalls).hasValue(0);
        assertThat(plan.plan().items()).hasSize(2);
        assertThat(plan.plan().items().get(1).condition())
                .as("模型规划路径仍须从 RULE 合并条件，不能静默丢掉「不足就别转」")
                .isEqualTo("不足就别转");
    }

    @Test
    @DisplayName("余额 facts 后续办：金额大于余额时条件闸门 STOP")
    void continuationStopsWhenBalanceIsInsufficient() {
        String session = newSession();
        chat(session, MULTI);
        ChatResponseDto balance = chat(session, "先办查余额");
        assertThat(balance.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(balance.decision().reasonCode()).isEqualTo(ReasonCode.CONTINUATION);
        assertThat(balance.text()).contains("可用余额");

        ChatResponseDto held = chat(session, "接着办");

        assertThat(held.plan().responsePhase())
                .as("余额不够时应直接收口，不能继续去建转账确认")
                .isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(held.usedTemplate()).isEqualTo("tpl.answer.condition-not-met");
        assertThat(held.taskId())
                .as("条件 STOP 不得进中控建档")
                .isNull();

        assertThat(plans.findActiveBySession("agent.mobile-banking-assistant", session))
                .as("STOP 推进到末件后应收口，否则下一轮还会端出同一件")
                .isEmpty();
    }

    private String newSession() {
        return "s-planner-" + UUID.randomUUID();
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

    /**
     * 若确定性规则没有锁定所有步骤，协调器才允许调用这个 Stub。
     * 本类的固定条件计划应始终保持调用次数为零。
     */
    @TestConfiguration
    static class StubPlannerConfig {

        @Bean
        AtomicInteger plannerCalls() {
            return new AtomicInteger();
        }

        @Bean
        IntentPlanner intentPlanner(AtomicInteger plannerCalls) {
            return (goal, candidates, ruleFallback) -> {
                plannerCalls.incrementAndGet();
                if (ruleFallback == null) {
                    return Optional.empty();
                }
                return Optional.of(new IntentPlan(
                        ruleFallback.original(), ruleFallback.items(), IntentPlan.Source.PLANNER));
            };
        }
    }
}
