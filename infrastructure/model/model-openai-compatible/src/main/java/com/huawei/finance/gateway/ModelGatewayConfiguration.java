package com.huawei.finance.gateway;

import com.huawei.finance.contracts.validation.ContractJson;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 模型网关装配。
 *
 * <p>核心是一个**带连接池的** HttpClient。实测每次新建连接会把 embedding 从 0.35s 拖到
 * 1.2-2.2s，差额全是 TLS 握手；在快路径的毫秒级预算里，这个差额就是全部预算。
 */
@AutoConfiguration
@EnableConfigurationProperties(ModelGatewayProperties.class)
public class ModelGatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayConfiguration.class);

    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager modelGatewayConnectionManager(ModelGatewayProperties props) {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(props.getMaxConnections())
                .setMaxConnPerRoute(props.getMaxConnectionsPerRoute())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(props.getConnectTimeoutMs()))
                        .setTimeToLive(TimeValue.ofSeconds(props.getKeepAliveSeconds()))
                        .build())
                .build();
    }

    @Bean
    public HttpClient modelGatewayHttpClient(PoolingHttpClientConnectionManager cm,
                                             ModelGatewayProperties props) {
        return HttpClients.custom()
                .setConnectionManager(cm)
                .evictIdleConnections(TimeValue.ofSeconds(props.getKeepAliveSeconds()))
                // 重试策略在应用层实现（区分连接级与请求级），这里关掉以免两层重试互相叠加
                .disableAutomaticRetries()
                .build();
    }

    /**
     * 网关专用的 RestClient。
     *
     * <p>消息转换器**显式钉死**，不用默认那套。默认转换器是按类路径上有什么来挑的，
     * 而运行时同时躺着两个 Jackson：本工程自己用 2.x（契约、资产、OpenSearch 客户端都是），
     * Spring Boot 4 的 Web 栈带进来 3.x。Spring 7 的默认客户端优先选 3.x 的转换器，
     * 于是它被要求把响应读成 2.x 的 {@code JsonNode}——一个在它眼里没有构造器的抽象类型。
     *
     * <p>这个错的恶劣之处在于它**伪装成了别的故障**：抛出来的是
     * {@code HttpMessageConversionException}，网关按「调用失败」记一笔降级，
     * 于是模型明明返回了 200 和正确的 JSON，看板上却显示模型不可用、系统走规则回退。
     * 排查时人会去查网络、查密钥、查对端，唯独不会想到是本地少配了一个转换器。
     *
     * <p>钉死之后，加不加 Jackson 3、谁先谁后都不再影响这个客户端。
     */
    @Bean(name = "modelGatewayRestClient")
    public RestClient modelGatewayRestClient(HttpClient httpClient, ModelGatewayProperties props) {
        return buildRestClient(httpClient, props, props.getBaseUrl());
    }

    /**
     * 仲裁 / agent-tools 用的 RestClient。
     *
     * <p>与主网关共用连接池与超时策略，只换 baseUrl。未配置 {@code huawei.finance.agent.model.chat.base-url}
     * 时与主客户端指向同一地址，仍单独建一个 bean，避免注入歧义。
     */
    @Bean(name = "modelGatewayChatRestClient")
    public RestClient modelGatewayChatRestClient(HttpClient httpClient, ModelGatewayProperties props) {
        return buildRestClient(httpClient, props, props.resolveChatBaseUrl());
    }

    private static RestClient buildRestClient(HttpClient httpClient, ModelGatewayProperties props,
                                              String baseUrl) {
        return configureRestClient(RestClient.builder()
                .requestFactory(new PathAwareRequestFactory(httpClient, props)), baseUrl);
    }

    /** 共享客户端转换器配置；包内测试可换入无端口的 Mock HTTP 传输。 */
    static RestClient configureRestClient(RestClient.Builder builder, String baseUrl) {
        return builder.baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(ContractJson.mapper()));
                    converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
                })
                .build();
    }

    /**
     * 熔断器经 Registry 创建而非 {@code CircuitBreaker.of(...)}。
     *
     * <p>差别不在熔断行为，而在能不能被观测：Resilience4j 的 Micrometer 绑定只认 Registry，
     * 脱离 Registry 单独 new 出来的实例在监控上是隐形的。
     */
    @Bean
    public CircuitBreakerRegistry modelGatewayCircuitBreakerRegistry(ModelGatewayProperties props) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getCircuitBreakerFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(props.getCircuitBreakerWaitSeconds()))
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker modelGatewayCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("model-gateway");
    }

    /**
     * 传输层重试。
     *
     * <p>重试条件是「传输没有干净完成」：连不上，或连上了但响应读到一半断掉。
     * 后者在公网链路上真实发生过，Spring 会把它包成 {@code RestClientException}，
     * 根因藏在几层之下的 {@code IOException} 里，只按 {@code ResourceAccessException} 判会漏。
     *
     * <p>收到完整 HTTP 响应后的失败（4xx/5xx）不在此列——那是请求级重试，语义完全不同：
     * 429 该退避，400 重试多少次都是同一个错。
     *
     * <p>三个接口都是只读的，重放最多多花一次 token，不产生副作用。
     */
    @Bean
    public RetryRegistry modelGatewayRetryRegistry(ModelGatewayProperties props) {
        RetryConfig.Builder<Object> config = RetryConfig.custom()
                .maxAttempts(Math.max(1, props.getConnectRetries() + 1))
                .retryOnException(ModelGatewayConfiguration::retryableTransportFailure);
        // Resilience4j 默认退避 500ms。快路径的毫秒预算里挤不出这个时间，而握手失败多是瞬时
        // 抖动，等待并不提高下一次的成功率，所以默认压到 1ms（近似立即重试）。
        config.waitDuration(Duration.ofMillis(Math.max(1, props.getRetryBackoffMs())));
        return RetryRegistry.of(config.build());
    }

    @Bean
    public Retry modelGatewayRetry(RetryRegistry registry) {
        Retry retry = registry.retry("model-gateway");
        retry.getEventPublisher().onRetry(e -> log.debug("网关传输失败重试 attempt={} cause={}",
                e.getNumberOfRetryAttempts(),
                OpenAiCompatibleModelGateway.causeChain(e.getLastThrowable())));
        return retry;
    }

    static boolean retryableTransportFailure(Throwable t) {
        if (t instanceof RestClientResponseException) {
            return false;
        }
        if (t instanceof ResourceAccessException) {
            return true;
        }
        if (!(t instanceof RestClientException)) {
            return false;
        }
        for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 Resilience4j 自带的 Micrometer 绑定接上。
     *
     * <p>熔断器状态、慢调用比例、重试次数这些，库本身就在统计。不接绑定就等于自己再数一遍，
     * 而自己数出来的永远比库里的少几个维度——比如「重试之后成功了」这种情况，
     * 业务侧的降级计数根本看不见，只有 {@code resilience4j.retry.calls} 的
     * {@code successful_with_retry} 标签能说明问题。
     */
    @Bean
    public TaggedCircuitBreakerMetrics modelGatewayCircuitBreakerMetrics(
            CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics =
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    @Bean
    public TaggedRetryMetrics modelGatewayRetryMetrics(RetryRegistry registry,
                                                       MeterRegistry meterRegistry) {
        TaggedRetryMetrics metrics = TaggedRetryMetrics.ofRetryRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    /**
     * 模型网关。**扩展点，且是行内落地时几乎必然要换的那一个。**
     *
     * <p>基线走 OpenAI 兼容协议，行内通常有统一推理入口，协议、鉴权、模型名都不一样。
     *
     * <p>替换时注意装饰顺序：{@link BudgetAwareModelGateway} 是往返预算的执法者，
     * 必须包在最外层。行内实现若自己直接注册成 {@code ModelGatewayClient}，
     * 这层装饰就没了——往返用途序列会静默丢光，
     * 而它失效时没有任何报错，只是账单在涨。行内实现应当只替换内层，
     * 或者自己也套上这个装饰器。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelGatewayClient modelGatewayClient(
            @Qualifier("modelGatewayRestClient") RestClient restClient,
            @Qualifier("modelGatewayChatRestClient") RestClient chatRestClient,
            ModelGatewayProperties props,
            CircuitBreaker circuitBreaker,
            Retry retry,
            MeterRegistry meterRegistry) {
        String apiKey = props.resolveApiKey();
        String chatApiKey = props.resolveChatApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置主网关密钥（环境变量 {} 或 huawei.finance.agent.model.api-key），"
                            + "embedding/rerank 将始终返回不可用，语义通道按降级运行。"
                            + "这是设计内的分支，不是故障。",
                    props.getApiKeyEnv());
        }
        if (chatApiKey == null || chatApiKey.isBlank()) {
            log.warn("未配置 chat 密钥（环境变量 {} 或 huawei.finance.agent.model.chat.api-key），"
                            + "仲裁与 agent loop 将始终返回不可用，系统按规则回退运行。"
                            + "这是设计内的分支，不是故障。",
                    props.resolveChatApiKeyEnv());
        }
        // 往返预算包在最外层：无论底下换成哪个实现，A 线 ≤2 次的计数都还在
        return new BudgetAwareModelGateway(
                new OpenAiCompatibleModelGateway(restClient, chatRestClient, props, circuitBreaker,
                        retry, meterRegistry, apiKey, chatApiKey),
                meterRegistry);
    }

    /**
     * 按 URI 路径决定读超时的请求工厂。
     *
     * <p>三类调用的合理超时差一个数量级（embedding 2s，仲裁 5s），用同一个全局超时要么把
     * 快路径拖死，要么把仲裁误杀。Spring 的 RestClient 没有按次设超时的入口，
     * 但 HttpComponents 允许通过 HttpContext 携带 RequestConfig，路径本身就足以区分用途。
     */
    private static final class PathAwareRequestFactory extends HttpComponentsClientHttpRequestFactory {

        private final ModelGatewayProperties props;

        PathAwareRequestFactory(HttpClient httpClient, ModelGatewayProperties props) {
            super(httpClient);
            this.props = props;
        }

        @Override
        protected HttpContext createHttpContext(HttpMethod httpMethod, URI uri) {
            HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(timeoutFor(uri)))
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(props.getConnectTimeoutMs()))
                    .build());
            return context;
        }

        private int timeoutFor(URI uri) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/embeddings")) {
                return props.getEmbedding().getTimeoutMs();
            }
            if (path.endsWith("/rerank")) {
                return props.getRerank().getTimeoutMs();
            }
            return props.getArbitration().getTimeoutMs();
        }
    }
}
