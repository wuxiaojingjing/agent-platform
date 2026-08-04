package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.common.context.AgentProperties;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLint;
import com.huawei.finance.registry.asset.AssetStore;
import com.huawei.finance.registry.asset.ResponsePolicy;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.IndexRebuildPipeline;
import com.huawei.finance.intent.cache.DecisionCacheControl;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;
import com.huawei.finance.orchestrator.loop.LoopContracts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 运营控制台的后端。
 *
 * <p>挂在 {@code /internal} 下与面客接口分开：这些接口会吐出阈值、规则、提示词与最近的
 * 用户原话，任何一条都不该经公网直达。分路径是为了让网关那层能用一条前缀规则拦住整片，
 * 而不是逐个接口去配。
 *
 * <p>只做视图与转发，不含判定逻辑。这里一旦开始「顺手算一下健康度」，控制台就成了第二个
 * 真值来源，而它和引擎的口径迟早会分叉。
 */
@RestController
@RequestMapping("/internal/console")
@ConditionalOnProperty(prefix = "huawei.finance.mobile-banking.console", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);

    private final AssetStore store;
    private final EngineRegistry engines;
    private final IndexReadiness readiness;
    private final IndexRebuildPipeline indexPipeline;
    private final ConsoleMetrics metrics;
    private final RecentDecisions recent;
    private final AssetEditor editor;
    private final ConsoleProperties props;
    private final AgentProperties agentProperties;
    private final CollaborationTraceView collaboration;
    private final CacheInspector cacheInspector;
    private final PromptOptimizationView promptOptimization;
    private final AgentLoopRepository loops;
    private final DecisionCacheControl decisionCacheControl;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;

    @Autowired
    public ConsoleController(AssetStore store, EngineRegistry engines, IndexReadiness readiness,
                             IndexRebuildPipeline indexPipeline, ConsoleMetrics metrics,
                             RecentDecisions recent, AssetEditor editor, ConsoleProperties props,
                             AgentProperties agentProperties,
                             CollaborationTraceView collaboration,
                             CacheInspector cacheInspector,
                             PromptOptimizationView promptOptimization,
                             Optional<AgentLoopRepository> loops,
                             Optional<DecisionCacheControl> decisionCacheControl) {
        this.store = store;
        this.engines = engines;
        this.readiness = readiness;
        this.indexPipeline = indexPipeline;
        this.metrics = metrics;
        this.recent = recent;
        this.editor = editor;
        this.props = props;
        this.agentProperties = agentProperties;
        this.collaboration = collaboration;
        this.cacheInspector = cacheInspector;
        this.promptOptimization = promptOptimization;
        this.loops = loops == null ? null : loops.orElse(null);
        this.decisionCacheControl = decisionCacheControl == null
                ? null : decisionCacheControl.orElse(null);
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ConsoleController(AssetStore store, EngineRegistry engines, IndexReadiness readiness,
                             IndexRebuildPipeline indexPipeline, ConsoleMetrics metrics,
                             RecentDecisions recent, AssetEditor editor, ConsoleProperties props,
                             AgentProperties agentProperties) {
        this(store, engines, readiness, indexPipeline, metrics, recent, editor, props,
                agentProperties, null, null, store == null ? null : new PromptOptimizationView(store),
                Optional.empty(), Optional.empty());
    }

    public ConsoleController(AssetStore store, EngineRegistry engines, IndexReadiness readiness,
                             IndexRebuildPipeline indexPipeline, ConsoleMetrics metrics,
                             RecentDecisions recent, AssetEditor editor, ConsoleProperties props,
                             AgentProperties agentProperties,
                             CollaborationTraceView collaboration,
                             CacheInspector cacheInspector,
                             PromptOptimizationView promptOptimization,
                             Optional<AgentLoopRepository> loops) {
        this(store, engines, readiness, indexPipeline, metrics, recent, editor, props,
                agentProperties, collaboration, cacheInspector, promptOptimization, loops,
                Optional.empty());
    }

    /** 返回页面渲染和联调所需的运行参数，避免前端复制服务端配置。 */
    @GetMapping("/settings")
    public ConsoleSettings settings() {
        return ConsoleSettings.from(agentProperties.getId(), props);
    }

    /**
     * 总览。
     *
     * <p>{@code indexStale} 仍是这页上要紧的一位：为真意味着资产版本与索引版本不一致
     * （自动重建失败，或 {@code huawei.finance.agent.registry.rebuild-on-asset-change=false}）。
     * 正常保存路径下管道会同步重建，此位应为假。
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        AssetBundle bundle = engines.current().bundle();
        IndexReadiness.Snapshot index = readiness.get();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("assetVersion", bundle.assetVersion());
        view.put("capabilityCount", bundle.capabilities().size());
        view.put("strongRuleCount", bundle.strongRules().size());
        view.put("negativeRuleCount", bundle.negativeRules().size());
        view.put("writeEnabled", props.isWriteEnabled());

        Map<String, Object> indexView = new LinkedHashMap<>();
        indexView.put("state", index.state().name());
        indexView.put("indexName", index.indexName());
        indexView.put("assetVersion", index.assetVersion());
        indexView.put("vectorsIndexed", index.vectorsIndexed());
        indexView.put("documentCount", index.documentCount());
        indexView.put("searchable", index.searchable());
        indexView.put("semanticAvailable", index.semanticAvailable());
        indexView.put("stale", index.assetVersion() != null
                && !bundle.assetVersion().equals(index.assetVersion()));
        view.put("index", indexView);

        List<AssetLint.Finding> findings = AssetLint.inspect(bundle);
        view.put("lint", Map.of(
                "errors", findings.stream().filter(f -> f.severity() == AssetLint.Severity.ERROR).toList(),
                "warnings", findings.stream().filter(f -> f.severity() == AssetLint.Severity.WARN).toList()));
        return view;
    }

    @GetMapping("/capabilities")
    public List<CapabilityCard> capabilities() {
        return engines.current().bundle().capabilities();
    }

    @GetMapping("/capabilities/{id}")
    public ResponseEntity<CapabilityCard> capability(@PathVariable String id) {
        CapabilityCard card = engines.current().bundle().capability(id);
        return card == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(card);
    }

    /** 规则、融合参数与澄清配置。这三样决定出口怎么判，放一起是为了对着看。 */
    @GetMapping("/rules")
    public Map<String, Object> rules() {
        AssetBundle bundle = engines.current().bundle();
        return Map.of(
                "strongRules", bundle.strongRules(),
                "negativeRules", bundle.negativeRules(),
                "fusion", bundle.fusion(),
                "clarify", bundle.clarify());
    }

    @GetMapping("/response-policy")
    public ResponsePolicy responsePolicy() {
        return engines.current().bundle().responsePolicy();
    }

    @PutMapping("/response-policy")
    public ResponseEntity<Map<String, Object>> saveResponsePolicy(@RequestBody ResponsePolicy policy) {
        if (!props.isWriteEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "message", "控制台写入未开启；生产回复策略必须通过 Git/MR 发布"));
        }
        try {
            String yaml = yamlMapper.writeValueAsString(policy);
            AssetEditor.WriteResult result = editor.write("response-policy.yaml", yaml);
            return ResponseEntity.ok(Map.of(
                    "assetVersion", result.assetVersion(),
                    "policyVersion", engines.current().bundle().responsePolicy().getVersion(),
                    "findings", result.findings()));
        } catch (AssetEditor.AssetWriteRejected e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "message", e.getMessage(), "findings", e.findings()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/recent")
    public List<Map<String, Object>> recent() {
        return recent.snapshot().stream().map(entry -> {
            Map<String, Object> view = objectMapper.convertValue(entry,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            if (collaboration != null) {
                view.put("collaboration", collaboration.byTrace(entry.traceId()));
                view.put("planExecution", collaboration.latestPlan(
                        agentProperties.getId(), entry.sessionId()));
            }
            addLoopExecution(view, entry);
            return view;
        }).toList();
    }

    private void addLoopExecution(Map<String, Object> view, RecentDecisions.Entry entry) {
        if (loops == null || entry.taskId() == null || !entry.taskId().startsWith("loop-")) return;
        try {
            loops.find(props.getDefaultSpaceId(), agentProperties.getId(), entry.taskId())
                    .ifPresent(run -> view.put("loopExecution", loopExecution(run)));
        } catch (RuntimeException failure) {
            log.warn("Loop 运营投影读取失败 loop={} cause={}", entry.taskId(), failure.toString());
        }
    }

    private Map<String, Object> loopExecution(LoopContracts.Run run) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("loopId", run.loopId());
        execution.put("status", run.status().name());
        execution.put("reasonCode", loops.reasonCode(run.tenantId(), run.agentId(), run.loopId()).orElse(null));
        execution.put("iteration", run.iteration());
        execution.put("maxIterations", run.maxIterations());
        execution.put("stateVersion", run.version());
        execution.put("candidateIds", run.candidateIds());
        execution.put("updatedAt", run.updatedAt());
        execution.put("steps", loops.steps(run.tenantId(), run.agentId(), run.loopId()).stream()
                .map(ConsoleController::loopStep).toList());
        return execution;
    }

    private static Map<String, Object> loopStep(LoopContracts.Step step) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stepIndex", step.stepIndex());
        view.put("status", step.status().name());
        view.put("taskId", step.taskId());
        view.put("reasonCode", step.reasonCode());
        view.put("createdAt", step.createdAt());
        view.put("completedAt", step.completedAt());
        if (step.action() != null) {
            view.put("actionType", step.action().actionType().name());
            view.put("targetId", step.action().targetId());
            view.put("proposalReasonCode", step.action().proposalReasonCode());
        }
        if (step.observation() != null) {
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("status", step.observation().status().name());
            observation.put("sourceType", step.observation().sourceType());
            observation.put("sourceId", step.observation().sourceId());
            observation.put("reasonCode", step.observation().reasonCode());
            observation.put("failureClass", step.observation().failureClass());
            observation.put("retryable", step.observation().retryable());
            view.put("observation", observation);
        }
        return view;
    }

    /** Offline prompt optimizer inputs and generated candidates; never activates a candidate. */
    @GetMapping("/prompt-optimization")
    public PromptOptimizationView.Snapshot promptOptimization() {
        if (promptOptimization == null) {
            throw new IllegalStateException("提示词优化视图未装配");
        }
        return promptOptimization.snapshot();
    }

    /**
     * 浏览 Redis 里本平台缓存键（出口决策 / 轮次投影 / 亲和 / 锁）。
     *
     * <p>联调专用；键模式固定。decision 键是哈希，页面展示的是 RouteDecision JSON 不是原话。
     */
    @GetMapping("/cache")
    public Map<String, Object> cache(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "200") int limit) {
        if (cacheInspector == null) {
            return Map.of("available", false, "message", "CacheInspector 未装配", "entries", List.of());
        }
        return cacheInspector.snapshot(kind, limit);
    }

    /** 只控制问法出口决策缓存，不影响会话、任务和 Runtime 记录。 */
    @GetMapping("/decision-cache-control")
    public Map<String, Object> decisionCacheControl() {
        if (decisionCacheControl == null) {
            return Map.of("available", false, "enabled", false,
                    "message", "DecisionCacheControl 未装配");
        }
        return Map.of("available", true, "enabled", decisionCacheControl.enabled());
    }

    @PutMapping("/decision-cache-control")
    public ResponseEntity<Map<String, Object>> updateDecisionCacheControl(
            @RequestBody DecisionCacheControlRequest request) {
        if (!props.isWriteEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "控制台写入未开启，禁止切换问法缓存"));
        }
        if (decisionCacheControl == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "DecisionCacheControl 未装配"));
        }
        if (request == null || request.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "enabled 必填"));
        }
        boolean enabled = request.enabled();
        decisionCacheControl.setEnabled(enabled);
        long cleared = enabled ? 0 : decisionCacheControl.clear();
        return ResponseEntity.ok(Map.of(
                "available", true,
                "enabled", decisionCacheControl.enabled(),
                "cleared", cleared));
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> deleteCache(@RequestParam String key) {
        if (!props.isWriteEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "控制台写入未开启，禁止删缓存"));
        }
        if (cacheInspector == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "CacheInspector 未装配"));
        }
        try {
            boolean deleted = cacheInspector.delete(key);
            return ResponseEntity.ok(Map.of("deleted", deleted, "key", key));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    public record DecisionCacheControlRequest(Boolean enabled) {
    }

    @GetMapping("/assets/files")
    public List<AssetEditor.FileEntry> files() {
        return editor.list();
    }

    @GetMapping("/assets/file")
    public Map<String, String> file(@RequestParam String path) {
        return Map.of("path", path, "content", editor.read(path));
    }

    /**
     * 改资产并即时生效。
     *
     * <p>{@link AssetEditor#write} 触发 {@code AssetStore.reload}，管道同步重建索引。
     * {@code indexStale} 为真只剩重建失败或外部接管未建索引两种情况；成功保存后默认已跟上。
     */
    @PutMapping("/assets/file")
    public ResponseEntity<Map<String, Object>> save(@RequestBody SaveRequest request) {
        if (!props.isWriteEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "message", "控制台写入未开启。资产归 Git 管，生产上应经 MR 与 CI 改动；"
                            + "本地联调把 huawei.finance.mobile-banking.console.write-enabled 打开即可"));
        }
        try {
            AssetEditor.WriteResult result = editor.write(request.path(), request.content());
            IndexReadiness.Snapshot index = readiness.get();
            boolean indexInSync = index.searchable()
                    && result.assetVersion().equals(index.assetVersion());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assetVersion", result.assetVersion());
            body.put("findings", result.findings());
            body.put("indexStale", !indexInSync);
            body.put("indexState", index.state().name());
            body.put("indexDocumentCount", index.documentCount());
            body.put("vectorsIndexed", index.vectorsIndexed());
            return ResponseEntity.ok(body);
        } catch (AssetEditor.AssetWriteRejected e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "message", e.getMessage(), "findings", e.findings()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 强制重建索引并切别名。
     *
     * <p>同步执行。资产保存路径已会自动重建；此接口留给自动重建曾失败、或外部改了资产目录时补一刀。
     */
    @PostMapping("/assets/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        if (!props.isWriteEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "控制台写入未开启"));
        }
        AssetBundle bundle = store.current();
        IndexRebuildPipeline.Result result = indexPipeline.forceRebuild(bundle);
        if (result.outcome() == IndexRebuildPipeline.Outcome.FAILED) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "重建索引失败，检索仍在用旧索引：" + result.detail()));
        }
        IndexReadiness.Snapshot index = readiness.get();
        log.info("控制台触发重建索引完成 version={} 向量={}",
                bundle.assetVersion(), result.vectorsIndexed());
        return ResponseEntity.ok(Map.of(
                "assetVersion", bundle.assetVersion(),
                "vectorsIndexed", result.vectorsIndexed(),
                "documentCount", index.documentCount(),
                "state", index.state().name()));
    }

    public record SaveRequest(String path, String content) {
    }
}
