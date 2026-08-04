package com.huawei.finance.runtime;

import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.common.event.ActiveTaskView;
import com.huawei.finance.common.event.InputEvent;
import com.huawei.finance.context.ContextLeaseCompiler;
import com.huawei.finance.context.ContextCompilation;
import com.huawei.finance.context.ContextualQueryRewriter;
import com.huawei.finance.context.ConversationTurn;
import com.huawei.finance.context.DomainReferenceResolution;
import com.huawei.finance.context.TurnStore;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.model.ResponseAction;
import com.huawei.finance.contracts.model.ResponseComponent;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.TaskShape;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.IntentRequest;
import com.huawei.finance.intent.IntentResult;
import com.huawei.finance.intent.PathSummary;
import com.huawei.finance.orchestrator.OrchestrationOutcome;
import com.huawei.finance.orchestrator.OrchestrationRequest;
import com.huawei.finance.orchestrator.TaskOrchestrator;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.ClarifyConfig;
import com.huawei.finance.response.RenderedResponse;
import com.huawei.finance.response.ResponseModelContext;
import com.huawei.finance.response.ResponseContext;
import com.huawei.finance.runtime.multi.MultiIntentCoordinator;
import com.huawei.finance.runtime.multi.StaticPlanCoordinator;
import com.huawei.finance.runtime.entry.EntryRouteCoordinator;
import com.huawei.finance.runtime.context.TurnContextAssembler;
import com.huawei.finance.runtime.context.TurnContextSnapshot;
import com.huawei.finance.runtime.entry.RouteDispatcher;
import com.huawei.finance.runtime.loop.AgentLoopStarter;
import com.huawei.finance.runtime.loop.LoopResponseBridge;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.context.SwitchCoordinator;
import com.huawei.finance.runtime.extension.ResponseEnricher;
import com.huawei.finance.runtime.extension.ResponseEnrichmentContext;
import com.huawei.finance.runtime.spi.DecisionRecorder;
import com.huawei.finance.runtime.spi.PostOrchestrationHook;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import com.huawei.finance.runtime.spi.RuntimeEnginesSource;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认 Agent 运行时流水线：上下文 → 多意图 → 意图 → 委托/中控 → 回复。
 *
 * <p>自入口 ChatService 抽出（v0.5 §17.3）。产品扩展经 {@link PostOrchestrationHook} /
 * {@link DecisionRecorder} 注入，不进本类硬编码。
 */
