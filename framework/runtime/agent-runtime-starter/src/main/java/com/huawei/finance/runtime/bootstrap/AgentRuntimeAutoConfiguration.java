package com.huawei.finance.runtime.bootstrap;

import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.contracts.a2a.PrincipalResolver;
import com.huawei.finance.contracts.a2a.ResolvedPrincipal;
import com.huawei.finance.context.ContextLeaseCompiler;
import com.huawei.finance.context.ContextualQueryRewriter;
import com.huawei.finance.context.ContextualQueryModel;
import com.huawei.finance.context.DomainReferenceResolution;
import com.huawei.finance.context.TurnStore;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.ConditionResolver;
import com.huawei.finance.intent.PlanConditionValidator;
import com.huawei.finance.intent.IntentEngineFactory;
import com.huawei.finance.intent.IntentPlanner;
import com.huawei.finance.intent.SlowPathProperties;
import com.huawei.finance.fastpath.FastPathEngine;
import com.huawei.finance.fastpath.FastPathIntentEngine;
import com.huawei.finance.orchestrator.TaskOrchestrator;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.runtime.AgentRuntime;
import com.huawei.finance.runtime.DefaultRuntimeRegistrationCompensator;
import com.huawei.finance.runtime.DefaultAgentRuntime;
import com.huawei.finance.runtime.RuntimeRegistrationCompensator;
import com.huawei.finance.runtime.multi.MultiIntentCoordinator;
import com.huawei.finance.runtime.multi.SlowPathExecutionCoordinator;
import com.huawei.finance.runtime.multi.StaticPlanCoordinator;
import com.huawei.finance.runtime.loop.*;
import com.huawei.finance.runtime.entry.*;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.continuation.RuntimeContinuationPort;
import com.huawei.finance.orchestrator.continuation.RuntimeContinuationRegistry;
import com.huawei.finance.runtime.extension.ResponseEnricher;
import com.huawei.finance.runtime.task.AgentTaskExecutor;
import com.huawei.finance.runtime.task.DefaultAgentTaskExecutor;
import com.huawei.finance.runtime.invocation.AgentInvocationRuntime;
import com.huawei.finance.runtime.invocation.DefaultAgentInvocationRuntime;
import com.huawei.finance.runtime.spi.DecisionRecorder;
import com.huawei.finance.runtime.spi.PostOrchestrationHook;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import com.huawei.finance.runtime.spi.RuntimeEnginesSource;
import com.huawei.finance.runtime.spi.SessionAffinityPort;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.orchestrator.continuation.ContinuationContextAssembler;
import com.huawei.finance.orchestrator.continuation.ContinuationCoordinator;
import com.huawei.finance.orchestrator.continuation.ContinuationModelCache;
import com.huawei.finance.orchestrator.continuation.ContinuationPolicyGate;
import com.huawei.finance.orchestrator.continuation.ContinuationUnderstandingModel;
import com.huawei.finance.orchestrator.continuation.DeterministicContinuationRules;
import com.huawei.finance.orchestrator.context.PlatformTaskContextManager;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.context.SwitchCoordinator;
import com.huawei.finance.runtime.PlatformRuntimeBridge;
import com.huawei.finance.a2a.DelegationClient;
import com.huawei.finance.response.ResponsePlanner;
import com.huawei.finance.response.ResponseRealizer;
import com.huawei.finance.response.ResponseTextModel;
import io.micrometer.tracing.Tracer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.time.Duration;

