package com.huawei.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 网关能否把响应读成 Jackson 2 的 {@code JsonNode}。
 *
 * <p><b>这个测试必须待在 {@code mobile-banking-assistant}。</b>它要验的缺陷只在两个 Jackson 同时在场时才犯：
 * 本工程自己用 2.x，Spring Boot 4 的 Web 栈带进来 3.x，而 Spring 7 的默认客户端优先挑 3.x
 * 的转换器。{@code model-openai-compatible} 模块自己的类路径上只有 2.x，同样的用例在那里恒绿，
 * 起不到任何保护作用——放错模块的测试比没有测试更糟，因为它让人以为这条路被守住了。
 *
 * <p>用 Spring Mock HTTP 传输驱动真实 {@link RestClient}，不绑定操作系统端口。
 */
class ModelGatewayJacksonTest {

    private ModelGatewayProperties props;
    private RestClient restClient;
    private MockRestServiceServer server;

    @BeforeEach
    void startStub() {
        props = new ModelGatewayProperties();
        props.setBaseUrl("http://model-gateway.test/v1");
        props.getEmbedding().setDimensions(3);
        // 占位密钥。没有它，网关在发请求之前就以「未配置密钥」短路返回不可用，
        // 于是这条用例根本走不到要验的那个转换器——而它是否短路取决于本机有没有
        // 设 SILICONFLOW_API_KEY，同一份代码在有密钥的机器上绿、在 CI 上红。
        // 一条只在某些机器上生效的守卫等于没有守卫，正是本类 javadoc 里说的那件事。
        // stub 不校验鉴权，随便给个值即可。
        props.setApiKey("test-key-not-a-real-secret");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = ModelGatewayConfiguration.configureRestClient(builder, props.getBaseUrl());
    }

    @Test
    @DisplayName("模型返回 200 与合法 JSON 时，网关必须解析成功而不是记一笔降级")
    void parsesJsonIntoJacksonTwoNode() {
        server.expect(once(), requestTo("http://model-gateway.test/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}",
                        MediaType.APPLICATION_JSON));

        GatewayResult<List<float[]>> result = gateway().embed(List.of("查余额"));

        // 修复前这里拿到的是失败：转换器读不出 2.x 的 JsonNode，抛
        // HttpMessageConversionException，被当成「模型不可用」记成降级。
        // 对外表现是看板显示模型挂了、系统走规则回退，而模型其实好好的
        assertThat(result.available())
                .as("响应是 200 和合法 JSON，解析失败只可能是本地转换器配错了")
                .isTrue();
        assertThat(result.value()).hasSize(1);
        assertThat(result.value().get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        server.verify();
    }

    private com.huawei.finance.gateway.ModelGatewayClient gateway() {
        ModelGatewayConfiguration config = new ModelGatewayConfiguration();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        return config.modelGatewayClient(restClient, restClient, props,
                config.modelGatewayCircuitBreaker(config.modelGatewayCircuitBreakerRegistry(props)),
                config.modelGatewayRetry(config.modelGatewayRetryRegistry(props)),
                meters);
    }
}
