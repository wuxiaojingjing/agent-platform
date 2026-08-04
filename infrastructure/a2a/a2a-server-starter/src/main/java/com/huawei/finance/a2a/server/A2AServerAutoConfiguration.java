package com.huawei.finance.a2a.server;

import com.huawei.finance.contracts.a2a.AgentNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

@AutoConfiguration
public class A2AServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LocalAgentNodeRegistry localAgentNodeRegistry(ObjectProvider<AgentNode> nodes) {
        return new LocalAgentNodeRegistry(nodes.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public A2AInboundController a2AInboundController(LocalAgentNodeRegistry nodes,
                                                     MeterRegistry meterRegistry,
                                                     ObjectProvider<Tracer> tracers) {
        return new A2AInboundController(nodes, meterRegistry, tracers.getIfAvailable());
    }
}
