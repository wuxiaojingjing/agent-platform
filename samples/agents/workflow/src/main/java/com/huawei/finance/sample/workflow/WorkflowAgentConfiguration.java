package com.huawei.finance.sample.workflow;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 声明式办理流程的装配。
 *
 * <p>只有显式打开 {@code huawei.finance.sample.workflow.enabled=true} 才装配。与 Mock 领域 Agent 同理：
 * 一个能对下游发起真实调用的执行件，不该因为在 classpath 上就自动生效。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "huawei.finance.sample.workflow.enabled", havingValue = "true")
@EnableConfigurationProperties(WorkflowFlowProperties.class)
public class WorkflowAgentConfiguration {

    @Bean
    public FlowSpecLoader flowSpecLoader() {
        return new FlowSpecLoader();
    }

    /**
     * 装配领域 Agent。
     *
     * <p>叶子操作按 {@code List<DomainOperation>} 注入：行内实现放在自己的包里、加上 {@code @Component}
     * 就会被收进来，不需要改本模块任何代码，也不需要在某个清单里登记一遍。
     */
    @Bean
    public WorkflowDomainAgent workflowDomainAgent(FlowSpecLoader loader,
                                                   WorkflowFlowProperties properties,
                                                   List<DomainOperation> operations) {
        List<FlowSpec> specs = loader.load(Path.of(properties.getFlowsDir()));
        Map<String, FlowSpec> byCapability = specs.stream().collect(Collectors.toMap(
                FlowSpec::capabilityId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        return new WorkflowDomainAgent(byCapability, new FlowCompiler(operations));
    }
}
