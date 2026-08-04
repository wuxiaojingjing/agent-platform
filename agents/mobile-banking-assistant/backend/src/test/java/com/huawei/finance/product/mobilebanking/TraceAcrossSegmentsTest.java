package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * FP-63：同一个 traceId 贯穿快路径、中控、渲染三段。
 *
 * <p>此前只断了出口那一处「响应体里的 traceId 是 32 位 OTEL id」。那证明了 id 的**来源**对，
 * 没证明它的**贯穿**——中控下发给领域 Agent 的 {@code UnifiedTask} 里带的是不是同一个、
 * 渲染出来的 {@code ResponsePlan} 上标的是不是同一个，都没有断言。排障时真正要用的恰恰是
 * 后两者：拿着用户报来的 id 去查领域侧那一跳。
 *
 * <p>这条断言在 FP-26a 之后变得更有必要：领域调用改到线程池上执行了，
 * 上下文不再天然跟随调用栈，得靠显式搬运。搬漏了不会有任何用例变红，
 * 只会在某天排障时发现领域那一跳的日志查不到。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceAcrossSegmentsTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TraceCapturingAgent agent;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过端到端验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过端到端验收");
    }

    @Test
    @DisplayName("响应体、下发给领域 Agent 的任务、回复计划三处 traceId 一致")
    void sameTraceIdAcrossThreeSegments() {
        ChatRequestDto request = new ChatRequestDto("s-" + UUID.randomUUID(), "u-1", "查一下余额",
                "MOBILE_BANK", "home", "");
        ChatResponseDto body = rest.postForEntity("/api/v1/chat",
                new org.springframework.http.HttpEntity<>(request, TenantHeaderSupport.of(request)),
                ChatResponseDto.class).getBody();

        assertThat(body).isNotNull();
        String traceId = body.traceId();

        // 出口这一段：来源是 OTEL span，不是自造前缀
        assertThat(traceId).matches("[0-9a-f]{32}");

        // 中控这一段：领域 Agent 拿到的必须是同一个，否则领域侧日志与用户报的 id 对不上
        assertThat(agent.seenTraceId.get())
                .as("领域 Agent 收到的 traceId")
                .isEqualTo(traceId);

        // 渲染这一段
        assertThat(body.plan().traceId()).isEqualTo(traceId);
    }

    /**
     * 抢在 Mock 账户 Agent 前面接管查余额，记下任务里的 traceId 后照常返回结果。
     *
     * <p>{@code AgentInvoker} 取第一个 {@code supports} 为真的实现，所以要排在最前。
     */
    @TestConfiguration
    static class RecordingAgentConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        TraceCapturingAgent traceCapturingAgent() {
            return new TraceCapturingAgent();
        }
    }

    static class TraceCapturingAgent implements DomainAgent {

        final AtomicReference<String> seenTraceId = new AtomicReference<>();

        @Override
        public boolean supports(String capabilityId) {
            return "cap.account.balance.query".equals(capabilityId);
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            seenTraceId.set(task.traceId());
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    Map.of("accountAlias", "尾号 8821 借记卡", "availableBalance", "12,845.60"),
                    task.idempotencyKey(), task.guardrailCheck());
        }
    }

    private static boolean redisAnswersPing() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 500);
            socket.setSoTimeout(500);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buf = new byte[7];
            int read = socket.getInputStream().read(buf);
            return read > 0 && new String(buf, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean postgresAccepts() {
        try (Connection c = DriverManager.getConnection(PG_URL, "agent_platform", "agent_platform")) {
            return c.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }
}
