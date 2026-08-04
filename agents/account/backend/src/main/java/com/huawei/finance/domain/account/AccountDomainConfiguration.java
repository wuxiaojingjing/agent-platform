package com.huawei.finance.domain.account;

import com.huawei.finance.contracts.port.DomainReferenceResolver;
import com.huawei.finance.contracts.port.TechDomainAgent;
import com.huawei.finance.contracts.port.ExecutionParameterResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/**
 * 账户域节点装配：域内指代 + 叶子执行器（阶段 1.5 / 3a）。
 *
 * <p>无开关：classpath 有本模块即交付账户节点语义。{@code TechDomainAgent} 会被
 * {@code a2a-gateway} 的 {@code TechDomainNodeFactory} 升级为真 {@code AgentNode}，
 * 不再依赖 {@code huawei.finance.sample.mock.enabled}。
 *
 * <p>{@code @ConditionalOnMissingBean} 让位：行内可声明同类型 Bean 接管，不必改本模块。
 */
@AutoConfiguration
public class AccountDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "accountReferenceResolver")
    public DomainReferenceResolver accountReferenceResolver() {
        return new AccountReferenceResolver();
    }

    @Bean
    @ConditionalOnMissingBean(AccountPort.class)
    public AccountPort accountPort(RestClient.Builder builder,
            @Value("${huawei.finance.backends.account.base-url:http://banking-systems-simulator:8090}") String baseUrl,
            @Value("${huawei.finance.backends.account.timeout:2s}") Duration timeout) {
        return new HttpAccountPort(builder, baseUrl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean(name = "accountDomainAgent")
    public TechDomainAgent accountDomainAgent(AccountPort port) {
        return new AccountDomainAgent(port);
    }

    @Bean
    @ConditionalOnMissingBean(name = "accountExecutionParameterResolver")
    public ExecutionParameterResolver accountExecutionParameterResolver(AccountPort port) {
        return new AccountExecutionParameterResolver(port);
    }
}
