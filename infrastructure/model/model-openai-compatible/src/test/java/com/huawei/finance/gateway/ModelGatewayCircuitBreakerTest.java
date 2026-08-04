package com.huawei.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * FP-53：模型网关熔断。
 *
 * <p>熔断器此前只有配置，没有用例。配置是活的：{@code minimumNumberOfCalls} 被人从 10 调到
 * 1000，熔断器在任何真实故障里都不会再打开，而没有任何东西会因此变红。
 *
 * <p>要验的是三件事，缺一不可：
 *
 * <ol>
 *   <li>失败累积到阈值后**真的打开**；
 *   <li>打开之后**不再触网**——熔断的全部价值就在这一条。仍然逐个发请求再逐个超时，
 *       等于给每个用户额外加了一个超时的等待，比不熔断更糟；
 *   <li>打开期间调用方拿到的是**降级结果而非异常**，且降级理由能归因到熔断。
 * </ol>
 */
class ModelGatewayCircuitBreakerTest {

    private MeterRegistry meterRegistry;
    private CircuitBreakerRegistry breakerRegistry;
    private CircuitBreaker breaker;
    private CountingFailureFactory transport;
    private OpenAiCompatibleModelGateway gateway;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        // 阈值压到最小，用例才跑得完；判定的是行为，不是这几个数字本身
        breakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .build());
        breaker = breakerRegistry.circuitBreaker("model-gateway");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakerRegistry).bindTo(meterRegistry);

        ModelGatewayProperties props = new ModelGatewayProperties();
        // 重试关掉：这里要数的是「打到网络上几次」，重试会让计数变成阈值的倍数，读不出意思
        props.setConnectRetries(0);
        Retry retry = RetryRegistry.ofDefaults().retry("no-retry",
                io.github.resilience4j.retry.RetryConfig.custom().maxAttempts(1).build());

        transport = new CountingFailureFactory();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:1")
                .requestFactory(transport)
                .build();

        gateway = new OpenAiCompatibleModelGateway(restClient, props, breaker, retry,
                meterRegistry, "test-key");
    }

    @Nested
    @DisplayName("累积失败后打开")
    class Opening {

        @Test
        @DisplayName("失败次数达到最小样本量后熔断器打开")
        void breakerOpensAfterEnoughFailures() {
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            for (int i = 0; i < 4; i++) {
                gateway.embed(List.of("查一下余额"));
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        /**
         * 样本量不足时不许开。少数几次抖动就摘掉整条语义通道，代价比抖动本身大得多。
         */
        @Test
        @DisplayName("样本量不足时保持闭合")
        void breakerStaysClosedBelowMinimumCalls() {
            gateway.embed(List.of("查一下余额"));
            gateway.embed(List.of("查一下余额"));

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("打开之后")
    class WhenOpen {

        @BeforeEach
        void trip() {
            for (int i = 0; i < 4; i++) {
                gateway.embed(List.of("查一下余额"));
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        /** 熔断的全部价值：别再打过去了。 */
        @Test
        @DisplayName("不再发起任何网络请求")
        void noFurtherNetworkCalls() {
            int before = transport.attempts.get();

            for (int i = 0; i < 10; i++) {
                gateway.embed(List.of("查一下余额"));
            }

            assertThat(transport.attempts.get()).isEqualTo(before);
        }

        @Test
        @DisplayName("调用方拿到降级结果而不是异常")
        void callerGetsDegradedNotException() {
            GatewayResult<List<float[]>> result = gateway.embed(List.of("查一下余额"));

            assertThat(result.available()).isFalse();
            assertThat(result.reason()).isEqualTo("circuit-open");
        }

        /** 降级理由要能归因：分不清「熔断保护」与「网关真挂了」，处置动作是不同的。 */
        @Test
        @DisplayName("降级计数带上 circuit-open 理由")
        void degradationIsAttributable() {
            gateway.embed(List.of("查一下余额"));

            double count = Search.in(meterRegistry).name("huawei.finance.agent.degraded")
                    .tag("reason", "circuit-open")
                    .counters().stream().mapToDouble(c -> c.count()).sum();
            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("available() 跟着翻假，上游据此整条摘掉语义通道")
        void availabilityReflectsBreakerState() {
            assertThat(gateway.available()).isFalse();
        }

        /** 库自带的绑定要真的接上，否则线上看不到熔断器的状态。 */
        @Test
        @DisplayName("熔断器状态在 Micrometer 上可见")
        void breakerStateIsObservable() {
            assertThat(Search.in(meterRegistry).name("resilience4j.circuitbreaker.state").gauges())
                    .isNotEmpty();
        }

        @Test
        @DisplayName("仲裁与重排两条接口一并被熔断保护，不是只挡了 embedding")
        void allEndpointsShareTheBreaker() {
            int before = transport.attempts.get();

            gateway.chat(new ChatRequest("m", "sys", "user", 256, 0.0, true));
            gateway.rerank("查一下余额", List.of("余额查询"), 3);

            assertThat(transport.attempts.get()).isEqualTo(before);
        }
    }

    /** 每次请求都在连接阶段失败，并数一数被真正打出去了几次。 */
    private static final class CountingFailureFactory implements ClientHttpRequestFactory {

        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(
                java.net.URI uri, HttpMethod httpMethod) {
            attempts.incrementAndGet();
            throw new ResourceAccessException("connect timed out: " + uri);
        }
    }
}
