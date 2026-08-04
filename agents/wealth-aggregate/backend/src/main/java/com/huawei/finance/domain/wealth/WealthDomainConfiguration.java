package com.huawei.finance.domain.wealth;

import com.huawei.finance.contracts.port.TechDomainAgent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/** 财富聚合域节点装配（阶段 3b 首批）。 */
@AutoConfiguration
public class WealthDomainConfiguration {
    @Bean @ConditionalOnMissingBean(WealthPort.class)
    public WealthPort wealthPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.wealth.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.wealth.timeout:2s}") Duration timeout) {
        return new HttpWealthPort(builder, baseUrl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean(name = "wealthDomainAgent")
    public TechDomainAgent wealthDomainAgent(WealthPort port) {
        return new WealthDomainAgent(port);
    }
}