public class DefaultAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);

    private final RuntimeEnginesSource engines;
    private final TaskOrchestrator orchestrator;
    private final TaskRepository taskRepository;
    private final Tracer tracer;
    private final ContextLeaseCompiler leaseCompiler;
    private final TurnContextAssembler turnContexts;
    private final ContextualQueryRewriter contextualQueries;
    private final TurnStore turnStore;
    private final IntentPlanRepository plans;
    private final MultiIntentCoordinator multiIntent;
    private final StaticPlanCoordinator staticPlan;
    private final EntryRouteCoordinator entryRoutes;
    private final Optional<PlatformRuntimeBridge> platform;
    private final RouteDispatcher routeDispatcher;
    private final Optional<AgentLoopStarter> loopStarter;
    private final LoopResponseBridge loopResponses = new LoopResponseBridge();
    private final Optional<SwitchCoordinator> switches;
    private final SwitchResponseBridge switchResponses = new SwitchResponseBridge();
    private final ResumeResponseBridge resumeResponses = new ResumeResponseBridge();
    private final DomainReferenceResolution workingMemory;
    private final AgentIdentity agentIdentity;
    private final Optional<PostOrchestrationHook> postOrchestration;
    private final DecisionRecorder decisionRecorder;
    private final ResponseEnricherChain responseEnrichers;

    public DefaultAgentRuntime(
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
            DecisionRecorder decisionRecorder,
            List<ResponseEnricher> responseEnrichers) {
        this.engines = engines;
        this.orchestrator = orchestrator;
        this.taskRepository = taskRepository;
        this.tracer = tracer;
        this.leaseCompiler = leaseCompiler;
        this.turnContexts = new TurnContextAssembler(leaseCompiler);
        this.contextualQueries = contextualQueries;
        this.turnStore = turnStore;
        this.plans = plans;
        this.multiIntent = multiIntent;
        this.staticPlan = staticPlan;
        this.entryRoutes = entryRoutes;
        this.platform = platform == null ? Optional.empty() : platform;
        this.routeDispatcher = new RouteDispatcher(this.platform);
        this.loopStarter = loopStarter == null ? Optional.empty() : loopStarter;
        this.switches = switches == null ? Optional.empty() : switches;
        this.workingMemory = workingMemory;
        this.agentIdentity = agentIdentity;
        this.postOrchestration = postOrchestration == null ? Optional.empty() : postOrchestration;
        this.decisionRecorder = decisionRecorder == null ? DecisionRecorder.NOOP : decisionRecorder;
        this.responseEnrichers = new ResponseEnricherChain(responseEnrichers);
    }

    /** 供测试与观测：优先取当前 span 的 traceId。 */
    public static String traceIdOf(Tracer tracer) {
        Span span = tracer == null ? null : tracer.currentSpan();
        return span == null ? "trace-" + UUID.randomUUID() : span.context().traceId();
    }

    @Override
    public AgentResponse handle(AgentRequest request) {
        String agentId = agentIdentity.id();
        Optional<TaskRecord> active = platform.flatMap(p -> p.foregroundTask(request.spaceId(), agentId, request.sessionId()));
        if (active.isEmpty() && platform.isEmpty()) {
            active = taskRepository.findActiveBySession(agentId, request.sessionId());
        }
        boolean clarifyRetry = active.map(t -> t.state() == TaskState.CLARIFY_PENDING).orElse(false);

        RequestContext ctx = new RequestContext(
                traceIdOf(tracer), request.sessionId(), request.userId(), request.spaceId(),
                agentId, request.channel(), request.page(), request.userState(), clarifyRetry,
                principalOf(request), request.lineage());
        RequestContextHolder.set(ctx);

        try {
            RuntimeEngines engine = engines.current();
            Optional<ContinuationContracts.Context> continuationContext =
                    entryRoutes.continuationContext(ctx.spaceId(), agentId, request.sessionId());
            long startedAt = System.nanoTime();
            long contextStarted = System.nanoTime();
            Span contextLoadSpan = startSpan("agent.context.load");
            ContextCompilation contextCompilation;
            try (Tracer.SpanInScope ignored = scope(contextLoadSpan)) {
                TurnContextSnapshot turnContext = turnContexts.assemble(ctx, request.query(), active,
                        request.subtaskContext(), continuationContext);
                contextCompilation = turnContext.compilation();
                if (contextLoadSpan != null) {
                    contextLoadSpan.tag("agent.context.state_version",
                            String.valueOf(contextCompilation.lease().stateVersion()));
                    contextLoadSpan.tag("agent.context.trustworthy",
                            String.valueOf(contextCompilation.lease().trustworthy()));
                }
            } catch (RuntimeException ex) {
                error(contextLoadSpan, ex);
                throw ex;
            } finally {
                end(contextLoadSpan);
            }
            IntentContext intentContext = contextCompilation.intentContext();
            ContextLease lease = contextCompilation.lease();
            Span contextCompileSpan = startSpan("agent.context.compile");
            if (contextCompileSpan != null) {
                contextCompileSpan.tag("agent.context.state_version", String.valueOf(lease.stateVersion()));
                contextCompileSpan.tag("agent.context.evidence_count",
                        String.valueOf(contextCompilation.intentContext().evidence().size()));
                contextCompileSpan.tag("agent.context.trimmed_count", String.valueOf(lease.trimmed().size()));
                contextCompileSpan.end();
            }
            Span contextRewriteSpan = startSpan("agent.context.rewrite");
            ContextualQuery contextualQuery;
            try (Tracer.SpanInScope ignored = scope(contextRewriteSpan)) {
                contextualQuery = contextualQueries.rewrite(request.query(), contextCompilation.intentContext());
                if (contextRewriteSpan != null) {
                    contextRewriteSpan.tag("agent.context.state_version",
                            String.valueOf(contextualQuery.stateVersion()));
                    contextRewriteSpan.tag("agent.context.used_ref_count",
                            String.valueOf(contextualQuery.usedContextRefs().size()));
                    contextRewriteSpan.tag("agent.context.event", contextualQuery.eventType().name());
                    contextRewriteSpan.tag("agent.context.outcome",
                            contextualQuery.rewriteOutcome().name());
                }
            } catch (RuntimeException ex) {
                error(contextRewriteSpan, ex);
                throw ex;
            } finally {
                end(contextRewriteSpan);
            }
            record(ctx, "context-engine", "contextual-rewrite", "CONTEXT",
                    withContext(Map.of(
                            "inputItemCount", intentContext.evidence().size()),
                            intentContext, null),
                    Map.ofEntries(
                            Map.entry("standaloneQuery", contextualQuery.standaloneQuery()),
                            Map.entry("eventType", contextualQuery.eventType().name()),
                            Map.entry("usedContextRefs", contextualQuery.usedContextRefs()),
                            Map.entry("unusedContextRefs", contextualQuery.unusedContextRefs()),
                            Map.entry("slotUpdates", contextualQuery.slotUpdates()),
                            Map.entry("resolutions", contextualQuery.resolutions()),
                            Map.entry("resolutionCount", contextualQuery.resolutions().size()),
                            Map.entry("reasonCode", contextualQuery.reasonCode()),
                            Map.entry("confidence", contextualQuery.confidence()),
                            Map.entry("modelVersion", contextualQuery.modelVersion()),
                            Map.entry("promptVersion", contextualQuery.promptVersion())),
                    contextualQuery.rewriteOutcome().name(),
                    contextStarted);
            record(ctx, "context-engine", "compile-lease", "CONTEXT",
                    Map.of("ownerAgent", agentId, "activeTask", active.isPresent(),
                            "tenantScoped", true),
                    Map.of("trustworthy", lease.trustworthy(),
                            "stateVersion", lease.stateVersion(),
                            "budgetTokens", lease.budgetTokens(),
                            "usedTokens", lease.usedTokens(),
                            "pendingItems", lease.pendingItems().size(),
                            "memoryConclusions", lease.toolConclusions().size(),
                            "availableContextRefs", contextCompilation.intentContext().evidenceRefs(),
                            "trimmedItems", lease.trimmed().size()),
                    lease.trustworthy() ? "OK" : "DEGRADED", contextStarted);

            if (contextualQuery.rewriteOutcome()
                    == ContextualQuery.RewriteOutcome.UNRESOLVED_REFERENCE) {
                return unresolvedContextReference(
                        engine, ctx, request, contextualQuery, startedAt);
            }

            boolean pendingGoalRouting = request.attributes().get("pendingGoalId") != null;
            Optional<ContinuationContracts.Decision> continuationDecision = pendingGoalRouting
                    ? Optional.empty()
                    : entryRoutes.routeContinuation(
                            ctx.spaceId(), agentId, request.sessionId(), request.query(),
                            request.action() == null ? null : new ContinuationContracts.StructuredAction(
                                    request.action().event(), request.action().ref(), request.action().version()),
                            continuationContext, intentContext);
            if (continuationDecision.isPresent() && continuationDecision.get().pendingSwitch() != null) {
                AgentResponse switchResponse = handlePendingSwitch(engine, ctx, request, continuationDecision.get());
                if (switchResponse != null) {
                    recordEarlyResponse(ctx, request, switchResponse, startedAt,
                            continuationDecision.get().resolution().event()
                                    != ContinuationContracts.Event.SWITCH_ACCEPT);
                    return switchResponse;
                }
            }
            if (request.action() == null && continuationDecision.isPresent()
                    && continuationDecision.get().resolution().event() == ContinuationContracts.Event.UNRESOLVED
                    && continuationDecision.get().snapshot() == null
                    && continuationDecision.get().suspended().size() > 1
                    && "AMBIGUOUS_RESUME".equals(
                            continuationDecision.get().resolution().reasonCode())) {
                AgentResponse response = chooseSuspendedTask(engine, ctx,
                        continuationDecision.get().suspended());
                recordEarlyResponse(ctx, request, response, startedAt, true);
                return response;
            }
            if (request.action() != null && continuationDecision.isPresent()
                    && continuationDecision.get().resolution().event() == ContinuationContracts.Event.UNRESOLVED) {
                AgentResponse response = invalidStructuredAction(engine, ctx, continuationDecision.get());
                recordEarlyResponse(ctx, request, response, startedAt, true);
                return response;
            }
            if (request.action() == null && continuationDecision.isPresent()
                    && continuationDecision.get().resolution().event() == ContinuationContracts.Event.UNRESOLVED
                    && continuationDecision.get().snapshot() != null) {
                AgentResponse response = resumeResponses.restored(
                        engine, ctx, continuationDecision.get().snapshot());
                recordEarlyResponse(ctx, request, response, startedAt, true);
                return response;
            }
            if (continuationDecision.isPresent()
                    && continuationDecision.get().resolution().event() == ContinuationContracts.Event.SWITCH_TO_NEW_GOAL
                    && switches.isPresent()) {
                String span = continuationDecision.get().resolution().newGoalSpan();
                int start = span == null ? 0 : Math.max(0, request.query().indexOf(span));
                int end = span == null ? request.query().length() : start + span.length();
                var pending = switches.get().propose(ctx.spaceId(), agentId, request.sessionId(),
                        ctx.traceId(), span == null ? request.query() : span, start, end);
                AgentResponse response = switchResponses.review(engine, ctx, pending);
                recordEarlyResponse(ctx, request, response, startedAt, true);
                return response;
            }
            if (continuationDecision.isPresent()
                    && continuationDecision.get().resolution().event() == ContinuationContracts.Event.RESUME_SUSPENDED
                    && platform.isPresent()) {
                var resumed = platform.get().resumeByRuntimeRef(ctx, continuationDecision.get().resolution().targetRef());
                if (resumed.isPresent()) {
                    AgentResponse response = resumeResponses.restored(
                            engine, ctx, continuationDecision.get().snapshot());
                    recordEarlyResponse(ctx, request, response, startedAt, true);
                    return response;
                }
            }
            if (continuationDecision.isPresent()
                    && continuationDecision.get().resolution().event() != ContinuationContracts.Event.UNRESOLVED
                    && continuationDecision.get().snapshot() != null
                    && continuationDecision.get().snapshot().runtimeType() == RuntimeType.AGENT_LOOP) {
                AgentResponse resumed = resumeLoop(
                        engine, ctx, request, lease, intentContext, continuationDecision.get(), startedAt);
                if (resumed != null) return resumed;
            }

            Optional<PlanRecord> resumedStaticPlan = continuationDecision
                    .filter(DefaultAgentRuntime::isStaticPlanContinuation)
                    .flatMap(resolution -> plans.findById(agentId, resolution.snapshot().runtimeRef()));

            MultiIntentCoordinator.Continuation continuation;
            if (resumedStaticPlan.isPresent()) {
                PlanRecord record = resumedStaticPlan.get();
                continuation = new MultiIntentCoordinator.Continuation(
                        request.query(), record, record.next().orElse(null));
            } else {
                // Natural-language plan selection/cancel/switch is resolved only by the
                // continuation model and PolicyGate above. An unresolved model output must not
                // fall through to phrase matching in the Static Plan runtime.
                continuation = new MultiIntentCoordinator.Continuation(request.query(), null, null);
            }

            long intentStarted = System.nanoTime();
            Span intentSpan = startSpan("agent.intent.recognize");
            IntentResult intent;
            boolean taskContinuation = isTaskContinuation(active, continuationDecision);
            boolean staticPlanContinuation = resumedStaticPlan.isPresent();
            try (Tracer.SpanInScope ignored = scope(intentSpan)) {
                if (taskContinuation) {
                    intent = continuedTaskIntent(request, active.orElseThrow(),
                            continuationDecision.orElseThrow(), engine.bundle());
                } else if (staticPlanContinuation) {
                    intent = continuedStaticPlanIntent(request, resumedStaticPlan.orElseThrow(),
                            continuationDecision.orElseThrow(), engine.bundle());
                } else {
                Map<String,String> intentAttributes = new LinkedHashMap<>();
                intentAttributes.put("userState", nullSafe(request.userState()));
                intentAttributes.put("continuationContext", String.valueOf(
                        pendingGoalRouting
                                || continuationDecision.map(d -> d.snapshot() != null).orElse(false)));
                intentAttributes.put("pendingSwitch", String.valueOf(request.action() != null
                        && request.action().event().startsWith("SWITCH_")));
                intentAttributes.put("resumeContext", String.valueOf(request.action() != null
                        && "RESUME_SUSPENDED".equals(request.action().event())));
                intent = engine.intentEngine().recognize(new IntentRequest(
                        ctx, continuation.query(), active.map(DefaultAgentRuntime::toView).orElse(null),
                        intentAttributes, contextualQuery, intentContext));
                }
                if (intentSpan != null) {
                    intentSpan.tag("agent.outcome", intent.decision().decision().name());
                    if (intent.decision().selectedCandidateId() != null) {
                        intentSpan.tag("agent.capability", intent.decision().selectedCandidateId());
                    }
                }
            } catch (RuntimeException ex) {
                error(intentSpan, ex);
                throw ex;
            } finally {
                end(intentSpan);
            }

            RouteDecision resolvedDecision = taskContinuation || staticPlanContinuation ? intent.decision()
                    : entryRoutes.routeResolvedGoal(intent, engine.bundle());
            if (!contextualQuery.usedContextRefs().isEmpty()) {
                resolvedDecision = resolvedDecision.withEvidenceRefs(contextualQuery.usedContextRefs());
            }
            RouteDecision decision = resolvedDecision;
            if (request.action() == null && continuationDecision.isPresent()
                    && shouldOfferSuspendedTaskChoice(continuationDecision.get(), decision)) {
                AgentResponse response = chooseSuspendedTask(
                        engine, ctx, continuationDecision.get().suspended());
                recordEarlyResponse(ctx, request, response, startedAt, true);
                return response;
            }
            RouteDispatcher.Handler routeHandler = routeDispatcher.handler(decision.decision());
            CapabilityCard card = decision.selectedCandidateId() == null
                    ? null : engine.bundle().capability(decision.selectedCandidateId());
            Map<String, Object> intentInput = new LinkedHashMap<>();
            intentInput.put("query", nullSafe(request.query()));
            intentInput.put("activeTask", active.isPresent());
            intentInput.put("clarifyRetry", clarifyRetry);
            intentInput.put("channel", nullSafe(request.channel()));
            intentInput.put("page", nullSafe(request.page()));
            intentInput.putAll(contextObservation(intentContext, contextualQuery));
            Map<String, Object> intentOutput = new LinkedHashMap<>();
            intentOutput.put("originalQuery", nullSafe(intent.originalQuery()));
            intentOutput.put("normalizedQuery", nullSafe(intent.normalizedQuery()));
            intentOutput.put("decision", decision.decision().name());
            intentOutput.put("reasonCode", decision.reasonCode() == null ? "" : decision.reasonCode().name());
            intentOutput.put("capability", decision.selectedCandidateId() == null
                    ? "" : decision.selectedCandidateId());
            intentOutput.put("candidateCount", intent.path() == null
                    ? 0 : intent.path().topCandidates().size());
            intentOutput.put("shortCircuit", decision.shortCircuit() == null
                    ? "NONE" : decision.shortCircuit().name());
            intentOutput.put("intentPath", decision.decision() == Decision.STATIC_PLAN
                    ? Enums.TaskSource.SLOW_PATH.name() : Enums.TaskSource.FAST_PATH.name());
            intentOutput.put("slotKeys", intent.slots() == null ? List.of() : intent.slots().keySet());
            if (intent.path() != null && intent.path().pipeline() != null) {
                var pipe = intent.path().pipeline();
                intentOutput.put("searchText", nullSafe(pipe.searchText()));
                intentOutput.put("semanticText", nullSafe(pipe.semanticText()));
                intentOutput.put("terms", pipe.terms());
            }
            record(ctx, "intent-engine", "recognize", "MAIN",
                    intentInput, intentOutput, "OK", intentStarted);

            boolean confirmed = intent.event() != null && intent.event().event() == InputEvent.CONFIRMATION;
            boolean cancelled = intent.event() != null && intent.event().event() == InputEvent.CANCEL;
            if (continuationDecision.isPresent()) {
                var event = continuationDecision.get().resolution().event();
                confirmed |= event == ContinuationContracts.Event.CONFIRM
                        || event == ContinuationContracts.Event.REVIEW_ACCEPT;
                cancelled |= event == ContinuationContracts.Event.CANCEL;
            }
            if (request.action() != null) {
                confirmed |= "CONFIRM".equals(request.action().event()) || "REVIEW_ACCEPT".equals(request.action().event());
                cancelled |= "CANCEL".equals(request.action().event());
            }

            long memoryStarted = System.nanoTime();
            Map<String, Object> turnSlots = new LinkedHashMap<>();
            PlanRecord parameterPlan = resumedStaticPlan.orElse(continuation.plan());
            if (parameterPlan != null) {
                turnSlots.putAll(plans.parameters(agentId, parameterPlan.planId()));
            }
            turnSlots.putAll(intent.slots());
            turnSlots.putAll(contextualQuery.slotUpdates());
            if (staticPlanContinuation && parameterPlan != null && !turnSlots.isEmpty()) {
                // Static Plan owns its parameters across waits. Persist only turn-visible values here;
                // working-memory enrichment below may contain transient facts from another Runtime.
                plans.saveParameters(agentId, parameterPlan.planId(), turnSlots);
            }
            Map<String, Object> slots = workingMemory.enrich(turnSlots, lease, continuation.query(),
                    contextCompilation.intentContext(), contextualQuery, card);
            java.util.Set<String> enrichedKeys = new java.util.LinkedHashSet<>(slots.keySet());
            enrichedKeys.removeAll(turnSlots.keySet());
            record(ctx, "working-memory", "enrich-slots", "MEMORY",
                    withContext(Map.of("turnSlotKeys", turnSlots.keySet(),
                            "confirmedFactKeys", lease.confirmedFacts().keySet(),
                            "conclusionCount", lease.toolConclusions().size()),
                            intentContext, contextualQuery),
                    Map.of("outputSlotKeys", slots.keySet(), "enrichedKeys", enrichedKeys),
                    "OK", memoryStarted);

            var resolutionGate = ContextResolutionPolicyGate.apply(decision, slots);
            decision = resolutionGate.decision();
            slots = resolutionGate.slots();
            routeHandler = routeDispatcher.handler(decision.decision());

            Map<String, Object> previousFacts = previousFacts(ctx);
            long conditionStarted = System.nanoTime();
            ConditionEvaluator.Evaluation condition = staticPlanContinuation
                    ? new ConditionEvaluator.Evaluation(ConditionEvaluator.Verdict.PROCEED,
                            null, Map.of(), "runtime-owned-static-plan-condition")
                    : multiIntent.checkCondition(continuation, previousFacts, slots);
            if (shouldRecordCondition(continuation, condition)) {
                SubIntent gated = continuation.item();
                record(ctx, "intent-slowpath", "condition-gate", "MAIN",
                        Map.of(
                                "summary", gated == null ? "" : nullSafe(gated.summary()),
                                "capabilityId", gated == null ? "" : nullSafe(gated.capabilityId()),
                                "condition", nullSafe(condition.condition()),
                                "previousFactKeys", previousFacts.keySet(),
                                "slotKeys", slots.keySet()),
                        Map.of(
                                "verdict", condition.verdict().name(),
                                "resolvedValues", condition.resolvedValues(),
                                "reason", nullSafe(condition.reason())),
                        condition.verdict() == ConditionEvaluator.Verdict.PROCEED
                                ? "OK" : condition.verdict().name(),
                        conditionStarted);
            }
            if (!cancelled && condition.verdict() != ConditionEvaluator.Verdict.PROCEED) {
                return conditionHeld(engine, ctx, request, continuation, condition.verdict(), decision,
                        startedAt, intent.path());
            }

            if (routeHandler == RouteDispatcher.Handler.AGENT_LOOP) {
                List<String> loopDegraded = intent.recall() == null
                        ? List.of() : intent.recall().degradedChannels();
                AgentResponse loopResponse = startLoop(
                        engine, ctx, request, lease, intentContext, decision, slots,
                        startedAt, intent.path(), loopDegraded);
                if (loopResponse != null) return loopResponse;
            }

            IntentPlan intentPlan;
            if (continuation.continuing()) {
                intentPlan = continuation.plan().plan();
            } else {
                try {
                    intentPlan = multiIntent.openIfSlowPath(
                            engine.bundle(), ctx, decision, intent.intentPlan());
                } catch (SessionAffinityMismatchException e) {
                    return affinityHeld(engine, ctx, request, startedAt, e);
                }
            }
            if (intentPlan != null) {
                recordPlanBlueprint(ctx, intentPlan, decision,
                        continuation.continuing() ? "continuation" : "open");
            }
            PlanRecord runtimePlan = continuation.plan() != null ? continuation.plan()
                    : plans.findActiveBySession(agentId, request.sessionId()).orElse(null);
            if (runtimePlan != null && decision.decision() == Decision.STATIC_PLAN
                    && !continuation.continuing() && !staticPlanContinuation) {
                plans.saveParameters(agentId, runtimePlan.planId(), slots);
            }

            List<String> expected = expectedAnswers(engine.bundle(), decision);
            Enums.InvocationOrigin invocationOrigin = invocationOrigin(request);
            Enums.TaskSource intentPath = decision.decision() == Decision.STATIC_PLAN
                    || continuation.continuing()
                    ? Enums.TaskSource.SLOW_PATH : Enums.TaskSource.FAST_PATH;
            OrchestrationRequest orchestrationRequest = new OrchestrationRequest(
                    ctx, decision, card, slots, intent.originalQuery(), confirmed, expected, lease,
                    intentPath, invocationOrigin, null, contextCompilation.intentContext());

            long orchestrationStarted = System.nanoTime();
            Span taskSpan = startSpan("agent.task.orchestrate");
            OrchestrationOutcome outcome;
            boolean slowCoordinatorHandled = false;
            try (Tracer.SpanInScope ignored = scope(taskSpan)) {
                if (taskSpan != null && card != null) {
                    taskSpan.tag("agent.capability", card.capabilityId());
                }
                Optional<OrchestrationOutcome> slowOutcome =
                        routeHandler == RouteDispatcher.Handler.STATIC_PLAN || continuation.continuing()
                                ? staticPlan.execute(ctx, intentPlan, engine.bundle(), slots, lease,
                                        java.time.Instant.now().plusSeconds(30), invocationOrigin,
                                        confirmed, cancelled,
                                        staticPlanContinuation
                                                ? continuationDecision.orElseThrow().snapshot().stateVersion()
                                                : null)
                                : Optional.empty();
                slowCoordinatorHandled = slowOutcome.isPresent();
                outcome = slowOutcome.orElseGet(() -> orchestrator.handle(orchestrationRequest));
                if (taskSpan != null) {
                    taskSpan.tag("agent.outcome", outcome.state() == null
                            ? "UNKNOWN" : outcome.state().name());
                }
            } catch (RuntimeException ex) {
                error(taskSpan, ex);
                throw ex;
            } finally {
                end(taskSpan);
            }
            Map<String, Object> orchestrationOutput = new LinkedHashMap<>();
            orchestrationOutput.put("taskId", outcome.taskId() == null ? "" : outcome.taskId());
            orchestrationOutput.put("state", outcome.state() == null ? "" : outcome.state().name());
            orchestrationOutput.put("executed", outcome.executed());
            if (outcome.result() != null) {
                orchestrationOutput.put("taskStatus", outcome.result().status().name());
                orchestrationOutput.put("failureClass", outcome.result().failureClass().name());
                orchestrationOutput.put("resultKeys", outcome.result().resultPayload().keySet());
            }
            record(ctx, "task-orchestrator", "orchestrate", "MAIN",
                    withContext(Map.of("capability", card == null ? "" : card.capabilityId(),
                            "risk", card == null ? "" : card.riskLevel().name(),
                            "parameterKeys", slots.keySet(), "confirmed", confirmed,
                            "intentPath", intentPath.name(),
                            "invocationOrigin", invocationOrigin.name(),
                            "contextTrustworthy", lease.trustworthy()),
                            intentContext, contextualQuery),
                    orchestrationOutput,
                    outcome.result() == null ? "PENDING" : outcome.result().status().name(),
                    orchestrationStarted);

            PlanRecord latestRuntimePlan = runtimePlan == null ? null
                    : plans.findById(agentId, runtimePlan.planId()).orElse(runtimePlan);
            CapabilityCard responseCard = responseCapability(
                    card, latestRuntimePlan, engine.bundle(), outcome);
            Map<String, Object> defaultRenderSlots = mergeForRendering(outcome, slots);
            if (latestRuntimePlan != null
                    && (latestRuntimePlan.state() == PlanState.WAITING_CONFIRMATION
                    || latestRuntimePlan.state() == PlanState.WAITING_REVIEW)) {
                Map<String, Object> completedFacts = completedPlanFacts(latestRuntimePlan);
                if (!completedFacts.isEmpty()) {
                    defaultRenderSlots = new LinkedHashMap<>(defaultRenderSlots);
                    defaultRenderSlots.put("capabilities", completedFacts);
                }
            }
            if (latestRuntimePlan != null && latestRuntimePlan.state() == PlanState.WAITING_USER) {
                PlanRecord.PendingInteraction pendingInteraction = latestRuntimePlan.pendingInteraction();
                SubIntent pendingItem = latestRuntimePlan.next().orElse(null);
                if (latestRuntimePlan.pendingInteraction() != null
                        && latestRuntimePlan.pendingInteraction().slot() != null
                        && !"conditionDecision".equals(latestRuntimePlan.pendingInteraction().slot())) {
                    ClarifyConfig.SlotClarify clarify = engine.bundle().clarify().getSlots()
                            .get(latestRuntimePlan.pendingInteraction().slot());
                    if (clarify != null) {
                        defaultRenderSlots = new LinkedHashMap<>(defaultRenderSlots);
                        defaultRenderSlots.put("question", clarify.getQuestion());
                        defaultRenderSlots.put("options", clarify.getOptions());
                    }
                } else if (pendingItem != null && pendingItem.condition() != null) {
                    defaultRenderSlots = new LinkedHashMap<>(defaultRenderSlots);
                    defaultRenderSlots.put("condition", pendingItem.condition());
                    defaultRenderSlots.put("taskSummary", pendingItem.summary());
                    Map<String, Object> completedFacts = completedPlanFacts(latestRuntimePlan);
                    if (!completedFacts.isEmpty()) {
                        defaultRenderSlots.put("capabilities", completedFacts);
                    }
                    if (pendingInteraction != null
                            && !pendingInteraction.expectedAnswers().isEmpty()) {
                        defaultRenderSlots.put("options",
                                pendingInteraction.expectedAnswers());
                    }
                }
            }
            Map<String,Object> baseRenderSlots = defaultRenderSlots;
            RouteDecision hookDecision = decision;
            Optional<Map<String, Object>> hooked = postOrchestration.flatMap(h -> h.enrichRenderSlots(
                    ctx, hookDecision, intentPlan, engine.bundle(), outcome, lease, baseRenderSlots));
            Map<String, Object> renderSlots;
            if (hooked.isPresent()) {
                renderSlots = hooked.get();
                multiIntent.releaseAffinity(ctx);
            } else {
                renderSlots = baseRenderSlots;
            }
            renderSlots = responseEnrichers.enrich(new ResponseEnrichmentContext(
                    ctx, decision, intentPlan, outcome.taskId(), outcome.result(),
                    outcome.guardrail(), renderSlots));

            long responseStarted = System.nanoTime();
            Span responseSpan = startSpan("agent.response.render");
            ResponsePlan plan;
            RenderedResponse rendered;
            try (Tracer.SpanInScope ignored = scope(responseSpan)) {
                plan = engine.planner().plan(new ResponseContext(
                        ctx, decision, responseCard, outcome.taskId(), outcome.result(), outcome.guardrail(),
                        renderSlots, intent.templateKey(), cancelled, intent.originalQuery(), intentPlan));
                rendered = engine.renderer().render(plan, new ResponseModelContext(
                        intentContext.conversationHistory(), intent.originalQuery(), plan.slots()));
                if (responseSpan != null) {
                    responseSpan.tag("agent.outcome", rendered.fellBack() ? "FALLBACK" : "SUCCEEDED");
                }
            } catch (RuntimeException ex) {
                error(responseSpan, ex);
                throw ex;
            } finally {
                end(responseSpan);
            }
            record(ctx, "response-engine", "plan-and-render", "MAIN",
                    withContext(Map.of("decision", decision.decision().name(),
                            "taskState", outcome.state() == null ? "" : outcome.state().name(),
                            "slotKeys", renderSlots.keySet()),
                            intentContext, contextualQuery),
                    Map.of("template", rendered.usedTemplateKey() == null
                                    ? "" : rendered.usedTemplateKey(),
                            "fellBack", rendered.fellBack()),
                    rendered.fellBack() ? "FALLBACK" : "OK", responseStarted);

            if (!slowCoordinatorHandled) {
                multiIntent.afterTurn(continuation, outcome);
            }

            List<String> degraded = intent.recall() == null
                    ? List.of() : intent.recall().degradedChannels();
            decisionRecorder.record(ctx.traceId(), ctx.sessionId(), request.query(), decision, outcome.taskId(),
                    rendered.usedTemplateKey(), rendered.fellBack(), degraded,
                    (System.nanoTime() - startedAt) / 1_000_000L, intent.path(),
                    ctx.gatewayCalls(), ctx.moduleSteps());

            log.info("出口 trace={} decision={} reason={} shortCircuit={} task={} template={} 上下文={}",
                    ctx.traceId(), decision.decision(), decision.reasonCode(), decision.shortCircuit(),
                    outcome.taskId(), rendered.usedTemplateKey(), leaseSummary(lease));

            registerRuntime(ctx, request, decision, outcome, continuation, intentPlan, latestRuntimePlan);
            if (outcome.taskId() == null) {
                platform.ifPresent(p -> p.completeDirectPendingGoal(ctx, request.attributes().get("pendingGoalId")));
            }
            String actionRef = outcome.taskId();
            long actionVersion = taskStateVersion(outcome.taskId());
            if (latestRuntimePlan != null && (latestRuntimePlan.state() == PlanState.WAITING_USER
                    || latestRuntimePlan.state() == PlanState.WAITING_REVIEW
                    || latestRuntimePlan.state() == PlanState.WAITING_CONFIRMATION)) {
                actionRef = latestRuntimePlan.planId();
                actionVersion = latestRuntimePlan.stateVersion();
            }
            List<ResponseAction> actions = new java.util.ArrayList<>(
                    responseActions(plan, actionRef, actionVersion));
            platform.ifPresent(bridge -> bridge.availableResumeTargets(ctx).forEach(target -> {
                boolean duplicate = actions.stream().anyMatch(action ->
                        "RESUME_SUSPENDED".equals(action.event())
                                && target.runtimeRef().equals(action.ref()));
                if (!duplicate) {
                    actions.add(new ResponseAction("RESUME_SUSPENDED", target.displaySummary(),
                            target.runtimeRef(), target.stateVersion(), ResponseAction.Style.SECONDARY));
                }
            }));
            AgentResponse response = new AgentResponse(ctx.traceId(), rendered.text(), decision, plan,
                    outcome.taskId(), rendered.usedTemplateKey(), rendered.fellBack(), degraded,
                    actions);
            recordTurn(ctx, request, responseCard, outcome, expected, response);
            return response;
        } finally {
            RequestContextHolder.clear();
        }
    }

    private static Enums.InvocationOrigin invocationOrigin(AgentRequest request) {
        String raw = request.attributes().get("invocationOrigin");
        return "A2A".equalsIgnoreCase(raw)
                ? Enums.InvocationOrigin.A2A : Enums.InvocationOrigin.LOCAL;
    }

    private AgentResponse startLoop(RuntimeEngines engine, RequestContext ctx, AgentRequest request,
                                    ContextLease lease, IntentContext intentContext,
                                    RouteDecision decision, Map<String,Object> slots,
                                    long startedAt, PathSummary path, List<String> degradedChannels) {
        if (loopStarter.isEmpty()) return null;
        String rootTaskId = "loop-root-" + UUID.randomUUID();
        com.huawei.finance.orchestrator.context.TaskContextModels.PlatformTask reserved = null;
        if (platform.isPresent()) {
            reserved = routeDispatcher.reserve(ctx, decision, RuntimeType.AGENT_LOOP).orElse(null);
            rootTaskId = reserved.platformTaskId();
        }
        com.huawei.finance.orchestrator.loop.LoopContracts.Outcome outcome;
        try {
            outcome = loopStarter.get().start(ctx.spaceId(), ctx, rootTaskId, request.query(), decision,
                    engine.bundle(), lease, slots, java.time.Instant.now().plusSeconds(30), intentContext);
        } catch (RuntimeException failure) {
            if (reserved != null) {
                routeDispatcher.failReservation(ctx, reserved,
                        request.attributes().get("pendingGoalId"), "RUNTIME_START_FAILED");
            }
            throw failure;
        }
        if (reserved != null) {
            routeDispatcher.bindAndFocus(ctx, reserved, RuntimeType.AGENT_LOOP, outcome.loopId(),
                    request.attributes().get("pendingGoalId"));
            routeDispatcher.closeIfTerminal(ctx, outcome.loopId(), terminal(outcome.state()));
        }
        AgentResponse response = loopResponses.respond(engine, ctx, decision, outcome,
                intentContext.conversationHistory(), request.query());
        recordLoopTurn(ctx, request, response, outcome);
        recordLoopDecision(ctx, request, response, outcome, startedAt, path, degradedChannels);
        return response;
    }

    private AgentResponse resumeLoop(RuntimeEngines engine, RequestContext ctx, AgentRequest request,
                                     ContextLease lease, IntentContext intentContext,
                                     ContinuationContracts.Decision continuation, long startedAt) {
        if (loopStarter.isEmpty()) return null;
        var resolution = continuation.resolution();
        String loopId = continuation.snapshot().runtimeRef();
        var outcome = resolution.event() == ContinuationContracts.Event.CANCEL
                ? loopStarter.get().cancel(ctx.spaceId(), ctx, loopId,
                        continuation.snapshot().stateVersion())
                : loopStarter.get().resume(ctx.spaceId(), ctx, loopId, engine.bundle(), lease,
                        resolution.slotUpdates(), resolution.event() == ContinuationContracts.Event.CONFIRM
                                || resolution.event() == ContinuationContracts.Event.REVIEW_ACCEPT,
                        resolution.event(), continuation.snapshot().stateVersion(), intentContext);
        RouteDecision decision = RouteDecision.builder().decision(Decision.RESUME_LOOP)
                .target(new com.huawei.finance.contracts.model.RouteTarget(
                        com.huawei.finance.contracts.model.RouteTarget.Type.LOOP, loopId))
                .taskShape(com.huawei.finance.contracts.model.TaskShape.OBSERVATION_DRIVEN)
                .confidence(resolution.confidence()).reasonCode(ReasonCode.CONTINUATION)
                .configVersion(engine.bundle().assetVersion()).build();
        routeDispatcher.closeIfTerminal(ctx, loopId, terminal(outcome.state()));
        AgentResponse response = loopResponses.respond(engine, ctx, decision, outcome,
                intentContext.conversationHistory(), request.query());
        recordLoopTurn(ctx, request, response, outcome);
        recordLoopDecision(ctx, request, response, outcome, startedAt, PathSummary.empty(), List.of());
        return response;
    }

    private void registerRuntime(RequestContext ctx, AgentRequest request, RouteDecision decision,
                                 OrchestrationOutcome outcome,
                                 MultiIntentCoordinator.Continuation continuation, IntentPlan intentPlan,
                                 PlanRecord staticPlanRecord) {
        if (platform.isEmpty() || (outcome.taskId() == null && staticPlanRecord == null)) return;
        RuntimeType type = decision.decision() == Decision.START_WORKFLOW ? RuntimeType.WORKFLOW : RuntimeType.TASK;
        String ref = outcome.taskId();
        boolean terminal = outcome.state() != null && outcome.state().terminal();
        if ((decision.decision() == Decision.STATIC_PLAN || continuation.continuing())
                && staticPlanRecord != null) {
            type = RuntimeType.STATIC_PLAN;
            ref = staticPlanRecord.planId();
            terminal = switch (staticPlanRecord.state()) {
                case COMPLETED, ABANDONED, FAILED, CANCELLED -> true;
                default -> false;
            };
        }
        routeDispatcher.register(ctx, decision, type, ref, terminal,
                request.attributes().get("pendingGoalId"));
    }

    private AgentResponse handlePendingSwitch(RuntimeEngines engine, RequestContext ctx, AgentRequest request,
                                              ContinuationContracts.Decision continuation) {
        if (switches.isEmpty() || continuation.pendingSwitch() == null) return null;
        var resolution = continuation.resolution();
        var pending = continuation.pendingSwitch();
        if (resolution.event() == ContinuationContracts.Event.UNRESOLVED) {
            return "SUSPENDED_TASK_LIMIT".equals(resolution.reasonCode())
                    ? switchResponses.suspendedLimit(engine, ctx, pending)
                    : switchResponses.review(engine, ctx, pending);
        }
        if (resolution.event() == ContinuationContracts.Event.SWITCH_REJECT) {
            switches.get().reject(ctx.spaceId(), ctx.agentId(), ctx.sessionId(), pending.switchId(),
                    pending.version(), ctx.traceId());
            return switchResponses.rejected(engine, ctx);
        }
        if (resolution.event() != ContinuationContracts.Event.SWITCH_ACCEPT) return null;
        recordUserAction(ctx, request, Decision.RESUME_TASK, ReasonCode.CONTINUATION);
        var goal = switches.get().accept(ctx.spaceId(), ctx.agentId(), ctx.sessionId(), pending.switchId(),
                pending.version(), ctx.traceId());
        String query = recoverGoal(ctx, goal.sourceTurnId(), goal.spanStart(), goal.spanEnd());
        AgentRequest routed = new AgentRequest(request.sessionId(), query, request.userId(), request.spaceId(),
                request.channel(), request.page(), request.userState(), Map.of("pendingGoalId", goal.pendingGoalId()),
                null, request.principal(), request.lineage());
        AgentResponse response;
        try {
            response = handle(routed);
        } catch (RuntimeException routingFailure) {
            platform.ifPresent(p -> p.failReservedRuntime(ctx, null, goal.pendingGoalId(),
                    "PENDING_GOAL_ROUTING_FAILED"));
            throw routingFailure;
        }
        return response;
    }

    private String recoverGoal(RequestContext ctx, String sourceTurnId, int start, int end) {
        return turnStore.recent(ctx.spaceId(), ctx.agentId(), ctx.sessionId(), 50).stream()
                .filter(turn -> sourceTurnId.equals(turn.traceId())).findFirst()
                .map(turn -> {
                    String text = turn.userText();
                    int safeStart = Math.max(0, Math.min(start, text.length()));
                    int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
                    return text.substring(safeStart, safeEnd);
                }).orElseThrow(() -> new IllegalStateException("SWITCH_SOURCE_TURN_NOT_FOUND"));
    }

    private static boolean terminal(com.huawei.finance.orchestrator.loop.LoopContracts.Status state) {
        return switch (state) {
            case COMPLETED, FAILED, HANDED_OFF, EXPIRED, CANCELLED -> true;
            default -> false;
        };
    }

    private AgentResponse invalidStructuredAction(RuntimeEngines engine, RequestContext context,
                                                  ContinuationContracts.Decision continuation) {
        var snapshot = continuation.snapshot();
        String ref = snapshot == null ? null : snapshot.runtimeRef();
        RouteTarget.Type type = snapshot != null && snapshot.runtimeType() == RuntimeType.AGENT_LOOP
                ? RouteTarget.Type.LOOP : RouteTarget.Type.CAPABILITY;
        RouteDecision decision = RouteDecision.builder().decision(Decision.CLARIFY)
                .target(ref == null ? null : new RouteTarget(type, ref))
                .taskShape(TaskShape.AMBIGUOUS_GOAL).confidence(1)
                .reasonCode(ReasonCode.RESUME_REQUIRED)
                .evidenceRefs(List.of("structured-action:stale-or-invalid"))
                .configVersion(engine.bundle().assetVersion())
                .shortCircuit(ShortCircuitLevel.CONTINUATION).build();
        List<String> actionCodes = snapshot == null ? List.of() : snapshot.allowedEvents().stream()
                .map(Enum::name)
                .filter(code -> code.equals("REVIEW_ACCEPT") || code.equals("CONFIRM") || code.equals("CANCEL"))
                .toList();
        Enums.ResponsePhase phase = actionCodes.contains("REVIEW_ACCEPT") ? Enums.ResponsePhase.REVIEW
                : actionCodes.contains("CONFIRM") ? Enums.ResponsePhase.CONFIRM : Enums.ResponsePhase.CLARIFY;
        ResponsePlan plan = ResponsePlan.builder().traceId(context.traceId()).taskId(ref)
                .sceneCode("ACTION#STALE").responsePhase(phase).templateKey("tpl.action.stale")
                .templateVersion("1.0.0").slots(Map.of()).actionCodes(actionCodes)
                .channel(context.channel()).fallbackTemplateKey("tpl.fallback.generic").build();
        RenderedResponse rendered = engine.renderer().render(plan);
        long version = snapshot == null ? 0 : snapshot.stateVersion();
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, ref,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(),
                responseActions(plan, ref, version));
    }

    private AgentResponse chooseSuspendedTask(RuntimeEngines engine, RequestContext context,
                                              List<ContinuationContracts.Snapshot> suspended) {
        RouteDecision decision = RouteDecision.builder().decision(Decision.CLARIFY)
                .taskShape(TaskShape.AMBIGUOUS_GOAL).confidence(1)
                .reasonCode(ReasonCode.RESUME_REQUIRED)
                .evidenceRefs(List.of("runtime:multiple-suspended"))
                .configVersion(engine.bundle().assetVersion())
                .shortCircuit(ShortCircuitLevel.CONTINUATION).build();
        List<String> summaries = suspended.stream().map(ContinuationContracts.Snapshot::displaySummary).toList();
        ResponsePlan plan = ResponsePlan.builder().traceId(context.traceId())
                .sceneCode("TASK#RESUME_SELECT").responsePhase(Enums.ResponsePhase.CLARIFY)
                .templateKey("tpl.resume.select").templateVersion("1.0.0")
                .slots(Map.of("tasks", summaries))
                .cardComponents(List.of(ResponseComponent.CHOICE_LIST))
                .actionCodes(List.of("RESUME_SUSPENDED"))
                .channel(context.channel()).fallbackTemplateKey("tpl.fallback.generic").build();
        RenderedResponse rendered = engine.renderer().render(plan);
        List<ResponseAction> actions = suspended.stream()
                .map(snapshot -> new ResponseAction("RESUME_SUSPENDED", snapshot.displaySummary(),
                        snapshot.runtimeRef(), snapshot.stateVersion(), ResponseAction.Style.SECONDARY))
                .toList();
        return new AgentResponse(context.traceId(), rendered.text(), decision, plan, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(), actions);
    }

    static boolean shouldOfferSuspendedTaskChoice(ContinuationContracts.Decision continuation,
                                                  RouteDecision entryDecision) {
        if (continuation == null || entryDecision == null
                || continuation.resolution().event() != ContinuationContracts.Event.UNRESOLVED
                || continuation.snapshot() != null || continuation.suspended().size() <= 1) {
            return false;
        }
        // An explicit new-goal interpretation must keep its own failure semantics. This fallback
        // only repairs the case where neither continuation understanding nor entry routing could
        // resolve the utterance, while the platform still has useful resume choices to present.
        if ("NEW_GOAL".equals(continuation.resolution().reasonCode())) {
            return false;
        }
        return entryDecision.reasonCode() == ReasonCode.NO_CANDIDATE;
    }

    private static List<ResponseAction> responseActions(ResponsePlan plan, String ref, long version) {
        if (plan == null || ref == null) return List.of();
        return plan.actionCodes().stream().filter(code -> switch (code) {
                    case "REVIEW_ACCEPT", "CONFIRM", "CANCEL" -> true; default -> false; })
                .map(code -> new ResponseAction(code, switch (code) {
                            case "REVIEW_ACCEPT" -> "继续"; case "CONFIRM" -> "确认执行"; default -> "取消"; },
                        ref, version, "CANCEL".equals(code) ? ResponseAction.Style.DANGER : ResponseAction.Style.PRIMARY))
                .toList();
    }

    private static CapabilityCard responseCapability(
            CapabilityCard routed, PlanRecord plan, AssetBundle assets, OrchestrationOutcome outcome) {
        if (routed != null || plan == null || outcome.result() != null) return routed;
        return plan.next().map(SubIntent::capabilityId)
                .map(assets::capability).orElse(null);
    }

    private long taskStateVersion(String taskId) {
        return taskId == null ? 0 : taskRepository.findById(taskId)
                .map(TaskRecord::stateVersion).orElse(0L);
    }

    private static boolean isTaskContinuation(Optional<TaskRecord> active,
                                              Optional<ContinuationContracts.Decision> continuation) {
        if (active.isEmpty() || continuation.isEmpty() || continuation.get().snapshot() == null) return false;
        RuntimeType type = continuation.get().snapshot().runtimeType();
        if (type != RuntimeType.TASK && type != RuntimeType.WORKFLOW) return false;
        return switch (continuation.get().resolution().event()) {
            case FILL_SLOT, CORRECTION, REVIEW_ACCEPT, CONFIRM, CANCEL, CONTINUE_CURRENT -> true;
            default -> false;
        };
    }

    private static boolean isStaticPlanContinuation(ContinuationContracts.Decision continuation) {
        if (continuation == null || continuation.snapshot() == null
                || continuation.snapshot().runtimeType() != RuntimeType.STATIC_PLAN) return false;
        return switch (continuation.resolution().event()) {
            case FILL_SLOT, REVIEW_ACCEPT, CONFIRM, CANCEL, CONTINUE_CURRENT -> true;
            default -> false;
        };
    }

    private static IntentResult continuedTaskIntent(AgentRequest request, TaskRecord task,
                                                    ContinuationContracts.Decision continuation,
                                                    AssetBundle assets) {
        boolean cancel = continuation.resolution().event() == ContinuationContracts.Event.CANCEL;
        RouteTarget.Type targetType = continuation.snapshot().runtimeType() == RuntimeType.WORKFLOW
                ? RouteTarget.Type.WORKFLOW : RouteTarget.Type.CAPABILITY;
        RouteDecision route = RouteDecision.builder()
                .decision(cancel ? Decision.CANCEL : Decision.RESUME_TASK)
                .target(new RouteTarget(targetType, task.capabilityId()))
                .candidateIds(List.of(task.capabilityId()))
                .taskShape(TaskShape.SINGLE_ACTION)
                .confidence(1)
                .reasonCode(ReasonCode.CONTINUATION)
                .evidenceRefs(List.of("runtime:" + task.taskId()))
                .configVersion(assets.assetVersion())
                .shortCircuit(ShortCircuitLevel.CONTINUATION)
                .build();
        Map<String,Object> slots = new LinkedHashMap<>(task.parameters());
        slots.putAll(canonicalSlotUpdates(assets, continuation.resolution().slotUpdates()));
        return new IntentResult() {
            @Override public RouteDecision decision() { return route; }
            @Override public Map<String,Object> slots() { return Map.copyOf(slots); }
            @Override public com.huawei.finance.contracts.model.RecallResult recall() { return null; }
            @Override public String originalQuery() { return request.query(); }
            @Override public String normalizedQuery() { return request.query(); }
            @Override public com.huawei.finance.common.event.EventClassification event() { return null; }
            @Override public String templateKey() { return null; }
            @Override public IntentPlan intentPlan() { return null; }
            @Override public PathSummary path() { return null; }
        };
    }

    private static IntentResult continuedStaticPlanIntent(
            AgentRequest request, PlanRecord plan,
            ContinuationContracts.Decision continuation, AssetBundle assets) {
        boolean cancel = continuation.resolution().event() == ContinuationContracts.Event.CANCEL;
        String capabilityId = plan.next().map(SubIntent::capabilityId).orElse(null);
        RouteDecision route = RouteDecision.builder()
                .decision(cancel ? Decision.CANCEL : Decision.STATIC_PLAN)
                .target(new RouteTarget(RouteTarget.Type.TASK, plan.planId()))
                .candidateIds(capabilityId == null ? List.of() : List.of(capabilityId))
                .taskShape(plan.plan().hasConditional()
                        ? TaskShape.CONDITIONAL_PLAN : TaskShape.FIXED_MULTI_STEP)
                .intentPlan(plan.plan())
                .confidence(1)
                .reasonCode(ReasonCode.CONTINUATION)
                .evidenceRefs(List.of("runtime:" + plan.planId()))
                .configVersion(assets.assetVersion())
                .shortCircuit(ShortCircuitLevel.CONTINUATION)
                .build();
        Map<String,Object> slots = canonicalSlotUpdates(assets, continuation.resolution().slotUpdates());
        return new IntentResult() {
            @Override public RouteDecision decision() { return route; }
            @Override public Map<String,Object> slots() { return slots; }
            @Override public com.huawei.finance.contracts.model.RecallResult recall() { return null; }
            @Override public String originalQuery() { return request.query(); }
            @Override public String normalizedQuery() { return request.query(); }
            @Override public com.huawei.finance.common.event.EventClassification event() { return null; }
            @Override public String templateKey() { return null; }
            @Override public IntentPlan intentPlan() { return plan.plan(); }
            @Override public PathSummary path() { return null; }
        };
    }

    private static Map<String, Object> canonicalSlotUpdates(
            AssetBundle assets, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) return Map.of();
        Map<String, Object> canonical = new LinkedHashMap<>();
        updates.forEach((slot, value) -> {
            var definition = assets.clarify().getSlots().get(slot);
            String text = value == null ? null : String.valueOf(value);
            Object resolved = definition == null || text == null
                    ? value : definition.getValueMapping().getOrDefault(text, text);
            canonical.put(slot, resolved);
        });
        return Map.copyOf(canonical);
    }

    private static PrincipalState principalOf(AgentRequest request) {
        if (request.principal() != null) {
            return request.principal();
        }
        return request.userId() == null || request.userId().isBlank()
                ? PrincipalState.anonymous(request.channel())
                : new PrincipalState(request.userId(), true, "AUTHENTICATED", request.channel());
    }

    private static String leaseSummary(ContextLease lease) {
        if (!lease.trustworthy()) {
            return "降级";
        }
        return lease.usedTokens() + "/" + lease.budgetTokens() + "tok"
                + (lease.wasTrimmed() ? " 裁" + lease.trimmed().size() : "");
    }

    private static Map<String, Object> withContext(
            Map<String, Object> moduleInput, IntentContext context, ContextualQuery rewrite) {
        Map<String, Object> input = new LinkedHashMap<>(moduleInput == null ? Map.of() : moduleInput);
        input.putAll(contextObservation(context, rewrite));
        return Map.copyOf(input);
    }

    private static Map<String, Object> contextObservation(
            IntentContext context, ContextualQuery rewrite) {
        if (context == null) return Map.of();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contextStateVersion", context.stateVersion());
        view.put("contextTrustworthy", context.trustworthy());
        view.put("conversationHistory", context.conversationHistory());
        view.put("availableContext", context.evidence());
        view.put("availableContextRefs", context.evidenceRefs());
        if (rewrite != null) {
            view.put("standaloneQuery", rewrite.standaloneQuery());
            view.put("contextEventType", rewrite.eventType().name());
            view.put("usedContextRefs", rewrite.usedContextRefs());
            view.put("contextSlotUpdates", rewrite.slotUpdates());
        }
        return Map.copyOf(view);
    }

    private Map<String, Object> previousFacts(RequestContext ctx) {
        try {
            List<ConversationTurn> recent = turnStore.recent(ctx.spaceId(), ctx.agentId(), ctx.sessionId(), 1);
            return recent.isEmpty() ? Map.of() : recent.get(recent.size() - 1).facts();
        } catch (RuntimeException e) {
            log.warn("读历史失败，条件将按判不出来处理 session={} cause={}",
                    ctx.sessionId(), e.toString());
            return Map.of();
        }
    }

    private AgentResponse affinityHeld(RuntimeEngines engine, RequestContext ctx, AgentRequest request,
                                       long startedAt, SessionAffinityMismatchException mismatch) {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.HANDOFF)
                .reasonCode(ReasonCode.POLICY_BLOCK)
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();
        ResponsePlan plan = ResponsePlan.builder()
                .traceId(ctx.traceId())
                .responsePhase(Enums.ResponsePhase.FINAL)
                .templateKey("tpl.reject.capability-not-open")
                .slots(Map.of("capabilityName", "该会话（已在其他服务实例办理中）",
                        "alternativeEntry", "原渠道稍后重试"))
                .channel(ctx.channel())
                .build();
        RenderedResponse rendered = engine.renderer().render(plan);
        decisionRecorder.record(ctx.traceId(), ctx.sessionId(), request.query(), decision, null, rendered.usedTemplateKey(),
                rendered.fellBack(), List.of(), (System.nanoTime() - startedAt) / 1_000_000L,
                null, ctx.gatewayCalls(), ctx.moduleSteps());
        log.warn("会话亲和拒绝续办 trace={} session={} local={} cause={}",
                ctx.traceId(), mismatch.sessionId(), mismatch.localInstanceId(), mismatch.getMessage());
        AgentResponse response = new AgentResponse(ctx.traceId(), rendered.text(), decision, plan, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of());
        recordResponseTurn(ctx, request, response);
        return response;
    }

    private AgentResponse unresolvedContextReference(
            RuntimeEngines engine, RequestContext ctx, AgentRequest request,
            ContextualQuery contextualQuery, long startedAt) {
        List<String> evidenceRefs = contextualQuery.resolutions().stream()
                .map(ContextualQuery.Resolution::contextRef)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .taskShape(TaskShape.AMBIGUOUS_GOAL)
                .missingSlots(List.of("contextReference"))
                .confidence(1.0)
                .reasonCode(ReasonCode.UNRESOLVED_REFERENCE)
                .evidenceRefs(evidenceRefs)
                .modelVersion(contextualQuery.modelVersion())
                .promptVersion(contextualQuery.promptVersion())
                .configVersion(engine.bundle().assetVersion())
                .shortCircuit(ShortCircuitLevel.CONTINUATION)
                .build();
        ResponsePlan plan = engine.planner().plan(new ResponseContext(
                ctx, decision, null, null, null, null, Map.of(), null,
                false, request.query(), null));
        RenderedResponse rendered = engine.renderer().render(plan);
        record(ctx, "context-engine", "unresolved-reference", "POLICY",
                Map.of("rewriteOutcome", contextualQuery.rewriteOutcome().name(),
                        "resolutionCount", contextualQuery.resolutions().size()),
                Map.of("decision", decision.decision().name(),
                        "reasonCode", decision.reasonCode().name()),
                "CLARIFY", startedAt);
        decisionRecorder.record(ctx.traceId(), ctx.sessionId(), request.query(), decision, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                null, ctx.gatewayCalls(), ctx.moduleSteps());
        AgentResponse response = new AgentResponse(ctx.traceId(), rendered.text(), decision, plan,
                null, rendered.usedTemplateKey(), rendered.fellBack(), List.of());
        recordResponseTurn(ctx, request, response);
        return response;
    }

    private AgentResponse conditionHeld(RuntimeEngines engine, RequestContext ctx,
                                        AgentRequest request,
                                        MultiIntentCoordinator.Continuation continuation,
                                        ConditionEvaluator.Verdict verdict,
                                        RouteDecision decision,
                                        long startedAt, PathSummary path) {
        SubIntent item = continuation.item();
        boolean stopped = verdict == ConditionEvaluator.Verdict.STOP;
        ResponsePlan plan = ResponsePlan.builder()
                .traceId(ctx.traceId())
                .responsePhase(stopped ? Enums.ResponsePhase.FINAL : Enums.ResponsePhase.CLARIFY)
                .templateKey(stopped ? "tpl.answer.condition-not-met" : "tpl.clarify.condition")
                .slots(Map.of("condition", item.condition(), "taskSummary", item.summary()))
                .channel(ctx.channel())
                .build();
        RenderedResponse rendered = engine.renderer().render(plan);
        if (stopped) {
            multiIntent.skipCurrent(continuation);
        }
        log.info("条件闸门 trace={} 判定={} 条件=「{}」件={}",
                ctx.traceId(), verdict, item.condition(), item.summary());
        decisionRecorder.record(ctx.traceId(), ctx.sessionId(), request.query(), decision, null, rendered.usedTemplateKey(),
                rendered.fellBack(), List.of(), (System.nanoTime() - startedAt) / 1_000_000L,
                path, ctx.gatewayCalls(), ctx.moduleSteps());
        AgentResponse response = new AgentResponse(ctx.traceId(), rendered.text(), decision, plan, null,
                rendered.usedTemplateKey(), rendered.fellBack(), List.of());
        recordResponseTurn(ctx, request, response);
        return response;
    }

    private ContextCompilation contextFor(RequestContext ctx, AgentRequest request,
                                          Optional<TaskRecord> active) {
        String goal = active.map(TaskRecord::goal).orElse(request.query());
        Map<String, Object> confirmed = new LinkedHashMap<>(
                active.map(TaskRecord::parameters).orElse(Map.of()));
        SubtaskContextEnvelope delegated = request.subtaskContext();
        if (delegated != null) {
            if (delegated.expired(java.time.Instant.now())) {
                java.time.Instant expiresAt = java.time.Instant.now().plus(leaseCompiler.leaseTtl());
                return new ContextCompilation(
                        ContextLease.degraded(request.sessionId(), goal, expiresAt),
                        IntentContext.degraded(request.sessionId(), goal, expiresAt));
            }
            confirmed.putAll(delegated.confirmedInputs());
            delegated.facts().forEach(fact -> confirmed.putAll(fact.value()));
        }
        ContextCompilation compiled = leaseCompiler.compileContext(
                ctx.spaceId(), ctx.agentId(), request.sessionId(), goal, confirmed, pendingItems(active));
        if (delegated == null || delegated.facts().isEmpty()) return compiled;

        Map<String, ContextEvidence> evidence = new LinkedHashMap<>();
        compiled.intentContext().evidence().forEach(item -> evidence.put(item.ref(), item));
        delegated.facts().forEach(item -> evidence.put(item.ref(), item));
        IntentContext base = compiled.intentContext();
        IntentContext merged = new IntentContext(base.leaseId(), base.sessionId(), base.goal(),
                base.stateVersion(), base.trustworthy(), base.expiresAt(), base.confirmedFacts(),
                List.copyOf(evidence.values()), base.trimmedItems());
        return new ContextCompilation(compiled.lease(), merged);
    }

    private static List<ContextLease.PendingItem> pendingItems(Optional<TaskRecord> active) {
        return active.<List<ContextLease.PendingItem>>map(task -> switch (task.state()) {
            case CONFIRM_PENDING -> List.of(new ContextLease.PendingItem(
                    task.pendingSlot(), Enums.PendingAction.CONFIRM, List.of()));
            case REVIEW_PENDING -> List.of(new ContextLease.PendingItem(
                    task.pendingSlot(), Enums.PendingAction.REVIEW, List.of()));
            case CLARIFY_PENDING -> List.of(new ContextLease.PendingItem(
                    task.pendingSlot(),
                    task.expectedAnswers().isEmpty()
                            ? Enums.PendingAction.FILLING_SLOT : Enums.PendingAction.SELECT,
                    task.expectedAnswers()));
            default -> List.of();
        }).orElse(List.of());
    }

    private void recordTurn(RequestContext ctx, AgentRequest request, CapabilityCard card,
                            OrchestrationOutcome outcome, List<String> expected,
                            AgentResponse response) {
        try {
            turnStore.append(new ConversationTurn(ctx.spaceId(), ctx.agentId(), request.sessionId(), 0L,
                    ctx.traceId(), outcome.taskId(), request.query(), response.decision().decision(),
                    response.decision().reasonCode(), card == null ? null : card.capabilityId(),
                    outcomeOf(outcome), pendingOf(outcome, expected), expected,
                    outcome.executed() && outcome.result() != null
                            ? outcome.result().resultPayload() : Map.of(), null,
                    conversationMessages(ctx, request, card, outcome, response)));
        } catch (RuntimeException e) {
            log.warn("轮次落盘失败，下一轮将少看见这一轮 session={} cause={}",
                    request.sessionId(), e.toString());
        }
    }

    private void recordResponseTurn(RequestContext ctx, AgentRequest request,
                                    AgentResponse response) {
        appendTurn(ctx, request, response, null, null, Map.of(), List.of(),
                userAndAssistantMessages(ctx, request, response));
    }

    private void recordEarlyResponse(RequestContext ctx, AgentRequest request,
                                     AgentResponse response, long startedAt,
                                     boolean storeConversationTurn) {
        decisionRecorder.record(ctx.traceId(), ctx.sessionId(), request.query(), response.decision(),
                response.taskId(), response.usedTemplate(), response.fellBack(),
                response.degradedChannels(), (System.nanoTime() - startedAt) / 1_000_000L,
                PathSummary.empty(), ctx.gatewayCalls(), ctx.moduleSteps());
        if (storeConversationTurn) {
            recordResponseTurn(ctx, request, response);
        }
    }

    private void recordLoopTurn(RequestContext ctx, AgentRequest request, AgentResponse response,
                                com.huawei.finance.orchestrator.loop.LoopContracts.Outcome outcome) {
        Map<String, Object> facts = new LinkedHashMap<>(outcome.completedFacts());
        if (outcome.lastObservation() != null) {
            facts.putAll(outcome.lastObservation().facts());
        }
        appendTurn(ctx, request, response, outcome.loopId(), "agent_loop", Map.copyOf(facts),
                response.actions().stream().map(ResponseAction::event).toList(),
                loopMessages(ctx, request, response, outcome));
    }

    private void recordLoopDecision(RequestContext ctx, AgentRequest request, AgentResponse response,
                                    com.huawei.finance.orchestrator.loop.LoopContracts.Outcome outcome,
                                    long startedAt, PathSummary path, List<String> degradedChannels) {
        decisionRecorder.record(ctx.traceId(), ctx.sessionId(), request.query(), response.decision(),
                outcome.loopId(), response.usedTemplate(), response.fellBack(), degradedChannels,
                (System.nanoTime() - startedAt) / 1_000_000L,
                path == null ? PathSummary.empty() : path, ctx.gatewayCalls(), ctx.moduleSteps());
    }

    private void recordUserAction(RequestContext ctx, AgentRequest request,
                                  Decision decision, ReasonCode reasonCode) {
        Map<String, Object> action = actionProjection(request);
        ConversationTurn.Message message = new ConversationTurn.Message(ctx.traceId() + ":user-action",
                ConversationTurn.MessageRole.USER, ConversationTurn.MessageType.TEXT,
                null, null, request.query(), action, true, true);
        try {
            turnStore.append(new ConversationTurn(ctx.spaceId(), ctx.agentId(), request.sessionId(), 0,
                    ctx.traceId(), null, request.query(), decision, reasonCode, null, null,
                    Enums.PendingAction.NONE, List.of(), Map.of(), null, List.of(message)));
        } catch (RuntimeException e) {
            log.warn("结构化用户操作落盘失败 session={} cause={}", request.sessionId(), e.toString());
        }
    }

    private void appendTurn(RequestContext ctx, AgentRequest request, AgentResponse response,
                            String taskId, String capabilityId, Map<String, Object> facts,
                            List<String> pendingOptions, List<ConversationTurn.Message> messages) {
        try {
            turnStore.append(new ConversationTurn(ctx.spaceId(), ctx.agentId(), request.sessionId(), 0L,
                    ctx.traceId(), taskId == null ? response.taskId() : taskId, request.query(),
                    response.decision().decision(), response.decision().reasonCode(), capabilityId,
                    null, pendingOf(response), pendingOptions, facts, null, messages));
        } catch (RuntimeException e) {
            log.warn("回复轮次落盘失败，下一轮将少看见这一轮 session={} cause={}",
                    request.sessionId(), e.toString());
        }
    }

    private static List<ConversationTurn.Message> conversationMessages(
            RequestContext ctx, AgentRequest request, CapabilityCard card,
            OrchestrationOutcome outcome, AgentResponse response) {
        List<ConversationTurn.Message> messages = new java.util.ArrayList<>(
                userMessages(ctx, request));
        if (outcome.unifiedTask() != null) {
            String callId = outcome.taskId() == null ? ctx.traceId() + ":call" : outcome.taskId();
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("parameters", outcome.unifiedTask().parameters());
            call.put("contextRefs", outcome.unifiedTask().contextRefs());
            messages.add(new ConversationTurn.Message(ctx.traceId() + ":call", 
                    ConversationTurn.MessageRole.ASSISTANT,
                    ConversationTurn.MessageType.TOOL_CALL, callId,
                    outcome.unifiedTask().capabilityId(), null, Map.copyOf(call), false, true));
            if (outcome.result() != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", outcome.result().status().name());
                result.put("failureClass", outcome.result().failureClass().name());
                result.put("output", outcome.result().resultPayload());
                boolean agentResult = outcome.unifiedTask().subtaskContext() != null
                        || card != null && card.type() == Enums.CapabilityType.AGENT;
                messages.add(new ConversationTurn.Message(ctx.traceId() + ":result",
                        agentResult ? ConversationTurn.MessageRole.AGENT : ConversationTurn.MessageRole.TOOL,
                        agentResult ? ConversationTurn.MessageType.AGENT_RESULT
                                : ConversationTurn.MessageType.TOOL_RESULT,
                        callId, outcome.unifiedTask().capabilityId(), null,
                        Map.copyOf(result), false, true));
            }
        }
        messages.add(assistantMessage(ctx, response));
        return List.copyOf(messages);
    }

    private static List<ConversationTurn.Message> userAndAssistantMessages(
            RequestContext ctx, AgentRequest request, AgentResponse response) {
        List<ConversationTurn.Message> messages = new java.util.ArrayList<>(userMessages(ctx, request));
        messages.add(assistantMessage(ctx, response));
        return List.copyOf(messages);
    }

    private static List<ConversationTurn.Message> loopMessages(
            RequestContext ctx, AgentRequest request, AgentResponse response,
            com.huawei.finance.orchestrator.loop.LoopContracts.Outcome outcome) {
        List<ConversationTurn.Message> messages = new java.util.ArrayList<>(userMessages(ctx, request));
        String callId = outcome.loopId() + ':' + outcome.stateVersion();
        messages.add(new ConversationTurn.Message(ctx.traceId() + ":loop-call",
                ConversationTurn.MessageRole.ASSISTANT, ConversationTurn.MessageType.TOOL_CALL,
                callId, "agent_loop", null, Map.of("goal", request.query()), false, true));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", outcome.state().name());
        result.put("completedFacts", outcome.completedFacts());
        if (outcome.lastObservation() != null) result.put("observation", outcome.lastObservation());
        boolean agentResult = outcome.lastObservation() != null
                && outcome.lastObservation().sourceType() != null
                && (outcome.lastObservation().sourceType().contains("AGENT")
                || outcome.lastObservation().delegationId() != null);
        messages.add(new ConversationTurn.Message(ctx.traceId() + ":loop-result",
                agentResult ? ConversationTurn.MessageRole.AGENT : ConversationTurn.MessageRole.TOOL,
                agentResult ? ConversationTurn.MessageType.AGENT_RESULT
                        : ConversationTurn.MessageType.TOOL_RESULT,
                callId, "agent_loop", null, Map.copyOf(result), false, true));
        messages.add(assistantMessage(ctx, response));
        return List.copyOf(messages);
    }

    private static List<ConversationTurn.Message> userMessages(RequestContext ctx, AgentRequest request) {
        boolean replayedGoal = request.attributes().containsKey("pendingGoalId");
        return List.of(new ConversationTurn.Message(ctx.traceId() + ":user",
                ConversationTurn.MessageRole.USER, ConversationTurn.MessageType.TEXT,
                null, null, request.query(), actionProjection(request), !replayedGoal, true));
    }

    private static ConversationTurn.Message assistantMessage(RequestContext ctx, AgentResponse response) {
        return new ConversationTurn.Message(ctx.traceId() + ":assistant",
                ConversationTurn.MessageRole.ASSISTANT, ConversationTurn.MessageType.TEXT,
                null, null, response.text() == null ? "" : response.text(),
                responseProjection(response), true, true);
    }

    private static Map<String, Object> actionProjection(AgentRequest request) {
        if (request.action() == null) return Map.of();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("event", request.action().event());
        action.put("version", request.action().version());
        if (request.action().ref() != null) action.put("ref", request.action().ref());
        return Map.of("action", Map.copyOf(action));
    }

    private static Map<String, Object> responseProjection(AgentResponse response) {
        Map<String, Object> visible = new LinkedHashMap<>();
        ResponsePlan plan = response.plan();
        if (plan != null) {
            if (plan.responsePhase() != null) visible.put("responsePhase", plan.responsePhase().name());
            if (plan.sceneCode() != null) visible.put("sceneCode", plan.sceneCode());
            visible.put("cardComponents", plan.cardComponents());
            visible.put("displaySlots", plan.slots());
            visible.put("riskNoticeCodes", plan.riskNoticeCodes());
        }
        visible.put("actions", response.actions().stream().map(action -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("event", action.event());
            item.put("label", action.label());
            item.put("version", action.version());
            item.put("style", action.style().name());
            if (action.ref() != null) item.put("ref", action.ref());
            return Map.copyOf(item);
        }).toList());
        return Map.copyOf(visible);
    }

    private static Enums.ToolOutcome outcomeOf(OrchestrationOutcome outcome) {
        if (!outcome.executed() || outcome.result() == null) {
            return null;
        }
        return switch (outcome.result().status()) {
            case SUCCESS -> Enums.ToolOutcome.SUCCEEDED;
            case PARTIAL, FAILED, CANCELLED -> Enums.ToolOutcome.FAILED;
            case NEED_USER -> Enums.ToolOutcome.ADDITIONAL_TOOL;
        };
    }

    private static Enums.PendingAction pendingOf(OrchestrationOutcome outcome, List<String> expected) {
        if (outcome.state() == null) {
            return Enums.PendingAction.NONE;
        }
        return switch (outcome.state()) {
            case CONFIRM_PENDING -> Enums.PendingAction.CONFIRM;
            case REVIEW_PENDING -> Enums.PendingAction.REVIEW;
            case CLARIFY_PENDING -> expected.isEmpty()
                    ? Enums.PendingAction.FILLING_SLOT : Enums.PendingAction.SELECT;
            default -> Enums.PendingAction.NONE;
        };
    }

    private static Enums.PendingAction pendingOf(AgentResponse response) {
        if (response.plan() == null || response.plan().responsePhase() == null) {
            return Enums.PendingAction.NONE;
        }
        return switch (response.plan().responsePhase()) {
            case CONFIRM -> Enums.PendingAction.CONFIRM;
            case REVIEW -> Enums.PendingAction.REVIEW;
            case SWITCH_REVIEW -> Enums.PendingAction.SELECT;
            case CLARIFY -> response.actions().isEmpty()
                    ? Enums.PendingAction.FILLING_SLOT : Enums.PendingAction.SELECT;
            default -> Enums.PendingAction.NONE;
        };
    }

    private static Map<String, Object> mergeForRendering(OrchestrationOutcome outcome,
                                                        Map<String, Object> slots) {
        return outcome.executed() ? outcome.result().resultPayload() : slots;
    }

    private Map<String, Object> completedPlanFacts(PlanRecord plan) {
        if (plan == null) return Map.of();
        Map<String, Object> completed = new LinkedHashMap<>();
        plans.steps(plan.planId()).stream()
                .filter(step -> step.status() == Enums.TaskStatus.SUCCESS)
                .sorted(java.util.Comparator.comparingInt(
                        com.huawei.finance.orchestrator.plan.PlanStepRecord::stepIndex))
                .forEach(step -> completed.put(step.capabilityId(), step.facts()));
        return Map.copyOf(completed);
    }

    private static List<String> expectedAnswers(AssetBundle bundle, RouteDecision decision) {
        if (decision.missingSlots().isEmpty()) {
            return List.of();
        }
        ClarifyConfig.SlotClarify clarify =
                bundle.clarify().getSlots().get(decision.missingSlots().get(0));
        return clarify == null ? List.of() : clarify.getOptions();
    }

    private static ActiveTaskView toView(TaskRecord task) {
        return new ActiveTaskView(task.taskId(), task.state().name(), task.domain(),
                task.capabilityId(), task.pendingSlot(), task.expectedAnswers(),
                task.parameters(), task.clarifyRounds());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean shouldRecordCondition(MultiIntentCoordinator.Continuation continuation,
                                                 ConditionEvaluator.Evaluation evaluation) {
        if (evaluation.verdict() != ConditionEvaluator.Verdict.PROCEED) {
            return true;
        }
        return continuation.item() != null && continuation.item().condition() != null;
    }

    private static void recordPlanBlueprint(RequestContext context, IntentPlan plan,
                                            RouteDecision decision, String phase) {
        List<Map<String, Object>> items = plan.items().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", item.order());
            row.put("text", nullSafe(item.text()));
            row.put("capabilityId", nullSafe(item.capabilityId()));
            row.put("summary", nullSafe(item.summary()));
            row.put("relation", item.relation() == null ? "" : item.relation().name());
            row.put("condition", nullSafe(item.condition()));
            row.put("resolutionStrength", item.resolution().strength().name());
            row.put("topScore", item.resolution().topScore());
            row.put("margin", item.resolution().margin());
            row.put("candidateIds", item.resolution().candidateIds());
            row.put("evidenceRefs", item.resolution().evidenceRefs());
            return row;
        }).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("phase", phase);
        input.put("decision", decision.decision() == null ? "" : decision.decision().name());
        input.put("reasonCode", decision.reasonCode() == null ? "" : decision.reasonCode().name());
        input.put("originalLength", plan.original() == null ? 0 : plan.original().length());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("source", plan.source() == null ? "" : plan.source().name());
        output.put("itemCount", items.size());
        output.put("hasConditional", plan.hasConditional());
        output.put("fullyResolved", plan.fullyResolved());
        output.put("items", items);
        record(context, "intent-slowpath", "plan-blueprint", "MAIN",
                input, output, "OK", System.nanoTime());
    }

    private static void record(RequestContext context, String module, String operation, String role,
                               Map<String, Object> input, Map<String, Object> output,
                               String outcome, long startedNanos) {
        context.recordModuleStep(new RuntimeModuleStep(module, operation, role, input, output,
                outcome, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    private Span startSpan(String name) {
        return tracer == null ? null : tracer.nextSpan().name(name).start();
    }

    private Tracer.SpanInScope scope(Span span) {
        return span == null ? null : tracer.withSpan(span);
    }

    private static void error(Span span, RuntimeException error) {
        if (span != null) {
            span.error(error);
        }
    }

    private static void end(Span span) {
        if (span != null) {
            span.end();
        }
    }
}
