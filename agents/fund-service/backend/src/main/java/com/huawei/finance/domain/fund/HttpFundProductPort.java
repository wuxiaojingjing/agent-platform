package com.huawei.finance.domain.fund;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
final class HttpFundProductPort implements FundProductPort {
    private final RestClient client;
    HttpFundProductPort(RestClient.Builder builder, String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
    public ProductView product(String principalRef) {
        ProductView value = client.get().uri("/api/funds/products?principal={principal}", principalRef)
                .retrieve().body(ProductView.class);
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "FUND_RESPONSE_EMPTY");
        return value;
    }
}
