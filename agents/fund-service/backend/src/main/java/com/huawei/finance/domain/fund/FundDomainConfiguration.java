package com.huawei.finance.domain.fund;

import com.huawei.finance.contracts.port.TechDomainAgent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/** 基金域节点装配（阶段 3b 首批）。 */
@AutoConfiguration
public class FundDomainConfiguration {
    @Bean @ConditionalOnMissingBean(FundProductPort.class)
    public FundProductPort fundProductPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.fund.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.fund.timeout:2s}") Duration timeout) {
        return new HttpFundProductPort(builder, baseUrl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean(name = "fundDomainAgent")
    public TechDomainAgent fundDomainAgent(FundProductPort port) {
        return new FundDomainAgent(port);
    }
}
