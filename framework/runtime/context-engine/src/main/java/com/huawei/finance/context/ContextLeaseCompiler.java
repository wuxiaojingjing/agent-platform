package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.obs.AgentMetrics;
import com.openjiuwen.core.context.token.TokenCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把会话历史编译成本轮最小工作集（FP-28）。
 *
 * <p><b>裁剪顺序按「重看一遍还能不能补回来」排</b>，不是按时间远近：
 *
 * <ol>
 *   <li>本轮目标与已确认事实：永不裁。裁了就等于忘记用户确认过什么，
 *       而这份租约还要用来判断能不能动账。</li>
 *   <li>待办项：永不裁。它是续轮短路的输入，没有它「确认」二字无从解释。</li>
 *   <li>工具结论：从旧到新裁。近的那条通常正是用户在追问的。</li>
 *   <li>历史用户原话：最先裁，也裁得最狠。它是唯一的自由文本，占 token 最多，
 *       而它承载的事实早已被抽成 {@code facts} 了。</li>
 * </ol>
 *
 * <p><b>为什么不用 OJ 的压缩器</b>：{@code RoundLevelCompressor} 那几个都带
 * {@code ModelRequestConfig}，压缩本身要花一次模型往返。同步面客链路上再加这一次，
 * 延迟与单价都难看，所以这里只复用 OJ 的 {@link TokenCounter}——计数口径必须与执行侧同源，
 * 否则「预算内」是自说自话。带模型的压缩留给 B 线慢路径。
 */
public class ContextLeaseCompiler {

    private static final Logger log = LoggerFactory.getLogger(ContextLeaseCompiler.class);

    private final TurnStore turns;
    private final TokenCounter tokenCounter;
    private final ContextProperties props;
    private final MeterRegistry meterRegistry;

