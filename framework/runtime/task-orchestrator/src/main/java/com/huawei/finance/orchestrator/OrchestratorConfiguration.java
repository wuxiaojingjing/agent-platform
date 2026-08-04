package com.huawei.finance.orchestrator;

import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.port.GuardrailHook;
import com.huawei.finance.orchestrator.guardrail.GuardrailProperties;
import com.huawei.finance.orchestrator.guardrail.PolicyGuardrail;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.context.PlatformTaskContextManager;
import com.huawei.finance.orchestrator.context.SwitchCoordinator;
import com.huawei.finance.orchestrator.context.PendingGoalCoordinator;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.continuation.RuntimeContinuationPort;
import com.huawei.finance.orchestrator.continuation.RuntimeContinuationRegistry;
import com.huawei.finance.orchestrator.continuation.TaskContinuationPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.huawei.finance.contracts.port.SessionLockManager;
import com.huawei.finance.contracts.port.ExecutionParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 任务编排装配。
 *
 * <p>{@code @AutoConfiguration} 而非 {@code @Configuration}：自动配置在使用方的配置**之后**
 * 才评估，{@link ConditionalOnMissingBean} 因此能真的让位给行内实现。写成普通
 * {@code @Configuration} 被组件扫描扫到时，先后取决于扫描顺序，覆盖就成了碰运气——
 * 而失效是静默的，行内的实现被忽略、系统照基线默认跑，没有任何报错。
 */
@AutoConfiguration
@EnableConfigurationProperties({OrchestratorProperties.class, GuardrailProperties.class})
public class OrchestratorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorConfiguration.class);

    /**
     * 护栏。**这是给使用方覆盖的头号扩展点。**
     *
     * <p>{@link PolicyGuardrail} 是基线的通用实现，但护栏本身是各行合规口径的落点：
     * 单笔限额、黑名单、可疑交易拦截、双人复核，这些规则各行不同且会独立演进。
     * 行内声明自己的 {@code GuardrailHook} Bean 即可整体接管。
     *
     * <p>要接管就得整体接管，没有「叠加一层」的形态：护栏产出的是「过 / 不过」这个单一判断，
     * 幂等键在它通过之后才生成。允许多个护栏叠加，就得回答冲突时听谁的，
     * 而那个答案只有行内自己知道——写在基线里必然是错的。
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardrailHook guardrailHook(GuardrailProperties props) {
        return new PolicyGuardrail(props);
    }

    /**
     * 领域调用线程池。
     *
     * <p>必须有界。无界池在下游整体变慢时会一路建线程直到把进程拖垮，而那恰恰是最需要
     * 超时机制生效的时刻——池子把自己耗尽了，超时判定也就没人执行了。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "domainAgentExecutor")
    public ExecutorService domainAgentExecutor(OrchestratorProperties props) {
        return Executors.newFixedThreadPool(props.getAgentPoolSize(), runnable -> {
            Thread t = new Thread(runnable, "agent-platform-agent");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param agents 用 {@code ObjectProvider} 而不是 {@code List<DomainAgent>}：后者在一个
     *               Agent 都没有时是注入失败，而不是注入空列表，报错信息也说不清缺的是什么。
     *               行内刚把平台立起来、还没实现任何领域 Agent 时，应当能启动并跑通澄清与拒绝
     *               这些不需要 Agent 的出口；真到了要执行时 {@link AgentInvoker} 会判
     *               {@code NO_AGENT_FOR_CAPABILITY}。但零 Agent 静默上线是另一种危险，
     *               所以启动时明确警告一次。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentInvoker agentInvoker(ObjectProvider<DomainAgent> agents,
                                     ObjectProvider<CapabilityDelegator> delegator,
                                     ExecutorService domainAgentExecutor,
                                     OrchestratorProperties props, MeterRegistry meterRegistry) {
        List<DomainAgent> resolved = agents.orderedStream().toList();
        if (resolved.isEmpty()) {
            log.warn("没有任何领域 Agent 注册。知识、导航与澄清照常工作，但所有 EXECUTE_CAPABILITY "
                    + "都会判 NO_AGENT_FOR_CAPABILITY。若这是生产环境，说明领域 Agent 漏装了");
        }
        return new AgentInvoker(resolved, domainAgentExecutor, props, meterRegistry,
                delegator.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskOrchestrator taskOrchestrator(TaskRepository repository, GuardrailHook guardrail,
                                             AgentInvoker invoker, SessionLockManager locks,
                                             ContractValidator validator, MeterRegistry meterRegistry,
                                             java.util.Optional<TaskContextStore> taskContexts,
                                             ObjectProvider<ExecutionParameterResolver> parameterResolvers) {
        return new TaskOrchestrator(repository, guardrail, invoker, locks, validator, meterRegistry,
                taskContexts, parameterResolvers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnBean(TaskContextStore.class)
    @ConditionalOnMissingBean
    public PlatformTaskContextManager platformTaskContextManager(TaskContextStore store, MeterRegistry meters) {
        return new PlatformTaskContextManager(store, meters);
    }

    @Bean @ConditionalOnBean(TaskContextStore.class) @ConditionalOnMissingBean
    public SwitchCoordinator switchCoordinator(TaskContextStore store, PlatformTaskContextManager tasks,
                                                MeterRegistry meters) {
        return new SwitchCoordinator(store, tasks, meters);
    }

    @Bean @ConditionalOnBean(TaskContextStore.class) @ConditionalOnMissingBean
    public PendingGoalCoordinator pendingGoalCoordinator(TaskContextStore store, MeterRegistry meters) {
        return new PendingGoalCoordinator(store, meters);
    }

    @Bean @ConditionalOnMissingBean
    public TaskContinuationPort taskContinuationPort(TaskRepository tasks) {
        return new TaskContinuationPort(tasks);
    }

    @Bean @ConditionalOnMissingBean
    public com.huawei.finance.orchestrator.continuation.StaticPlanContinuationPort staticPlanContinuationPort(
            IntentPlanRepository plans) {
        return new com.huawei.finance.orchestrator.continuation.StaticPlanContinuationPort(plans);
    }

    @Bean @ConditionalOnMissingBean
    public com.huawei.finance.orchestrator.continuation.WorkflowContinuationPort workflowContinuationPort(
            TaskRepository tasks) {
        return new com.huawei.finance.orchestrator.continuation.WorkflowContinuationPort(tasks);
    }

    @Bean @ConditionalOnMissingBean
    public RuntimeContinuationRegistry runtimeContinuationRegistry(List<RuntimeContinuationPort> ports) {
        return new RuntimeContinuationRegistry(ports);
    }

    @Bean @ConditionalOnBean(TaskContextStore.class) @ConditionalOnMissingBean
    public com.huawei.finance.orchestrator.continuation.ContinuationContextAssembler continuationContextAssembler(
            TaskContextStore store, RuntimeContinuationRegistry registry) {
        return new com.huawei.finance.orchestrator.continuation.ContinuationContextAssembler(store, registry);
    }
}
