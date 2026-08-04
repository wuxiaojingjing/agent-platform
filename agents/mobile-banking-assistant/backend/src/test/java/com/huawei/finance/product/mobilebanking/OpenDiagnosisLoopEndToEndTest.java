package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.TaskShape;
import com.huawei.finance.contracts.model.ResponseComponent;
import com.huawei.finance.domain.account.AccountPort;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.gateway.ToolChatReply;
import com.huawei.finance.gateway.ToolChatRequest;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.ActionType;
import com.huawei.finance.orchestrator.loop.LoopContracts.Status;
import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.product.mobilebanking.console.RecentDecisions;
import com.huawei.finance.runtime.bootstrap.ModelAgentLoopPlanner;
import com.huawei.finance.runtime.loop.AgentLoopPlanner;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "huawei.finance.agent.loop.enabled=true")
@Import(OpenDiagnosisLoopEndToEndTest.LoopTestConfiguration.class)
class OpenDiagnosisLoopEndToEndTest {
    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";

    @Autowired private TestRestTemplate rest;
    @Autowired private AgentLoopRepository loops;
    @Autowired private AgentLoopPlanner loopPlanner;
    @Autowired private PlannerGateway plannerGateway;
    @Autowired private RecentDecisions recentDecisions;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过 Loop 端到端验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过 Loop 端到端验收");
    }

    @Test
    @DisplayName("开放式工资排查 → START_LOOP + AFTER_OBSERVATION，并按单动作 Observation 完成")
    void openPayrollDiagnosisStartsLoop() {
        assertThat(loopPlanner).isInstanceOf(ModelAgentLoopPlanner.class);
        String session = "s-loop-" + UUID.randomUUID();
        ChatRequestDto request = new ChatRequestDto(session, "u-1", "工资没到账，帮我排查原因",
                "MOBILE_BANK", "home", "");

        ChatResponseDto response = rest.postForObject("/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);

        assertThat(response).isNotNull();
        assertThat(response.decision().decision()).isEqualTo(Decision.START_LOOP);
        assertThat(response.decision().taskShape()).isEqualTo(TaskShape.OPEN_ENDED_DIAGNOSIS);
        assertThat(response.decision().reasonCode()).isEqualTo(ReasonCode.AFTER_OBSERVATION);
        assertThat(plannerGateway.toolCalls()).as("DeepAgent tool-channel calls").hasValue(2);
        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(response.plan().responsePolicyVersion()).isEqualTo("response-policy-v1");
        assertThat(response.plan().responsePromptVersion()).isEqualTo("response-realizer-v1");
        assertThat(response.plan().cardComponents()).contains(ResponseComponent.LOOP_STATUS);
        assertThat(response.text()).contains("排查已完成")
                .doesNotContain("{")
                .doesNotContain("cap.payroll.arrival.query");
        var run = loops.find(TenantHeaderSupport.SPACE_ID, "agent.mobile-banking-assistant",
                response.taskId()).orElseThrow();
        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(loops.steps(run.tenantId(), run.agentId(), run.loopId()))
                .extracting(step -> step.action().actionType())
                .containsExactly(ActionType.CALL_CAPABILITY, ActionType.FINISH);
        assertThat(recentDecisions.snapshot())
                .anySatisfy(entry -> {
                    assertThat(entry.traceId()).isEqualTo(response.traceId());
                    assertThat(entry.decision()).isEqualTo(Decision.START_LOOP.name());
                    assertThat(entry.taskId()).isEqualTo(response.taskId());
                    assertThat(entry.moduleSteps()).isNotEmpty();
                });
    }

    @TestConfiguration
    static class LoopTestConfiguration {
        @Bean
        @Primary
        PlannerGateway deterministicLoopModel() {
            return new PlannerGateway();
        }

        @Bean
        @Primary
        AccountPort accountPort() {
            return new AccountPort() {
                @Override public AccountView accountView(String principalRef) {
                    return new AccountView(List.of(new CardView(1, "工资卡", "1000.00")));
                }
                @Override public List<TransactionView> transactions(String principalRef) {
                    return List.of();
                }
            };
        }
    }

    static final class PlannerGateway implements ModelGatewayClient {
        private final AtomicInteger plannerProposals = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();

        AtomicInteger toolCalls() {
            return toolCalls;
        }

        @Override public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("test-no-embedding", 0);
        }
        @Override public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok("{\"decision\":\"START_LOOP\","
                    + "\"taskShape\":\"OPEN_ENDED_DIAGNOSIS\","
                    + "\"candidateIds\":[\"cap.payroll.arrival.query\"],"
                    + "\"subGoals\":[{\"id\":\"diagnose\",\"candidateIds\":[\"cap.payroll.arrival.query\"],"
                    + "\"dependsOn\":[],\"selectionBasis\":\"AFTER_OBSERVATION\"}],"
                    + "\"missingSlots\":[],\"extractedSlots\":{},\"confidence\":0.96,"
                    + "\"reasonCode\":\"AFTER_OBSERVATION\"}", 1);
        }
        @Override public GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
            toolCalls.incrementAndGet();
            int proposal = plannerProposals.getAndIncrement();
            ToolChatReply.ToolCallRequest call = proposal == 0
                    ? new ToolChatReply.ToolCallRequest("call-payroll",
                            toolName(request, "propose_capability_"),
                            "{\"targetId\":\"cap.payroll.arrival.query\",\"parameters\":{},"
                                    + "\"inputProvenance\":{},"
                                    + "\"proposalReasonCode\":\"CHECK_PAYROLL\"}")
                    : new ToolChatReply.ToolCallRequest("call-finish", toolName(request, "propose_finish_"),
                            "{\"parameters\":{},\"inputProvenance\":{},"
                                    + "\"proposalReasonCode\":\"DIAGNOSIS_COMPLETE\"}");
            return GatewayResult.ok(new ToolChatReply("", List.of(call)), 1);
        }
        @Override public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("test-no-rerank", 0);
        }
        @Override public boolean available() { return true; }

        private static String toolName(ToolChatRequest request, String prefix) {
            return request.tools().stream()
                    .map(tool -> tool.get("function"))
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(function -> String.valueOf(function.get("name")))
                    .filter(name -> name.startsWith(prefix))
                    .findFirst().orElseThrow();
        }
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
        } catch (Exception e) {
            return false;
        }
    }
}
