package com.huawei.finance.sample.oj.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.sample.oj.OjDomainAgent;
import com.huawei.finance.sample.oj.OjQueryCodec;
import com.huawei.finance.sample.oj.StubTransferAgent;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * 端到端：起一个完整的 OpenJiuwen Web 上下文，中控侧通过 HTTP 契约调它。
 *
 * <p>这里不 mock {@link RestClient} 的请求与响应，而是用 MockMvc 作为它的 HTTP 传输层。
 * 因此不需要绑定操作系统端口，但仍会经过真实的 Controller、HTTP message converter
 * 和 Jackson 序列化。这条链路上会出问题的地方
 * 基本都在编解码之外——OJ 的入口校验（{@code conversationId} 必填）、
 * {@code ServeRequest.fromQueryRequest} 会不会把 messages 里的额外键丢掉、
 * {@code stream=false} 时走的到底是 SSE 还是普通 JSON、Jackson 在真实序列化下
 * 能不能还原 {@code TaskResult} 里的枚举。这些全都只有在真的发一次 HTTP 时才暴露。
 */
@SpringBootTest(
        classes = {OjTestServerApplication.class, OjEndToEndTest.ServerAgents.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        // 服务端那一半：把 OpenJiuwenAgentHandler 与 /v1/query 装起来
        "huawei.finance.sample.openjiuwen.server.enabled=true",
        // 客户端那一半在测试里手工装（要用到随机端口），所以这里不开
        "huawei.finance.sample.openjiuwen.enabled=false"
})
class OjEndToEndTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @TestConfiguration
    static class ServerAgents {
        @Bean
        DomainAgent stubTransferAgent() {
            return new StubTransferAgent();
        }
    }

    private OjDomainAgent client() {
        return client(MockMvcBuilders.webAppContextSetup(webApplicationContext).build());
    }

    private static OjDomainAgent client(MockMvc mvc) {
        RestClient restClient = RestClient.builder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc))
                .build();
        return new OjDomainAgent(restClient, new OjQueryCodec(),
                Map.of(StubTransferAgent.CAPABILITY, "http://openjiuwen-agent"));
    }

    @Test
    @DisplayName("一笔任务隔着真实 HTTP 走完并拿回结构化结果")
    void executesTaskOverRealHttp() {
        UnifiedTask task = task(UUID.randomUUID().toString());

        TaskResult result = client().execute(task);

        assertThat(result.success())
                .as("端到端跑不通时先看服务端日志：多半是 /v1/query 没装或编排器缺 Bean")
                .isTrue();
        assertThat(result.taskId()).isEqualTo(task.taskId());
        assertThat(result.idempotencyKey()).isEqualTo(task.idempotencyKey());
        assertThat(result.resultPayload()).containsEntry("amount", "1000");
        assertThat(result.status())
                .as("枚举要能原样还原，退化成字符串会让中控的状态机对不上")
                .isEqualTo(Enums.TaskStatus.SUCCESS);
    }

    @Test
    @DisplayName("同一幂等键重投，隔着 HTTP 仍然幂等")
    void replayIsIdempotentOverHttp() {
        UnifiedTask task = task(UUID.randomUUID().toString());
        OjDomainAgent client = client();

        TaskResult first = client.execute(task);
        TaskResult second = client.execute(task);

        assertThat(second.resultPayload()).isEqualTo(first.resultPayload());
    }

    @Test
    @DisplayName("没有配路由的能力，supports 为假，不去猜地址")
    void doesNotRouteUnknownCapability() {
        assertThat(client().supports("cap.unknown")).isFalse();
    }

    @Test
    @DisplayName("服务端不通时判 RETRYABLE，不判死")
    void transportFailureIsRetryable() {
        // 指向一个没人监听的端口。这一类失败下无法断定领域侧是否已执行，
        // 判 FATAL 会让一笔可能已扣款的转账被中控当成没发生
        OjDomainAgent broken = new OjDomainAgent(RestClient.create(), new OjQueryCodec(),
                Map.of(StubTransferAgent.CAPABILITY, "http://localhost:1"));

        TaskResult result = broken.execute(task(UUID.randomUUID().toString()));

        assertThat(result.success()).isFalse();
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(result.resultPayload()).containsEntry("error", "OJ_TRANSPORT_ERROR");
    }

    /**
     * 这一组是整套接入里最要紧的一条验证：把中控指向一个**通用对话 Handler**，
     * 它返回一句「已为您转账成功」——客户端必须把这次调用判成失败。
     *
     * <p>这不是假想的错法。OJ 自带的 Handler 就是这个行为，而配置里把地址写成
     * 隔壁那个通用 Agent 的，是最容易犯的一种错。若客户端在这里采信了响应，
     * 中控会把一笔从未发生的转账记为成功：没有异常、没有告警，等对账才发现。
     */
    @Nested
    @SpringBootTest(
            classes = {OjTestServerApplication.class, ChattyServer.class},
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @TestPropertySource(properties = {"huawei.finance.sample.openjiuwen.server.enabled=true", "huawei.finance.sample.openjiuwen.enabled=false"})
    class AgainstGenericChatHandler {

        @Autowired
        WebApplicationContext chattyContext;

        @Test
        @DisplayName("通用对话 Handler 编出来的成功，必须被判成失败")
        void refusesToTrustNaturalLanguageSuccess() {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(chattyContext).build();
            TaskResult result = client(mvc).execute(task(UUID.randomUUID().toString()));

            assertThat(result.success())
                    .as("这句话是模型编的，对应的转账从未发生")
                    .isFalse();
            assertThat(result.failureClass())
                    .as("这是配错了地址，重试只会再拿一段编出来的话")
                    .isEqualTo(Enums.FailureClass.FATAL);
            assertThat(result.resultPayload()).containsEntry("error", "OJ_CONTRACT_VIOLATION");
        }
    }

    /** 冒充一个通用对话 Agent：不认信封，只会回自然语言。 */
    @TestConfiguration
    static class ChattyServer {
        @Bean
        @Primary
        AgentHandler chattyHandler() {
            return new AgentHandler() {
                @Override
                public QueryResponse query(ServeRequest request) {
                    return new QueryResponse("已为您向张三转账 1000 元，请查收。",
                            request.getConversationId());
                }

                @Override
                public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                    observer.onComplete();
                }
            };
        }
    }

    static UnifiedTask task(String idempotencyKey) {
        return new UnifiedTask(
                "task-" + UUID.randomUUID(),
                "trace-" + UUID.randomUUID(),
                Enums.TaskSource.FAST_PATH,
                "帮我转 1000 给张三",
                StubTransferAgent.CAPABILITY,
                Map.of("payee", "张三", "amount", "1000"),
                RiskLevel.R2,
                Map.of("confirmed", true),
                GuardrailCheck.passed(),
                idempotencyKey,
                List.of(),
                null);
    }
}
