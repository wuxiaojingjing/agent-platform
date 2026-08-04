package com.huawei.finance.domain.creditcard;

import com.huawei.finance.contracts.port.TechDomainAgent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/** 信用卡域节点装配（阶段 3b 首批）。 */
@AutoConfiguration
public class CreditcardDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean(CreditcardPort.class)
    public CreditcardPort creditcardPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.creditcard.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.creditcard.timeout:3s}") Duration timeout) {
        return new HttpCreditcardPort(builder, baseUrl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean(name = "creditcardDomainAgent")
    public TechDomainAgent creditcardDomainAgent(CreditcardPort port) {
        return new CreditcardDomainAgent(port);
    }
}
