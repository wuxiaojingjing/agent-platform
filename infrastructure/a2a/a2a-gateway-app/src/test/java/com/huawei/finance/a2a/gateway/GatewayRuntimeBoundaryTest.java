package com.huawei.finance.a2a.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.a2a.DelegationStore;
import com.huawei.finance.a2a.JdbcDelegationStore;
import com.huawei.finance.context.ContextProperties;
import com.huawei.finance.context.TurnStore;
import com.huawei.finance.orchestrator.TaskOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class GatewayRuntimeBoundaryTest {

    @Test
    void gatewayLoadsDelegationPersistenceWithoutAgentRuntime() {
        try (var context = new SpringApplicationBuilder(A2AGatewayApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:65432/agent_platform",
                        "spring.datasource.username=agent_platform",
                        "spring.datasource.password=agent_platform",
                        "spring.flyway.enabled=false",
                        "huawei.finance.agent.nacos.enabled=false")
                .run()) {
            assertThat(context.getBean(DelegationStore.class)).isInstanceOf(JdbcDelegationStore.class);
            assertThat(context.getBeansOfType(TaskOrchestrator.class)).isEmpty();
            assertThat(context.getBeansOfType(ContextProperties.class)).isEmpty();
            assertThat(context.getBeansOfType(TurnStore.class)).isEmpty();
        }
    }
}
