package com.huawei.finance.domain.finance;

import com.huawei.finance.contracts.port.TechDomainAgent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 金融助手域节点装配（阶段 3b 首批）。 */
@AutoConfiguration
public class FinanceDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean(NavigationCatalogPort.class)
    public NavigationCatalogPort navigationCatalogPort() {
        return new YamlNavigationCatalog();
    }

    @Bean
    @ConditionalOnMissingBean(name = "financeDomainAgent")
    public TechDomainAgent financeDomainAgent(NavigationCatalogPort catalog) {
        return new FinanceDomainAgent(catalog);
    }
}
