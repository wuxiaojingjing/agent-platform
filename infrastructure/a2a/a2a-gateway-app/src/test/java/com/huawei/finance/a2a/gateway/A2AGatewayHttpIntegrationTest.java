package com.huawei.finance.a2a.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.a2a.A2AGateway;
import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardRegistry;
import com.huawei.finance.a2a.InMemoryDelegationStore;
import com.huawei.finance.a2a.client.HttpA2ADispatcher;
import com.huawei.finance.a2a.client.RemoteA2AProperties;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class A2AGatewayHttpIntegrationTest {

    @Test
    void clientCallsGatewayAndGatewayCallsTargetAgentInboundEndpoint() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String targetUrl = "http://agent-account:8080";
        String gatewayUrl = "http://a2a-gateway:8086";

        RestClient.Builder targetBuilder = RestClient.builder();
        MockRestServiceServer targetServer = MockRestServiceServer.bindTo(targetBuilder).build();
        targetServer.expect(once(), requestTo(targetUrl + "/a2a/v2/inbound"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("delegation-http-1")))
                .andRespond(withSuccess(mapper.writeValueAsString(DelegationReceipt.succeeded(
                        "delegation-http-1", Map.of("balance", "100.00"))), MediaType.APPLICATION_JSON));

        HttpRemoteAgentNode remoteNode = new HttpRemoteAgentNode(
                "agent.account", URI.create(targetUrl), targetBuilder);
        AgentCard card = new AgentCard("agent.account", "account", "账户助手", "account",
                List.of("account"), "R0", 5000, "account", "1.0.0",
                AgentCard.Status.ACTIVE, Map.of());
        A2AGateway gateway = new A2AGateway(
                new AgentCardRegistry(List.of(card), List.of(remoteNode)),
                new InMemoryDelegationStore(), new A2AProperties(),
                new SimpleMeterRegistry(), Clock.systemUTC());
        A2AGatewayController controller = new A2AGatewayController(gateway);

        DelegationEnvelope envelope = new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "tenant-1",
                "agent.mobile-banking-assistant", "agent.account",
                "root-1", "parent-1", "source-1", "delegation-http-1", "trace-1",
                DelegationMode.GOAL, "查询余额", null, Map.of(), List.of(),
                Instant.now().plusSeconds(30), List.of("agent.mobile-banking-assistant"));

        RestClient.Builder gatewayBuilder = RestClient.builder();
        MockRestServiceServer gatewayServer = MockRestServiceServer.bindTo(gatewayBuilder).build();
        gatewayServer.expect(once(), requestTo(gatewayUrl + "/a2a/v2/delegations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("agent.account")))
                .andRespond(request -> {
                    byte[] response = mapper.writeValueAsBytes(controller.dispatch(envelope));
                    MockClientHttpResponse http = new MockClientHttpResponse(response,
                            org.springframework.http.HttpStatus.OK);
                    http.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return http;
                });

        RemoteA2AProperties properties = new RemoteA2AProperties();
        properties.setGatewayUrl(URI.create(gatewayUrl));
        DelegationReceipt receipt = new HttpA2ADispatcher(properties, gatewayBuilder).dispatch(envelope);

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsEntry("balance", "100.00");
        gatewayServer.verify();
        targetServer.verify();
    }
}