    public ContextLeaseCompiler(TurnStore turns, TokenCounter tokenCounter,
                                ContextProperties props, MeterRegistry meterRegistry) {
        this.turns = turns;
        this.tokenCounter = tokenCounter;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 编译本轮租约。
     *
     * <p>永远返回一份租约，读不到历史时返回不可信的降级租约。调用方无需 catch，
     * 但必须看 {@link ContextLease#allowsSideEffects()}。
     *
     * @param goal 本轮目标
     * @param confirmedFacts 任务态里已确认的槽位，来自强一致的 Postgres 任务表
     * @param pendingItems 当前待办
     */
    public ContextLease compile(String sessionId, String goal,
                                Map<String, Object> confirmedFacts,
                                List<ContextLease.PendingItem> pendingItems) {
        return compile(com.huawei.finance.common.context.RequestContext.AGENT_ENTRY, sessionId, goal,
                confirmedFacts, pendingItems);
    }

    public ContextLease compile(String agentId, String sessionId, String goal,
                                Map<String, Object> confirmedFacts,
                                List<ContextLease.PendingItem> pendingItems) {
        return compileContext(agentId, sessionId, goal, confirmedFacts, pendingItems).lease();
    }

    public ContextLease compile(String tenantId, String agentId, String sessionId, String goal,
                                Map<String, Object> confirmedFacts,
                                List<ContextLease.PendingItem> pendingItems) {
        return compileContext(tenantId, agentId, sessionId, goal, confirmedFacts, pendingItems).lease();
    }

    /** Reads history once and signs both execution and intent projections at the same version. */
    public ContextCompilation compileContext(String agentId, String sessionId, String goal,
                                             Map<String, Object> confirmedFacts,
                                             List<ContextLease.PendingItem> pendingItems) {
        return compileContext(com.huawei.finance.common.context.RequestContext.SPACE_UNSCOPED,
                agentId, sessionId, goal, confirmedFacts, pendingItems);
    }

    public ContextCompilation compileContext(String tenantId, String agentId, String sessionId, String goal,
                                             Map<String, Object> confirmedFacts,
                                             List<ContextLease.PendingItem> pendingItems) {
        return compileContext(tenantId, agentId, sessionId, goal, confirmedFacts, pendingItems, List.of());
    }

    /** Current Runtime and delegated evidence share the same token budget as conversation history. */
    public ContextCompilation compileContext(String tenantId, String agentId, String sessionId, String goal,
                                             Map<String, Object> confirmedFacts,
                                             List<ContextLease.PendingItem> pendingItems,
                                             List<ContextEvidence> currentEvidence) {
        Instant expiresAt = Instant.now().plus(props.getLeaseTtl());
        List<ConversationTurn> history;
        try {
            history = turns.recent(tenantId, agentId, sessionId, props.getMaxTurns());
        } catch (ContextUnavailableException e) {
            log.error("上下文读取失败，签发降级租约（本轮禁止有副作用操作）session={} cause={}",
                    sessionId, e.toString());
            meterRegistry.counter(AgentMetrics.CONTEXT_DEGRADED).increment();
            return degraded(sessionId, goal, expiresAt);
        }

        Budget budget = new Budget(props.getBudgetTokens());
        List<ContextLease.TrimmedItem> trimmed = new ArrayList<>();

        // 不可裁的先记账。它们超预算也照留——超了说明预算配小了，
        // 那是配置问题，不能靠丢掉已确认事实来掩盖
        budget.charge(text(goal));
        Map<String, Object> facts = new LinkedHashMap<>(confirmedFacts);
        budget.charge(text(facts));
        budget.charge(text(pendingItems));

        // 不可裁的部分自己就超了预算，说明这份上下文没法如实装进窗口。
        // 此时既不能截断（截掉的是已确认事实），也不能假装装下了，只能判上下文异常
        if (budget.overspent()) {
            log.error("目标与已确认事实合计 {} tokens 已超 {} 预算，无法在不丢事实的前提下编译。"
                            + "签发降级租约，本轮禁止有副作用操作 session={}",
                    budget.used(), props.getBudgetTokens(), sessionId);
            meterRegistry.counter(AgentMetrics.CONTEXT_DEGRADED).increment();
            return degraded(sessionId, goal, expiresAt);
        }

        List<ContextEvidence> boundedCurrentEvidence = new ArrayList<>();
        for (ContextEvidence item : currentEvidence == null ? List.<ContextEvidence>of() : currentEvidence) {
            int cost = tokens(text(item));
            if (budget.fits(cost)) {
                budget.charge(cost);
                boundedCurrentEvidence.add(item);
            } else {
                trimmed.add(new ContextLease.TrimmedItem(item.ref(),
                        "超出 " + props.getBudgetTokens() + " token 预算", cost));
            }
        }

        List<ConclusionEntry> conclusionEntries =
                collectConclusions(history, budget, trimmed);
        List<ContextLease.ToolConclusion> conclusions = conclusionEntries.stream()
                .map(ConclusionEntry::conclusion).toList();
        Map<String, ContextEvidence> evidenceByRef = new LinkedHashMap<>();
        boundedCurrentEvidence.forEach(item -> evidenceByRef.put(item.ref(), item));
        collectEvidence(history, conclusionEntries, confirmedFacts, budget, trimmed)
                .forEach(item -> evidenceByRef.putIfAbsent(item.ref(), item));
        List<ContextEvidence> evidence = List.copyOf(evidenceByRef.values());

        if (!trimmed.isEmpty()) {
            meterRegistry.counter(AgentMetrics.CONTEXT_TRIMMED).increment(trimmed.size());
            log.info("上下文裁剪 session={} 裁掉={} 项 省下={} tokens 余额={}",
                    sessionId, trimmed.size(),
                    trimmed.stream().mapToInt(ContextLease.TrimmedItem::tokens).sum(),
                    props.getBudgetTokens() - budget.used());
        }

        // 版本取「已落盘的轮数」，不另设计数器。它天然随每一轮递增，
        // 且与租约看到的历史严格同源：拿着 version=7 的租约却已经有第 8 轮落盘，
        // 就是「签发后状态变了」，无需再维护一个可能与历史不同步的版本字段
        long stateVersion = history.isEmpty() ? 0 : history.get(history.size() - 1).seq() + 1;

        ContextLease lease = new ContextLease(UUID.randomUUID().toString(), sessionId, goal, facts,
                pendingItems, conclusions, props.getBudgetTokens(), budget.used(),
                trimmed, true, stateVersion, expiresAt);
        IntentContext intentContext = new IntentContext(
                lease.leaseId(), sessionId, goal, stateVersion, true, expiresAt,
                facts, evidence, trimmed.size());
        return new ContextCompilation(lease, intentContext);
    }

    private static ContextCompilation degraded(String sessionId, String goal, Instant expiresAt) {
        return new ContextCompilation(ContextLease.degraded(sessionId, goal, expiresAt),
                IntentContext.degraded(sessionId, goal, expiresAt));
    }

    /**
     * 从新到旧收工具结论，收到装不下为止；装不下的逐条记进裁剪清单。
     *
     * <p>倒序遍历再反转，而不是正序遍历后截断：预算耗尽时该丢的是最旧的那批，
     * 正序遍历会先把最旧的装满，新的一条都进不来。
     */
    private List<ConclusionEntry> collectConclusions(
            List<ConversationTurn> history, Budget budget,
            List<ContextLease.TrimmedItem> trimmed) {

        Deque<ConclusionEntry> kept = new ArrayDeque<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationTurn turn = history.get(i);
            if (!turn.hasToolConclusion()) {
                continue;
            }
            ContextLease.ToolConclusion conclusion = new ContextLease.ToolConclusion(
                    turn.capabilityId(), turn.outcome(), turn.pending(), turn.facts());
            int cost = tokens(text(conclusion));
            if (budget.fits(cost)) {
                budget.charge(cost);
                kept.addFirst(new ConclusionEntry(turn, conclusion));
            } else {
                trimmed.add(new ContextLease.TrimmedItem(
                        ref(turn), "超出 " + props.getBudgetTokens() + " token 预算", cost));
            }
        }
        return List.copyOf(kept);
    }

    private List<ContextEvidence> collectEvidence(
            List<ConversationTurn> history, List<ConclusionEntry> conclusions,
            Map<String, Object> confirmedFacts, Budget budget,
            List<ContextLease.TrimmedItem> trimmed) {
        Map<String, ContextEvidence> selected = new LinkedHashMap<>();

        for (Map.Entry<String, Object> fact : confirmedFacts.entrySet()) {
            String ref = "task.confirmed." + fact.getKey();
            selected.put(ref, new ContextEvidence(ref, ContextEvidence.Kind.CONFIRMED_INPUT,
                    Map.of("name", fact.getKey(), "value", fact.getValue()), null, null,
                    null, null, null, ContextEvidence.Sensitivity.SENSITIVE));
        }

        for (ConclusionEntry entry : conclusions) {
            ConversationTurn turn = entry.turn();
            Map<String, ContextEvidence> delegatedByKey = collectDelegatedEvidence(turn, selected);
            for (Map.Entry<String, Object> fact : turn.facts().entrySet()) {
                if (isContextMetadata(fact.getKey())) continue;
                String factRef = factRef(turn.capabilityId(), fact.getKey());
                ContextEvidence delegated = delegatedByKey.get(fact.getKey());
                selected.put(factRef, new ContextEvidence(factRef, ContextEvidence.Kind.TOOL_FACT,
                        Map.of(fact.getKey(), fact.getValue()),
                        delegated == null ? turn.agentId() : delegated.sourceAgentId(),
                        delegated == null ? turn.taskId() : delegated.sourceTaskId(),
                        ref(turn), delegated == null ? turn.at() : delegated.observedAt(),
                        delegated == null ? null : delegated.validUntil(),
                        delegated == null ? ContextEvidence.Sensitivity.SENSITIVE
                                : delegated.sensitivity()));
            }
        }

        Deque<ContextEvidence> utterances = new ArrayDeque<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationTurn turn = history.get(i);
            List<Map<String, Object>> storedMessages = turn.messages().stream()
                    .filter(ConversationTurn.Message::modelVisible)
                    .map(ContextLeaseCompiler::messageView).toList();
            if ((turn.userText() == null || turn.userText().isBlank()) && storedMessages.isEmpty()) continue;
            String evidenceRef = ref(turn) + ":utterance";
            int cost = tokens(text(storedMessages.isEmpty() ? turn.userText() : storedMessages));
            if (budget.fits(cost)) {
                budget.charge(cost);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("text", turn.userText() == null ? "" : turn.userText());
                if (turn.decision() != null) value.put("decision", turn.decision().name());
                if (turn.capabilityId() != null) value.put("capabilityId", turn.capabilityId());
                if (turn.outcome() != null) value.put("outcome", turn.outcome().name());
                value.put("pending", turn.pending().name());
                if (!turn.pendingOptions().isEmpty()) {
                    value.put("pendingOptions", turn.pendingOptions());
                }
                if (!storedMessages.isEmpty()) value.put("messages", storedMessages);
                utterances.addFirst(new ContextEvidence(evidenceRef, ContextEvidence.Kind.USER_TURN,
                        value, turn.agentId(), turn.taskId(), ref(turn), turn.at(), null,
                        ContextEvidence.Sensitivity.SENSITIVE));
            } else {
                trimmed.add(new ContextLease.TrimmedItem(evidenceRef,
                        "超出 " + props.getBudgetTokens() + " token 预算", cost));
            }
        }
        utterances.forEach(item -> selected.put(item.ref(), item));
        return List.copyOf(selected.values());
    }

