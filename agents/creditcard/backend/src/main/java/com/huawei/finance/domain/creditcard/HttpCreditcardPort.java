package com.huawei.finance.domain.creditcard;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class HttpCreditcardPort implements CreditcardPort {
    private final RestClient client;
    HttpCreditcardPort(RestClient.Builder builder, String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
    @Override public BillView bill(String principalRef, String cardRef) {
        return require(client.get().uri(builder -> builder
                        .path("/api/creditcards/{principal}/bill")
                        .queryParam("cardRef", cardRef).build(principalRef))
                .retrieve().body(BillView.class));
    }
    @Override public OperationReceipt repay(RepayCommand command) {
        return require(client.post().uri("/api/creditcards/repayments").body(command)
                .retrieve().body(OperationReceipt.class));
    }
    @Override public OperationReceipt replace(ReplaceCommand command) {
        return require(client.post().uri("/api/creditcards/replacements").body(command)
                .retrieve().body(OperationReceipt.class));
    }
    private static <T> T require(T value) {
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "CREDITCARD_RESPONSE_EMPTY");
        return value;
    }
}
