package com.huawei.finance.domain.account;

import java.util.List;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class HttpAccountPort implements AccountPort {
    private final RestClient client;

    HttpAccountPort(RestClient.Builder builder, String baseUrl, Duration timeout) {
        this.client = client(builder, baseUrl, timeout);
    }

    @Override public AccountView accountView(String principalRef) {
        AccountView value = client.get().uri("/api/accounts/{principal}/balances", principalRef)
                .retrieve().body(AccountView.class);
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "ACCOUNT_RESPONSE_EMPTY");
        return value;
    }

    @Override public List<TransactionView> transactions(String principalRef) {
        TransactionView[] value = client.get().uri("/api/accounts/{principal}/transactions", principalRef)
                .retrieve().body(TransactionView[].class);
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "ACCOUNT_TRANSACTIONS_RESPONSE_EMPTY");
        return List.of(value);
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