    private static Map<String, ContextEvidence> collectDelegatedEvidence(
            ConversationTurn turn, Map<String, ContextEvidence> selected) {
        Map<String, ContextEvidence> byKey = new LinkedHashMap<>();
        Object raw = turn.facts().get("contextDeltaFacts");
        if (!(raw instanceof List<?> list)) return byKey;
        for (Object item : list) {
            try {
                ContextEvidence evidence = item instanceof ContextEvidence typed
                        ? typed : ContractJson.mapper().convertValue(item, ContextEvidence.class);
                selected.put(evidence.ref(), evidence);
                if (evidence.value().size() == 1) {
                    byKey.put(evidence.value().keySet().iterator().next(), evidence);
                }
            } catch (RuntimeException ignored) {
                // Malformed delegated context never becomes a local fact.
            }
        }
        return byKey;
    }

    private static boolean isContextMetadata(String key) {
        return "contextDeltaFacts".equals(key) || "contextDeltaRefs".equals(key)
                || "contextDeltaBaseVersion".equals(key) || "a2aDelegationId".equals(key)
                || "targetTaskId".equals(key) || "principalVerified".equals(key)
                || "invocationOrigin".equals(key) || "intentPath".equals(key);
    }

    private static String factRef(String capabilityId, String key) {
        if ("cards".equals(key)) return "fact:accounts";
        if ("availableBalance".equals(key) || "balance".equals(key)) {
            return "fact:balance-snapshot";
        }
        String capability = capabilityId == null ? "unknown" : capabilityId.replaceAll("[^a-zA-Z0-9._-]", "-");
        String normalizedKey = key == null ? "unknown" : key.replaceAll("[^a-zA-Z0-9._-]", "-");
        return "fact:" + capability + ":" + normalizedKey;
    }

