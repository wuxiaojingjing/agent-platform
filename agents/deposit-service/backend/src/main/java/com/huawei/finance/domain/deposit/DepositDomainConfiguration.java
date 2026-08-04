package com.huawei.finance.domain.deposit;

import com.huawei.finance.contracts.port.TechDomainAgent;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class DepositDomainConfiguration {
    @Bean @ConditionalOnMissingBean(DepositCatalogPort.class)
    DepositCatalogPort depositCatalogPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.deposit.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.deposit.timeout:2s}") Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout); factory.setReadTimeout(timeout);
        RestClient client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        return () -> {
            Map<?, ?> body = client.get().uri("/api/deposits/products").retrieve().body(Map.class);
            return product(body);
        };
    }
    @Bean @ConditionalOnMissingBean(name = "depositDomainAgent")
    TechDomainAgent depositDomainAgent(DepositCatalogPort port) { return new DepositDomainAgent(port); }
    private static DepositCatalogPort.ProductView product(Map<?, ?> body) {
        if (body == null) throw new IllegalStateException("empty deposit response");
        return new DepositCatalogPort.ProductView(value(body,"productCode"), value(body,"name"), value(body,"domain"),
                value(body,"riskLevel"), value(body,"returnRate"), value(body,"term"));
    }
    private static String value(Map<?,?> map, String key) { Object value = map.get(key); return value == null ? "" : String.valueOf(value); }
}
