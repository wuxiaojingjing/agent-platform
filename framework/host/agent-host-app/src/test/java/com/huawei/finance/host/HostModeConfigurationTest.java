package com.huawei.finance.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.ResolvedPrincipal;
import com.huawei.finance.contracts.a2a.PrincipalResolver;
import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import com.huawei.finance.runtime.invocation.AgentInvocationOutcome;
import com.huawei.finance.runtime.invocation.AgentInvocationRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

class HostModeConfigurationTest {

    private final HostModeConfiguration configuration = new HostModeConfiguration();

    @Test
    void providesDefaultRestClientBuilder() {
        new ApplicationContextRunner()
                .withUserConfiguration(HostModeConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(RestClient.Builder.class));
    }

    @Test
    void customRestClientBuilderOverridesHostDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomRestClientConfiguration.class, HostModeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClient.Builder.class);
                    assertThat(context.getBean(RestClient.Builder.class))
                            .isSameAs(context.getBean("customRestClientBuilder"));
                });
    }

    @Test
    void extensionRegistersTheMatchingRealDomainNode() {
        TechDomainAgent account = agent("agent.account", "account", "cap.account.balance.query");

        var node = configuration.extensionAgentNode(identity("agent.account"),
                provider(account), successfulRuntime(), principalResolver());
        var receipt = node.handle(task("agent.account", "cap.account.balance.query"));

        assertThat(node.agentId()).isEqualTo("agent.account");
        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(receipt.facts()).containsEntry("source", "real-extension");
    }

    @Test
    void extensionWithoutImplementationFailsFast() {
        assertThatThrownBy(() -> configuration.extensionAgentNode(identity("agent.account"),
                provider(), successfulRuntime(), principalResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未提供 TechDomainAgent");
    }

    @Test
    void extensionWithDifferentIdentityFailsFast() {
        assertThatThrownBy(() -> configuration.extensionAgentNode(identity("agent.transfer"),
                provider(agent("agent.account", "account", "cap.account.balance.query")),
                successfulRuntime(), principalResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity=agent.transfer");
    }

    @Test
    void scaffoldReturnsExplicitDomainNotOpen() {
        var node = configuration.scaffoldAgentNode(identity("agent.loan_service"),
                List.of("loan_service"));

        var receipt = node.handle(task("agent.loan_service", "cap.loan_service.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
        assertThat(receipt.facts()).isEmpty();
        assertThat(receipt.reasonCode()).isEqualTo("DOMAIN_NOT_OPEN");
    }

    private static AgentIdentity identity(String id) {
        return new AgentIdentity(id);
    }

    private static PrincipalResolver principalResolver() {
        return (tenant, target, principal) -> principal == null
                ? ResolvedPrincipal.anonymous("TEST")
                : new ResolvedPrincipal(principal.principalRef(), principal.authenticated(),
                        principal.authLevel(), principal.channel(), Map.of());
    }

    private static AgentInvocationRuntime successfulRuntime() {
        return request -> {
            TaskResult result = new TaskResult("target-task", Enums.TaskStatus.SUCCESS,
                    Enums.FailureClass.NONE, Map.of("source", "real-extension"),
                    request.sourceInvocationId(), null);
            return new AgentInvocationOutcome("target-task", "SUCCESS", result,
                    result.resultPayload(), List.of(), null);
        };
    }

    private static TechDomainAgent agent(String id, String domain, String capability) {
        return new TechDomainAgent() {
            @Override public String agentId() { return id; }
            @Override public String techDomainCode() { return domain; }
            @Override public boolean supports(String capabilityId) { return capability.equals(capabilityId); }
            @Override public Set<String> advertisedCapabilities() { return Set.of(capability); }
            @Override public TaskResult execute(UnifiedTask task) {
                return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                        Enums.FailureClass.NONE, Map.of("source", "real-extension"),
                        task.idempotencyKey(), null);
            }
        };
    }

    private static DelegationEnvelope task(String target, String capability) {
        return new DelegationEnvelope(null, "tenant", "agent.mobile-banking-assistant", target,
                "root", "parent", "source", "delegation", "trace", DelegationMode.TASK,
                "query", capability, Map.of(), List.of(), Instant.now().plusSeconds(30),
                List.of("agent.mobile-banking-assistant"));
    }

    @SafeVarargs
    private static org.springframework.beans.factory.ObjectProvider<TechDomainAgent> provider(
            TechDomainAgent... agents) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < agents.length; i++) {
            factory.registerSingleton("agent" + i, agents[i]);
        }
        return factory.getBeanProvider(TechDomainAgent.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRestClientConfiguration {
        @Bean
        RestClient.Builder customRestClientBuilder() {
            return RestClient.builder();
        }
    }
}
