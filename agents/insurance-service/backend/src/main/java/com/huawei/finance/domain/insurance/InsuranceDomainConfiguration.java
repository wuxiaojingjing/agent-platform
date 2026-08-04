package com.huawei.finance.domain.insurance;

import com.huawei.finance.contracts.port.TechDomainAgent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/** 保险域节点装配（阶段 3b 首批）。 */
@AutoConfiguration
public class InsuranceDomainConfiguration {
    @Bean @ConditionalOnMissingBean(InsuranceProductPort.class)
    public InsuranceProductPort insuranceProductPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.insurance.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.insurance.timeout:2s}") Duration timeout) {
        return new HttpInsuranceProductPort(builder, baseUrl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean(name = "insuranceDomainAgent")
    public TechDomainAgent insuranceDomainAgent(InsuranceProductPort port) {
        return new InsuranceDomainAgent(port);
    }
}
