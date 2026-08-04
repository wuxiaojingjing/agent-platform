package com.huawei.finance.host;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 通用 Host 从 AGENT_HOME/agent.yaml 读取身份；业务代码通过 backend 扩展 jar 接入。 */
@SpringBootApplication
public class AgentHostApplication {

    public static void main(String[] args) throws IOException {
        Path home = AgentAssetLocations.requireAgentHome();
        AgentDefinition definition = AgentDefinition.load(home);
        // application.yml has higher priority than SpringApplication default properties.
        // The process identity is defined by agent.yaml, so force it before logging/tracing initialize.
        System.setProperty("spring.application.name", definition.id());
        SpringApplication application = new SpringApplication(AgentHostApplication.class);
        Map<String, Object> defaults = new java.util.LinkedHashMap<>();
        defaults.put("spring.application.name", definition.id());
        defaults.put("huawei.finance.agent.id", definition.id());
        defaults.put("huawei.finance.agent.domains", String.join(",", definition.domains()));
        defaults.put("huawei.finance.agent.implementation-mode", definition.mode());
        defaults.put("huawei.finance.agent.implementation-artifact", definition.artifact());
        defaults.put("huawei.finance.agent.assets.path", home.resolve("assets").toString());
        defaults.put("huawei.finance.agent.a2a.delegation.enabled",
                !definition.mode().equals("scaffold"));
        defaults.put("management.metrics.tags.agentId", definition.id());
        defaults.put("management.metrics.tags.implementationMode", definition.mode());
        defaults.put("management.metrics.tags.role", "domain");
        if (definition.mode().equals("scaffold")) {
            defaults.put("spring.autoconfigure.exclude", String.join(",",
                    "com.huawei.finance.intent.bootstrap.IntentEngineAutoConfiguration",
                    "com.huawei.finance.runtime.bootstrap.AgentRuntimeAutoConfiguration",
                    "com.huawei.finance.fastpath.FastPathConfiguration",
                    "com.huawei.finance.slowpath.SlowPathConfiguration",
                    "com.huawei.finance.orchestrator.OrchestratorConfiguration",
                    "com.huawei.finance.context.ContextConfiguration",
                    "com.huawei.finance.response.ResponseConfiguration",
                    "com.huawei.finance.registry.asset.AssetRegistryAutoConfiguration",
                    "com.huawei.finance.persistence.JdbcPersistenceAutoConfiguration",
                    "com.huawei.finance.cache.redis.RedissonClientAutoConfiguration",
                    "com.huawei.finance.cache.redis.RedisSessionLockAutoConfiguration",
                    "com.huawei.finance.cache.redis.RedisDecisionCacheConfiguration",
                    "com.huawei.finance.a2a.client.A2AClientAutoConfiguration",
                    "com.huawei.finance.gateway.ModelGatewayConfiguration",
                    "com.huawei.finance.registry.SearchOpenSearchAutoConfiguration",
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"));
        }
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
