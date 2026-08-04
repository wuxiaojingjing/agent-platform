package com.huawei.finance.a2a.gateway;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.net.URI;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

final class HttpRemoteAgentNode implements AgentNode {

    private final String agentId;
    private final AgentEndpointResolver resolver;
    private final RestClient.Builder clientBuilder;

    HttpRemoteAgentNode(String agentId, AgentEndpointResolver resolver) {
        this(agentId, resolver, RestClient.builder());
    }

    HttpRemoteAgentNode(String agentId, URI baseUrl) {
        this(agentId, AgentEndpointResolver.ofStatic(
                baseUrl == null ? java.util.Map.of() : java.util.Map.of(agentId, baseUrl.toString())));
    }

    HttpRemoteAgentNode(String agentId, URI baseUrl, RestClient.Builder builder) {
        this(agentId, AgentEndpointResolver.ofStatic(
                baseUrl == null ? java.util.Map.of() : java.util.Map.of(agentId, baseUrl.toString())), builder);
    }

    HttpRemoteAgentNode(String agentId, AgentEndpointResolver resolver, RestClient.Builder builder) {
        this.agentId = agentId;
        this.resolver = resolver;
        this.clientBuilder = builder;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public boolean autonomous() {
        return true;
    }

    @Override
    public DelegationReceipt handle(DelegationEnvelope envelope) {
        String baseUrl = resolver.resolve(agentId).orElse(null);
        if (baseUrl == null) {
            return DelegationReceipt.fatal(envelope.delegationId(), "AGENT_ENDPOINT_MISSING",
                    "Gateway 未发现目标 Agent 地址：" + agentId);
        }
        try {
            DelegationReceipt receipt = clientBuilder.clone().baseUrl(baseUrl).build()
                    .post().uri("/a2a/v2/inbound").body(envelope)
                    .retrieve().body(DelegationReceipt.class);
            return DelegationReceipt.requireValidEnvelope(receipt, envelope.delegationId());
        } catch (RestClientException ex) {
            return DelegationReceipt.fatal(envelope.delegationId(), "AGENT_UNREACHABLE",
                    "Gateway 调用目标 Agent 失败：" + ex.getClass().getSimpleName());
        }
    }
}
