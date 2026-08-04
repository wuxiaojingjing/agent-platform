package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.orchestrator.continuation.ContinuationUnderstandingModel;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.runtime.ActionEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Proves that model outages never reactivate phrase-based Static Plan continuation. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "huawei.finance.agent.slowpath.enabled=false",
        "huawei.finance.agent.slowpath.execution-mode=CONFIRM_EACH"
})
@Import({StaticPlanAccountTestConfiguration.class,
        ContinuationModelUnavailableEndToEndTest.UnavailableModelConfiguration.class})
class ContinuationModelUnavailableEndToEndTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final String AGENT_ID = "agent.mobile-banking-assistant";
    private static final String MULTI = "查余额，再给老徐转 1000；不足就别转";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private RedissonClient redisson;
    @Autowired private IntentPlanRepository plans;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过模型不可用边界验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过模型不可用边界验收");
    }

    @BeforeEach
    void clearDecisionCache() {
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    void naturalLanguagePhrasesDoNotAdvanceOrCancelAPlanWhenTheModelIsUnavailable() {
        for (String phrase : List.of("先办查余额", "接着办", "1", "算了，不办了")) {
            String session = "s-no-model-" + UUID.randomUUID();
            chat(session, MULTI, null);
            var before = plans.findActiveBySession(AGENT_ID, session).orElseThrow();

            ChatResponseDto response = chat(session, phrase, null);

            assertThat(response.decision().decision()).isEqualTo(Decision.RESUME_TASK);
            assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.RESUME_REQUIRED);
            assertThat(response.taskId()).isEqualTo(before.planId());
            var after = plans.findById(AGENT_ID, before.planId()).orElseThrow();
            assertThat(after.state()).isEqualTo(PlanState.IN_PROGRESS);
            assertThat(after.cursor()).isZero();
        }
    }

    @Test
    void structuredContinueStillAdvancesThePlanWithoutTheModel() {
        String session = "s-structured-no-model-" + UUID.randomUUID();
        chat(session, MULTI, null);
        var plan = plans.findActiveBySession(AGENT_ID, session).orElseThrow();

        ChatResponseDto response = chat(session, "",
                new ActionEvent("CONTINUE_CURRENT", plan.planId(), plan.stateVersion()));

        assertThat(response.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.CONTINUATION);
        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(plans.findById(AGENT_ID, plan.planId()).orElseThrow().cursor()).isEqualTo(1);
    }

    private ChatResponseDto chat(String sessionId, String query, ActionEvent action) {
        ChatRequestDto request = new ChatRequestDto(
                sessionId, "u-1", query, "MOBILE_BANK", "home", "", action);
        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of("u-1", "MOBILE_BANK")),
                ChatResponseDto.class);
        assertThat(entity.getBody()).isNotNull();
        return entity.getBody();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class UnavailableModelConfiguration {
        @Bean
        @Primary
        ContinuationUnderstandingModel unavailableContinuationModel() {
            return ContinuationUnderstandingModel.UNAVAILABLE;
        }
    }
}
