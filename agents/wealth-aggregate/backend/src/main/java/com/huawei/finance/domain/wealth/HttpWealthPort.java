package com.huawei.finance.domain.wealth;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
final class HttpWealthPort implements WealthPort {
    private final RestClient client;
    HttpWealthPort(RestClient.Builder builder, String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
    public HoldingView holdings(String principalRef) {
        HoldingView value = client.get().uri("/api/wealth/{principal}/holdings", principalRef)
                .retrieve().body(HoldingView.class);
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "WEALTH_RESPONSE_EMPTY");
        return value;
    }
}
