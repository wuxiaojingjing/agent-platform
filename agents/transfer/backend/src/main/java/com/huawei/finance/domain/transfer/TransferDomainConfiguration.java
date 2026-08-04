package com.huawei.finance.domain.transfer;

import com.huawei.finance.contracts.port.TechDomainAgent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/** 转账域节点装配（阶段 3b 首批）。 */
@AutoConfiguration
public class TransferDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransferPort.class)
    public TransferPort transferPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.transfer.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.transfer.timeout:3s}") Duration timeout) {
        return new HttpTransferPort(builder, baseUrl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean(name = "transferDomainAgent")
    public TechDomainAgent transferDomainAgent(TransferPort port) {
        return new TransferDomainAgent(port);
    }
}
