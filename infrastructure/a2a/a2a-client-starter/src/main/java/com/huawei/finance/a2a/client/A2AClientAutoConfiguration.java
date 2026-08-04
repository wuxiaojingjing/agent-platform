package com.huawei.finance.a2a.client;

import com.huawei.finance.a2a.A2ADispatcher;
import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCardProjector;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.contracts.port.ContextStateVersionProvider;
import com.huawei.finance.contracts.port.DomainReferenceResolver;
import com.huawei.finance.contracts.port.ExecutionParameterResolver;
import com.huawei.finance.registry.asset.AssetBundle;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;
import io.micrometer.tracing.Tracer;
import io.micrometer.observation.ObservationRegistry;

@AutoConfiguration
@EnableConfigurationProperties({A2AProperties.class, RemoteA2AProperties.class,
        A2ADelegationProperties.class})
public class A2AClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(A2ADispatcher.class)
    public A2ADispatcher remoteA2ADispatcher(RemoteA2AProperties properties,
                                             ObjectProvider<RestClient.Builder> builders,
                                             ObservationRegistry observations,
                                             ObjectProvider<Tracer> tracers) {
        return new HttpA2ADispatcher(properties,
                builders.getIfAvailable(() -> RestClient.builder()
                        .observationRegistry(observations)),
                tracers.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentCardRegistry agentCardRegistry(A2AProperties properties) {
        return new AgentCardRegistry(new AgentCardProjector(properties.resolveCardLocations()).project(), List.of());
    }

    @Bean
    @ConditionalOnMissingBean
    public DelegationClient delegationClient(A2ADispatcher dispatcher, A2AProperties properties,
                                             MeterRegistry meterRegistry) {
        return new DelegationClient(dispatcher, properties, meterRegistry, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(CapabilityDelegator.class)
    @ConditionalOnProperty(name = "huawei.finance.agent.a2a.delegation.enabled", havingValue = "true")
    public CapabilityDelegator a2aCapabilityDelegator(
            DelegationClient client, AgentCardRegistry registry,
            AssetBundle assets, AgentIdentity identity, MeterRegistry meterRegistry,
            ObjectProvider<Tracer> tracers,
            ObjectProvider<ContextStateVersionProvider> contextVersions) {
        return new A2ACapabilityDelegator(client, registry, assets, identity,
                meterRegistry, tracers.getIfAvailable(),
                contextVersions.getIfAvailable(() -> ContextStateVersionProvider.UNKNOWN));
    }

    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.a2a.delegation.enabled", havingValue = "true")
    A2AReferenceResolutionClient a2aReferenceResolutionClient(
            DelegationClient client, AgentCardRegistry registry,
            AssetBundle assets, AgentIdentity identity, ObjectProvider<Tracer> tracers) {
        return new A2AReferenceResolutionClient(client, registry, assets, identity,
                tracers.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.a2a.delegation.enabled", havingValue = "true")
    DomainReferenceResolver a2aRemoteDomainReferenceResolver(
            A2AReferenceResolutionClient client, AgentCardRegistry registry) {
        return new A2ARemoteDomainReferenceResolver(client, registry);
    }

    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.a2a.delegation.enabled", havingValue = "true")
    ExecutionParameterResolver a2aExecutionParameterResolver(
            A2AReferenceResolutionClient client) {
        return new A2AExecutionParameterResolver(client);
    }
}
