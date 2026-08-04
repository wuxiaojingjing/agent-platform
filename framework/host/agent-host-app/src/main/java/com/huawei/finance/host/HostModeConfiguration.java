package com.huawei.finance.host;

import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.node.ScaffoldAgentNode;
import com.huawei.finance.a2a.node.RuntimeBackedAgentNode;
import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.PrincipalResolver;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.port.TechDomainAgent;
import com.huawei.finance.runtime.invocation.AgentInvocationRuntime;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class HostModeConfiguration {

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.implementation-mode", havingValue = "scaffold")
    public AgentNode scaffoldAgentNode(
            AgentIdentity identity,
            @org.springframework.beans.factory.annotation.Value("${huawei.finance.agent.domains}")
            List<String> domains) {
        return new ScaffoldAgentNode(identity.id(), domains.getFirst());
    }

    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.implementation-mode", havingValue = "extension")
    public AgentNode extensionAgentNode(AgentIdentity identity,
                                        ObjectProvider<TechDomainAgent> agents,
                                        AgentInvocationRuntime runtime,
                                        PrincipalResolver principalResolver) {
        List<TechDomainAgent> implementations = agents.orderedStream().toList();
        if (implementations.isEmpty()) {
            throw new IllegalStateException("领域扩展 JAR 未提供 TechDomainAgent，拒绝以空实现上线");
        }

        List<String> implementationIds = implementations.stream()
                .map(TechDomainAgent::agentId).distinct().toList();
        if (implementationIds.size() != 1 || !identity.id().equals(implementationIds.getFirst())) {
            throw new IllegalStateException("Extension Host 必须装配且只能装配一个与身份匹配的领域节点"
                    + " identity=" + identity.id()
                    + " implementations=" + implementationIds
                    + " matchingNodes=0");
        }
        return new RuntimeBackedAgentNode(identity.id(), runtime, principalResolver);
    }

}
