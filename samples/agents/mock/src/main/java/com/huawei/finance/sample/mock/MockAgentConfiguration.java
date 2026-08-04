package com.huawei.finance.sample.mock;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Mock 领域 Agent 装配：一科技域一 Bean，便于逐域替换成 OJ Agent Server。
 *
 * <p><b>默认关闭，必须显式打开。</b>原因见类历史注释——Mock 写死假数据，与真实通道同开会不确定派单。
 *
 * <p>阶段 3b 首批（account + transfer / creditcard / wealth / fund / insurance / finance）
 * 已上收至 {@code agents/*}，见 {@link #EXTERNALLY_DELIVERED_DOMAINS}；本配置不再为它们注册 Bean。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "huawei.finance.sample.mock.enabled", havingValue = "true")
public class MockAgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MockAgentConfiguration.class);

    /** 附录 F 全部 26 科技域码。 */
    static final List<String> ALL_TECH_DOMAINS = List.of(
            "wealth_product", "fund_service", "precious_metal", "bond_service", "fx_service",
            "deposit_service", "insurance_service", "wealth_aggregate", "loan_service",
            "security_service", "personal_info", "finance_assistant", "channel_settings",
            "advisory_service", "vip_service", "payroll_service", "livelihood_service",
            "enterprise_service", "benefits_ops", "life_service", "e_cny", "branch_service",
            "creditcard_service", "account", "payment", "transfer");

    /**
     * 本期仍由 mock 模块提供可执行 Bean 的域。
     * 首批 6 域已上收后为空；类 {@code Mock*Agent} 保留作测试夹具。
     */
    static final Set<String> IMPLEMENTED_DOMAINS = Set.of();

    /**
     * 已由 {@code agents/*} 交付叶子的域：不注册 Scaffold，也不再挂 Mock*Agent Bean。
     */
    static final Set<String> EXTERNALLY_DELIVERED_DOMAINS = Set.of(
            "account",
            "transfer",
            "creditcard_service",
            "wealth_aggregate",
            "fund_service",
            "insurance_service",
            "finance_assistant",
            "deposit_service",
            "loan_service",
            "payroll_service",
            "wealth_product");

    private final Environment environment;

    MockAgentConfiguration(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void warnMockAgentsActive() {
        log.warn("Mock 领域 Agent 已启用（huawei.finance.sample.mock.enabled=true）。"
                + "余额、账单、转账返回的都是写死的假数据，生产环境必须关闭");

        if (environment.getProperty("huawei.finance.sample.openjiuwen.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "huawei.finance.sample.mock.enabled 与 huawei.finance.sample.openjiuwen.enabled 不得同时为 true："
                            + "同一能力会有两个 DomainAgent 承接，走真实通道还是走假数据取决于 Bean 顺序。"
                            + "生产部署请关掉 mock");
        }
    }

    /**
     * 为无 TOOL 科技域各注册一个 {@link ScaffoldDomainAgent} Bean，
     * 才能被 {@code List<DomainAgent>} / AgentInvoker 收集到。
     */
    @Bean
    public static BeanDefinitionRegistryPostProcessor scaffoldDomainAgentRegistrar() {
        return new ScaffoldDomainAgentRegistrar();
    }

    static final class ScaffoldDomainAgentRegistrar implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            int count = 0;
            for (String code : ALL_TECH_DOMAINS) {
                if (IMPLEMENTED_DOMAINS.contains(code)
                        || EXTERNALLY_DELIVERED_DOMAINS.contains(code)) {
                    continue;
                }
                RootBeanDefinition def = new RootBeanDefinition(ScaffoldDomainAgent.class);
                def.getConstructorArgumentValues().addIndexedArgumentValue(0, code);
                registry.registerBeanDefinition("scaffoldDomainAgent." + code, def);
                count++;
            }
            log.info("已注册科技域脚手架子 Agent {} 个（无 TOOL 域）", count);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // no-op
        }
    }
}