/**
 * 装配 {@link AgentRuntime} 与多意图协调。引擎快照 / 亲和 / 留痕由应用侧提供 Bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentLoopProperties.class)
public class AgentRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PrincipalResolver.class)
    public PrincipalResolver principalResolver() {
        return (tenantId, targetAgentId, context) -> context == null
                ? ResolvedPrincipal.anonymous("UNKNOWN")
                : new ResolvedPrincipal(context.principalRef(), context.authenticated(),
                        context.authLevel(), context.channel(), java.util.Map.of());
    }

    /**
     * 单实例通用 Host 不需要会话亲和；多实例产品可提供持久化实现覆盖该默认值。
     */
    @Bean
    @ConditionalOnMissingBean(SessionAffinityPort.class)
    public SessionAffinityPort sessionAffinityPort() {
        return SessionAffinityPort.NONE;
    }

    /**
     * 通用 Host 的稳定引擎快照。需要资产热重载的产品应用可覆盖此 SPI，按请求返回新快照。
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeEnginesSource.class)
    public RuntimeEnginesSource runtimeEnginesSource(
            AssetBundle bundle,
            FastPathEngine fastPathEngine,
            IntentEngineFactory intentEngineFactory,
            ResponsePlanner planner,
            ResponseRealizer renderer) {
        var intentEngine = java.util.Objects.requireNonNull(
                intentEngineFactory.create(new FastPathIntentEngine(fastPathEngine)),
                "IntentEngineFactory 返回 null");
        RuntimeEngines snapshot = new RuntimeEngines(bundle, intentEngine, planner, renderer);
        return () -> snapshot;
    }

    @Bean
    @ConditionalOnMissingBean(AgentTaskExecutor.class)
    public AgentTaskExecutor agentTaskExecutor(TaskOrchestrator orchestrator) {
        return new DefaultAgentTaskExecutor(orchestrator);
    }

    @Bean @ConditionalOnBean(ModelGatewayClient.class) @ConditionalOnMissingBean(AgentLoopPlanner.class)
    public AgentLoopPlanner modelAgentLoopPlanner(ModelGatewayClient gateway, ModelGatewayProperties properties,
                                                   AssetBundle assets, ContractValidator validator) {
        return new ModelAgentLoopPlanner(gateway, properties, assets, validator);
    }

    @Bean @ConditionalOnMissingBean({AgentLoopPlanner.class, ModelGatewayClient.class})
    public AgentLoopPlanner agentLoopPlanner(){return new FallbackAgentLoopPlanner();}

    @Bean @ConditionalOnBean(ModelGatewayClient.class) @ConditionalOnMissingBean(ResponseTextModel.class)
    public ResponseTextModel responseTextModel(ModelGatewayClient gateway, ModelGatewayProperties properties) {
        return new GatewayResponseTextModel(gateway, properties);
    }

    @Bean @ConditionalOnMissingBean
    public ContinuationModelCache continuationModelCache(ModelGatewayProperties properties) {
        var config = properties.getContinuation();
        return new InMemoryContinuationModelCache(Duration.ofSeconds(
                Math.max(0, config.getCacheTtlSeconds())), config.getCacheMaxEntries());
    }

    @Bean @ConditionalOnBean(ModelGatewayClient.class) @ConditionalOnMissingBean(ContinuationUnderstandingModel.class)
    public ContinuationUnderstandingModel continuationUnderstandingModel(ModelGatewayClient gateway,
            ModelGatewayProperties properties, AssetBundle assets, ContractValidator validator,
            ContinuationModelCache cache, MeterRegistry meters) {
        return new ModelContinuationUnderstanding(gateway, properties, assets, validator, cache, meters);
    }

    @Bean
    @ConditionalOnBean(ModelGatewayClient.class)
    @ConditionalOnMissingBean(ContextualQueryModel.class)
    public ContextualQueryModel contextualQueryModel(
            ModelGatewayClient gateway, ModelGatewayProperties properties,
            AssetBundle assets, ContractValidator validator, MeterRegistry meters) {
        return new ModelContextualQueryRewriter(gateway, properties, assets, validator, meters);
    }

    @Bean @ConditionalOnMissingBean({ContinuationUnderstandingModel.class, ModelGatewayClient.class})
    public ContinuationUnderstandingModel unavailableContinuationUnderstandingModel() {
        return ContinuationUnderstandingModel.UNAVAILABLE;
    }

    @Bean @ConditionalOnBean(ContinuationContextAssembler.class) @ConditionalOnMissingBean
    public ContinuationCoordinator continuationCoordinator(ContinuationContextAssembler contexts,
            ContinuationUnderstandingModel model, MeterRegistry meters,
            RuntimeEnginesSource engines) {
        return new ContinuationCoordinator(contexts, new DeterministicContinuationRules(), model,
                new ContinuationPolicyGate(.85, .95), meters,
                new ClarifySlotValueNormalizer(() -> engines.current().bundle().clarify()));
    }

    @Bean @ConditionalOnMissingBean
    public LoopCandidateRetriever loopCandidateRetriever(){return new LoopCandidateRetriever();}

    @Bean @ConditionalOnMissingBean
    public LoopActionValidator loopActionValidator(ContractValidator validator){return new LoopActionValidator(validator);}

    @Bean @ConditionalOnMissingBean
    public LoopActionPolicyGate loopActionPolicyGate(){return new LoopActionPolicyGate();}

    @Bean @ConditionalOnMissingBean
    public LoopObservationNormalizer loopObservationNormalizer(){return new LoopObservationNormalizer();}

    @Bean @ConditionalOnMissingBean
    public LoopActionExecutorRouter loopActionExecutorRouter(AgentTaskExecutor tasks,
            ObjectProvider<LoopGoalDelegator> goals,AgentLoopProperties props,
            LoopObservationNormalizer observations){
        return new LoopActionExecutorRouter(tasks, goals.getIfAvailable(() -> LoopGoalDelegator.UNAVAILABLE),
                props.getMaxDelegationDepth(), observations);
    }

    @Bean @ConditionalOnBean(DelegationClient.class) @ConditionalOnMissingBean(LoopGoalDelegator.class)
    public LoopGoalDelegator loopGoalDelegator(DelegationClient client) {
        return new A2ALoopGoalDelegator(client);
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean(AgentLoopRepository.class)
    public AgentLoopCoordinator agentLoopCoordinator(AgentLoopRepository repository,LoopCandidateRetriever candidates,
            AgentLoopPlanner planner,LoopActionValidator validator,LoopActionPolicyGate policy,
            LoopActionExecutorRouter executors,AgentLoopProperties props,MeterRegistry meters){
        return new AgentLoopCoordinator(repository,candidates,planner,validator,policy,executors,
                props.getMaxCandidates(),props.getMaxModelCalls(),props.getMaxRepeatAction(),
                props.getClaimRecoverySeconds(),meters);
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean(AgentLoopCoordinator.class)
    public AgentLoopStarter agentLoopStarter(AgentLoopRepository repository,AgentLoopCoordinator coordinator,
                                              AgentLoopProperties props){
        return new AgentLoopStarter(repository,coordinator,props.getMaxIterations(),props.getDeadlineSeconds());
    }

    @Bean @ConditionalOnMissingBean(name="loopContinuationPort")
    @ConditionalOnBean(AgentLoopRepository.class)
    public RuntimeContinuationPort loopContinuationPort(AgentLoopRepository repository){
        return new LoopContinuationPort(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public MultiIntentCoordinator multiIntentCoordinator(
            IntentPlanRepository plans,
            IntentPlanner planner,
            ConditionEvaluator conditions,
            SlowPathProperties props,
            SessionAffinityPort affinity,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracers) {
        return new MultiIntentCoordinator(plans, planner, conditions, props, affinity,
                meters.getIfAvailable(), tracers.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public SlowPathExecutionCoordinator slowPathExecutionCoordinator(
            IntentPlanRepository plans, AgentTaskExecutor tasks,
            ConditionEvaluator conditions, ConditionResolver conditionResolver, SlowPathProperties props,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracers,
            MeterRegistry meters) {
        return new SlowPathExecutionCoordinator(plans, tasks, conditions, props,
                tracers.getIfAvailable(), meters, java.util.concurrent.ForkJoinPool.commonPool(),
                conditionResolver, new PlanConditionValidator());
    }

    @Bean
    @ConditionalOnMissingBean
    public StaticPlanCoordinator staticPlanCoordinator(SlowPathExecutionCoordinator delegate) {
        return new StaticPlanCoordinator(delegate);
    }

    @Bean @ConditionalOnMissingBean
    public EntryRouteCoordinator entryRouteCoordinator(AgentLoopProperties loop, MeterRegistry meters,
            Optional<ContinuationCoordinator> continuation){
        return new EntryRouteCoordinator(new IntentEvidenceBuilder(),new DeterministicEntryRules(),
                new LoopEntryPolicyGate(loop.isEnabled()),meters,continuation);
    }

    @Bean @ConditionalOnBean({TaskContextStore.class, PlatformTaskContextManager.class}) @ConditionalOnMissingBean
    public PlatformRuntimeBridge platformRuntimeBridge(TaskContextStore store,
            PlatformTaskContextManager manager, TaskRepository tasks,
            RuntimeRegistrationCompensator compensator,
            Optional<RuntimeContinuationRegistry> continuations) {
        return new PlatformRuntimeBridge(store, manager, tasks, compensator, continuations);
    }

    @Bean @ConditionalOnMissingBean
    public RuntimeRegistrationCompensator runtimeRegistrationCompensator(
            TaskRepository tasks, IntentPlanRepository plans,
            Optional<com.huawei.finance.orchestrator.loop.AgentLoopRepository> loops) {
        return new DefaultRuntimeRegistrationCompensator(tasks, plans, loops);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
    @ConditionalOnBean(RuntimeEnginesSource.class)
    public AgentRuntime agentRuntime(
            RuntimeEnginesSource engines,
            TaskOrchestrator orchestrator,
            TaskRepository taskRepository,
            Tracer tracer,
            ContextLeaseCompiler leaseCompiler,
            ContextualQueryRewriter contextualQueries,
            TurnStore turnStore,
            IntentPlanRepository plans,
            MultiIntentCoordinator multiIntent,
            StaticPlanCoordinator staticPlan,
            EntryRouteCoordinator entryRoutes,
            Optional<PlatformRuntimeBridge> platform,
            Optional<AgentLoopStarter> loopStarter,
            Optional<SwitchCoordinator> switches,
            DomainReferenceResolution workingMemory,
            AgentIdentity agentIdentity,
            Optional<PostOrchestrationHook> postOrchestration,
            Optional<DecisionRecorder> decisionRecorder,
            ObjectProvider<ResponseEnricher> responseEnrichers) {
        return new DefaultAgentRuntime(
                engines, orchestrator, taskRepository, tracer, leaseCompiler, contextualQueries, turnStore, plans,
                multiIntent, staticPlan, entryRoutes, platform, loopStarter, switches,
                workingMemory, agentIdentity, postOrchestration,
                decisionRecorder.orElse(DecisionRecorder.NOOP),
                responseEnrichers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(AgentInvocationRuntime.class)
    @ConditionalOnBean(AgentRuntime.class)
    public AgentInvocationRuntime agentInvocationRuntime(
            AgentRuntime runtime, AgentTaskExecutor tasks, RuntimeEnginesSource engines,
            ContextLeaseCompiler leases, TaskRepository repository, AgentIdentity identity,
            Tracer tracer) {
        return new DefaultAgentInvocationRuntime(runtime, tasks, engines, leases, repository,
                identity, tracer);
    }
}
