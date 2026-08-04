package com.huawei.finance.domain.insurance;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
final class HttpInsuranceProductPort implements InsuranceProductPort {
    private final RestClient client;
    HttpInsuranceProductPort(RestClient.Builder builder, String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
    public ProductView product(String principalRef) {
        ProductView value = client.get().uri("/api/insurance/products?principal={principal}", principalRef)
                .retrieve().body(ProductView.class);
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "INSURANCE_RESPONSE_EMPTY");
        return value;
    }
}
