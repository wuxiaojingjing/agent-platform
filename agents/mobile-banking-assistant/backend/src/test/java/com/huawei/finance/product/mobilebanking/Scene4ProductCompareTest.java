package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.domain.insurance.InsuranceProductPort;
import com.huawei.finance.domain.wealthproduct.WealthProductPort;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.gateway.ToolChatReply;
import com.huawei.finance.gateway.ToolChatRequest;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts.ActionType;
import com.huawei.finance.orchestrator.loop.LoopContracts.Status;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 场景 4：产品目录解析所属领域，Loop 跨 Agent 获取事实并出对照答复。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Scene4ProductCompareTest.BackendFixture.class)
class Scene4ProductCompareTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";
    private static final AtomicInteger INSURANCE_CALLS = new AtomicInteger();
    private static final AtomicInteger WEALTH_CALLS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @Autowired
    private AgentLoopRepository loops;

    @Autowired
    private PlannerGateway plannerGateway;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过场景 4 端到端");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过场景 4 端到端");
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

    @BeforeEach
    void clearDecisionCache() {
        INSURANCE_CALLS.set(0);
        WEALTH_CALLS.set(0);
        plannerGateway.reset();
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    @DisplayName("保险产品A与理财产品B不可比 → CLARIFY 且不创建 Loop/调用子 Agent")
    void crossTypeProductsClarifyWithoutAgentCalls() {
        ChatRequestDto request = new ChatRequestDto(
                "s-" + UUID.randomUUID(), "u-1", "对比产品A和产品B", "MOBILE_BANK", "home", "");
        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(body.decision().reasonCode()).isEqualTo(ReasonCode.INCOMPARABLE_PRODUCT_TYPE);
        assertThat(body.decision().decidedByModel()).isFalse();
        assertThat(body.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(body.plan().templateKey()).isEqualTo("tpl.product.compare.incomparable");
        assertThat(body.plan().cardComponents())
                .containsExactly("CHOICE_LIST");
        assertThat(body.plan().slots())
                .containsEntry("leftType", "保险产品")
                .containsEntry("rightType", "理财产品")
                .containsEntry("options", java.util.List.of("查看产品A", "查看产品B"));
        assertThat(body.taskId()).isNull();
        assertThat(INSURANCE_CALLS).hasValue(0);
        assertThat(WEALTH_CALLS).hasValue(0);
        assertThat(plannerGateway.chatCalls()).hasValue(0);
        assertThat(plannerGateway.toolCalls()).hasValue(0);
        assertThat(body.text())
                .contains("产品A")
                .contains("产品B")
                .contains("保险产品")
                .contains("理财产品")
                .contains("不能直接比较");
    }

    @Test
    @DisplayName("同类理财产品B与B2 → Loop逐项查询并生成产品对比卡")
    void sameTypeProductsRunObservationDrivenComparison() {
        ChatRequestDto request = new ChatRequestDto(
                "s-" + UUID.randomUUID(), "u-1", "对比产品B和产品B2", "MOBILE_BANK", "home", "");

        ResponseEntity<ChatResponseDto> entity = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/chat",
                new HttpEntity<>(request, TenantHeaderSupport.of(request)), ChatResponseDto.class);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatResponseDto body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.decision().decision()).isEqualTo(Decision.START_LOOP);
        assertThat(body.decision().reasonCode()).isEqualTo(ReasonCode.AFTER_OBSERVATION);
        assertThat(body.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(body.plan().cardComponents()).containsExactly(
                "LOOP_STATUS", "RESULT_SUMMARY", "PRODUCT_COMPARISON");
        assertThat(body.plan().slots()).containsEntry("comparisonReady", true);
        assertThat(body.taskId()).isNotBlank();
        assertThat(body.text()).contains("产品B").contains("产品B2").contains("R2").contains("R3");
        assertThat(INSURANCE_CALLS).hasValue(0);
        assertThat(WEALTH_CALLS).hasValue(2);
        assertThat(plannerGateway.chatCalls()).hasValue(1);
        assertThat(plannerGateway.toolCalls()).hasValue(3);

        var run = loops.find(TenantHeaderSupport.SPACE_ID, "agent.mobile-banking-assistant",
                body.taskId()).orElseThrow();
        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(loops.steps(run.tenantId(), run.agentId(), run.loopId()))
                .extracting(step -> step.action().actionType())
                .containsExactly(ActionType.CALL_CAPABILITY, ActionType.CALL_CAPABILITY, ActionType.FINISH);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BackendFixture {
        @Bean
        @Primary
        PlannerGateway deterministicProductComparisonModel() {
            return new PlannerGateway();
        }

        @Bean
        @Primary
        InsuranceProductPort insuranceProductPort() {
            return principalRef -> {
                INSURANCE_CALLS.incrementAndGet();
                return new InsuranceProductPort.ProductView(
                        "INS-A", "产品A", "保险", "R3", "3.2%", "一年");
            };
        }

        @Bean
        @Primary
        WealthProductPort wealthProductPort() {
            return capabilityId -> {
                WEALTH_CALLS.incrementAndGet();
                if ("cap.wealth-product.product-b2.query".equals(capabilityId)) {
                    return new WealthProductPort.ProductView(
                            "WEALTH-B2", "产品B2", "理财", "R3", "3.0%-3.5%", "365天");
                }
                return new WealthProductPort.ProductView(
                        "WEALTH-B", "产品B", "理财", "R2", "2.6%", "180天");
            };
        }
    }

    static final class PlannerGateway implements ModelGatewayClient {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger proposals = new AtomicInteger();

        AtomicInteger chatCalls() { return chatCalls; }
        AtomicInteger toolCalls() { return toolCalls; }

        void reset() {
            chatCalls.set(0);
            toolCalls.set(0);
            proposals.set(0);
        }

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("test-no-embedding", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            chatCalls.incrementAndGet();
            return GatewayResult.ok("{\"decision\":\"START_LOOP\"," +
                    "\"taskShape\":\"OBSERVATION_DRIVEN\"," +
                    "\"candidateIds\":[\"cap.wealth-product.product.query\"," +
                    "\"cap.wealth-product.product-b2.query\"]," +
                    "\"subGoals\":[" +
                    "{\"id\":\"product-b\",\"candidateIds\":[\"cap.wealth-product.product.query\"]," +
                    "\"dependsOn\":[],\"selectionBasis\":\"AFTER_OBSERVATION\"}," +
                    "{\"id\":\"product-b2\",\"candidateIds\":[\"cap.wealth-product.product-b2.query\"]," +
                    "\"dependsOn\":[],\"selectionBasis\":\"AFTER_OBSERVATION\"}]," +
                    "\"missingSlots\":[],\"extractedSlots\":{},\"confidence\":0.99," +
                    "\"reasonCode\":\"AFTER_OBSERVATION\"}", 1);
        }

        @Override
        public GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
            toolCalls.incrementAndGet();
            int proposal = proposals.getAndIncrement();
            ToolChatReply.ToolCallRequest call = switch (proposal) {
                case 0 -> proposal(request, "propose_capability_", "query-b",
                        "{\"targetId\":\"cap.wealth-product.product.query\",\"parameters\":{}," +
                                "\"inputProvenance\":{},\"proposalReasonCode\":\"QUERY_LEFT\"}");
                case 1 -> proposal(request, "propose_capability_", "query-b2",
                        "{\"targetId\":\"cap.wealth-product.product-b2.query\",\"parameters\":{}," +
                                "\"inputProvenance\":{},\"proposalReasonCode\":\"QUERY_RIGHT\"}");
                default -> proposal(request, "propose_finish_", "finish",
                        "{\"parameters\":{},\"inputProvenance\":{}," +
                                "\"proposalReasonCode\":\"COMPARISON_FACTS_READY\"}");
            };
            return GatewayResult.ok(new ToolChatReply("", List.of(call)), 1);
        }

        private static ToolChatReply.ToolCallRequest proposal(
                ToolChatRequest request, String prefix, String id, String arguments) {
            return new ToolChatReply.ToolCallRequest(id, toolName(request, prefix), arguments);
        }

        private static String toolName(ToolChatRequest request, String prefix) {
            return request.tools().stream().map(tool -> tool.get("function"))
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .map(function -> String.valueOf(function.get("name")))
                    .filter(name -> name.startsWith(prefix)).findFirst().orElseThrow();
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("test-no-rerank", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
