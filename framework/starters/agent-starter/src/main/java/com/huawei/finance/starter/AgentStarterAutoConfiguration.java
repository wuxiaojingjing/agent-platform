package com.huawei.finance.starter;

import com.huawei.finance.common.context.AgentProperties;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.intent.bootstrap.IntentEngineAutoConfiguration;
import com.huawei.finance.runtime.bootstrap.AgentRuntimeAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Agent 集成最小自动配置。生产 A2A 只装远程客户端；进程内实现位于 testkit。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties.class)
@ImportAutoConfiguration({
        IntentEngineAutoConfiguration.class,
        AgentRuntimeAutoConfiguration.class
})
public class AgentStarterAutoConfiguration {

    @Bean
    public AgentIdentity agentIdentity(AgentProperties properties) {
        return new AgentIdentity(properties.getId());
    }
}
