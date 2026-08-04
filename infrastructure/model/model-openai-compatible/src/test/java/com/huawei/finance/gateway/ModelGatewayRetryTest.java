package com.huawei.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 传输层重试的判定与次数。
 *
 * <p>这里不连网关：要验的是「什么样的失败该重试」，而不是「网关通不通」。
 * 用真实网络验这件事既慢又不可复现，反而把判定逻辑本身的回归盖住了。
 */
class ModelGatewayRetryTest {

    private final ModelGatewayProperties props = new ModelGatewayProperties();

    @Test
    void connectFailureIsRetried() {
        assertThat(ModelGatewayConfiguration.retryableTransportFailure(
                new ResourceAccessException("connect timed out"))).isTrue();
    }

    /**
     * 连上了但响应读到一半断掉。
     *
     * <p>Spring 把它包成朴素的 RestClientException，根因藏在两层之下。这是公网链路上真实
     * 发生过的形态，只按 ResourceAccessException 判会整类漏掉。
     */
    @Test
    void truncatedResponseIsRetried() {
        RestClientException wrapped = new RestClientException("read failed",
                new IllegalStateException("stream closed", new SocketException("Connection reset")));
        assertThat(ModelGatewayConfiguration.retryableTransportFailure(wrapped)).isTrue();
    }

    /**
     * 拿到完整 HTTP 响应后的失败不重试。
     *
     * <p>400 重试多少次都是同一个 400，白烧一次 token 和一次往返预算。
     */
    @Test
    void httpErrorIsNotRetried() {
        assertThat(ModelGatewayConfiguration.retryableTransportFailure(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        null, null, null))).isFalse();
    }

    @Test
    void unrelatedFailureIsNotRetried() {
        assertThat(ModelGatewayConfiguration.retryableTransportFailure(
                new IllegalArgumentException("向量维度不符"))).isFalse();
    }

    @Test
    void retryStopsAtConfiguredAttempts() {
        props.setConnectRetries(2);
        Retry retry = new ModelGatewayConfiguration()
                .modelGatewayRetry(new ModelGatewayConfiguration().modelGatewayRetryRegistry(props));

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> alwaysFails = Retry.decorateSupplier(retry, () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("connect timed out");
        });

        try {
            alwaysFails.get();
        } catch (ResourceAccessException expected) {
            // 重试耗尽后抛出原始异常，由 call() 统一转成降级
        }
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void nonRetryableFailsOnFirstAttempt() {
        Retry retry = new ModelGatewayConfiguration()
                .modelGatewayRetry(new ModelGatewayConfiguration().modelGatewayRetryRegistry(props));

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> badRequest = Retry.decorateSupplier(retry, () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                    null, null, null);
        });

        try {
            badRequest.get();
        } catch (RestClientException expected) {
            // 预期
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ioCauseAtAnyDepthIsRetried() {
        RestClientException deep = new RestClientException("outer",
                new IllegalStateException("mid", new RuntimeException("inner", new IOException("eof"))));
        assertThat(ModelGatewayConfiguration.retryableTransportFailure(deep)).isTrue();
    }
}
