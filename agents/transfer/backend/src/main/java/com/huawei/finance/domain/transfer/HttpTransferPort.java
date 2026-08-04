package com.huawei.finance.domain.transfer;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class HttpTransferPort implements TransferPort {
    private final RestClient client;
    HttpTransferPort(RestClient.Builder builder, String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
    @Override public TransferReceipt submit(TransferCommand command) {
        TransferReceipt value = client.post().uri("/api/transfers").body(command)
                .retrieve().body(TransferReceipt.class);
        if (value == null) throw new org.springframework.web.client.ResourceAccessException(
                "TRANSFER_RESPONSE_EMPTY");
        return value;
    }
}
