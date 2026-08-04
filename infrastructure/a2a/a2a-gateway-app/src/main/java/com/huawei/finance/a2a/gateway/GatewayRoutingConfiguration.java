package com.huawei.finance.a2a.gateway;

import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardProjector;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.DelegationStore;
import com.huawei.finance.a2a.JdbcDelegationStore;
import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import com.huawei.finance.nacos.discovery.NacosAgentDirectory;
import com.huawei.finance.nacos.discovery.NacosEndpointResolver;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import io.micrometer.observation.ObservationRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRouteProperties.class)
public class GatewayRoutingConfiguration {

    @Bean
    public DelegationStore gatewayDelegationStore(JdbcTemplate jdbcTemplate) {
        return new JdbcDelegationStore(jdbcTemplate);
    }

    @Bean
    public AgentEndpointResolver gatewayEndpointResolver(
            ObjectProvider<NacosAgentDirectory> directories, GatewayRouteProperties routes) {
        var staticTargets = new LinkedHashMap<String, String>();
        routes.getTargets().forEach((agentId, uri) -> {
            if (uri != null) {
                staticTargets.put(agentId, uri.toString());
            }
        });
        AgentEndpointResolver fallback = AgentEndpointResolver.ofStatic(staticTargets);
        NacosAgentDirectory directory = directories.getIfAvailable();
        return directory == null ? fallback : new NacosEndpointResolver(directory, fallback);
    }

    @Bean
    public AgentCardProjector gatewayAgentCardProjector(A2AProperties properties) {
        return new AgentCardProjector(properties.resolveCardLocations());
    }

    @Bean
    public AgentCardRegistry gatewayAgentCardRegistry(AgentCardProjector projector,
                                                      AgentEndpointResolver resolver,
                                                      ObjectProvider<RestClient.Builder> builders,
                                                      ObservationRegistry observations) {
        RestClient.Builder clientBuilder = builders.getIfAvailable(() ->
                RestClient.builder().observationRegistry(observations));
        List<AgentCard> cards = projector.project();
        List<AgentNode> nodes = cards.stream()
                .map(card -> new HttpRemoteAgentNode(card.agentId(), resolver, clientBuilder))
                .map(AgentNode.class::cast)
                .toList();
        return new AgentCardRegistry(cards, nodes);
    }
}
