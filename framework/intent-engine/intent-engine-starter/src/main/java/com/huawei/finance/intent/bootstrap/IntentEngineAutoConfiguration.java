package com.huawei.finance.intent.bootstrap;

import com.huawei.finance.fastpath.FastPathAssembly;
import com.huawei.finance.fastpath.FastPathConfiguration;
import com.huawei.finance.intent.IntentEngineFactory;
import com.huawei.finance.slowpath.SlowPathConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 聚合快/慢路径自动配置，供 Agent 只依赖本 Starter。 */
@AutoConfiguration
@AutoConfigureAfter(FastPathConfiguration.class)
@ImportAutoConfiguration({FastPathConfiguration.class, SlowPathConfiguration.class})
public class IntentEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IntentEngineFactory.class)
    public IntentEngineFactory intentEngineFactory() {
        return platformDefault -> platformDefault;
    }

    @Bean
    @ConditionalOnBean(FastPathAssembly.Infrastructure.class)
    @ConditionalOnMissingBean
    public IntentEngines.Infrastructure intentEnginesInfrastructure(
            FastPathAssembly.Infrastructure infra) {
        return IntentEngines.wrap(infra);
    }
}