    private static Map<String, Object> messageView(ConversationTurn.Message source) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("role", source.role().name().toLowerCase(java.util.Locale.ROOT));
        view.put("type", source.type().name());
        view.put("ref", source.messageId());
        if (source.callId() != null) view.put("callId", source.callId());
        if (source.name() != null) view.put("name", source.name());
        if (source.text() != null) view.put("text", source.text());
        if (!source.data().isEmpty()) view.put("data", source.data());
        view.put("userVisible", source.userVisible());
        view.put("modelVisible", source.modelVisible());
        return Map.copyOf(view);
    }

    private record ConclusionEntry(ConversationTurn turn, ContextLease.ToolConclusion conclusion) {
    }

    private static String ref(ConversationTurn turn) {
        return "turn:" + turn.sessionId() + "#" + turn.seq();
    }

    private static String text(Object value) {
        return String.valueOf(value);
    }

    private int tokens(String text) {
        return tokenCounter.count(text);
    }

    /** 预算记账。单独一个类，是为了让「记了没记」在读代码时一眼看得见。 */
    private final class Budget {
        private final int limit;
        private int used;

        Budget(int limit) {
            this.limit = limit;
        }

        void charge(String text) {
            charge(tokens(text));
        }

        void charge(int cost) {
            used += cost;
        }

        boolean fits(int cost) {
            return used + cost <= limit;
        }

        boolean overspent() {
            return used > limit;
        }

        int used() {
            return used;
        }
    }

    /** 供装配处读取的默认租期，避免调用方各自猜。 */
    public Duration leaseTtl() {
        return props.getLeaseTtl();
    }
}
