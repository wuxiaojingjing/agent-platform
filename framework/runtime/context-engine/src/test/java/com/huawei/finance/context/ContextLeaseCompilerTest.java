package com.huawei.finance.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.openjiuwen.core.context.token.SimpleTokenCounter;
import com.openjiuwen.core.context.token.TokenCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextLeaseCompilerTest {

    private final TokenCounter counter = new SimpleTokenCounter();

    /** 历史给多少给多少的假存储。 */
    private static final class StubStore implements TurnStore {
        private final List<ConversationTurn> turns = new ArrayList<>();
        private boolean broken;

        @Override
        public ConversationTurn append(ConversationTurn turn) {
            turns.add(turn);
            return turn;
        }

        @Override
        public List<ConversationTurn> recent(String tenantId, String agentId, String sessionId, int limit) {
            if (broken) {
                throw new ContextUnavailableException("库连不上", new RuntimeException());
            }
            return turns.size() <= limit ? List.copyOf(turns)
                    : List.copyOf(turns.subList(turns.size() - limit, turns.size()));
        }
    }

    private ContextLeaseCompiler compiler(StubStore store, int budget) {
        ContextProperties props = new ContextProperties();
        props.setBudgetTokens(budget);
        return new ContextLeaseCompiler(store, counter, props, new SimpleMeterRegistry());
    }

    private static ConversationTurn turn(long seq, String capability, Map<String, Object> facts) {
        return new ConversationTurn("agent.entry", "s1", seq, "t" + seq, "task" + seq, "查一下余额",
                Decision.EXECUTE_CAPABILITY, null, capability, Enums.ToolOutcome.SUCCEEDED,
                Enums.PendingAction.NONE, List.of(), facts, Instant.now());
    }

    @Test
    @DisplayName("token 计数对中文必须有意义：整个 4K 预算都建立在它身上")
    void tokenCounterHandlesChinese() {
        // 这条守的不是 OJ 的实现细节，是一个假设：换计数器时若中文退化成
        // 「按空格切词」，一整段中文会被算成 1 个 token，预算从此形同虚设，
        // 而表现只是「从来不裁剪」——没有任何报错
        int oneChar = counter.count("余");
        int hundredChars = counter.count("余额".repeat(50));

        assertThat(oneChar).as("单个汉字不该算成 0 个 token").isPositive();
        assertThat(hundredChars)
                .as("100 个汉字要显著多于 1 个字，否则中文根本没被计数")
                .isGreaterThan(oneChar * 10);
    }

    @Test
    @DisplayName("预算够用时不裁剪，历史工具结论按时间正序给出")
    void keepsEverythingWithinBudget() {
        StubStore store = new StubStore();
        store.append(turn(0, "cap.balance", Map.of("account", "8821")));
        store.append(turn(1, "cap.bill", Map.of("month", "7")));

        ContextLease lease = compiler(store, 4096)
                .compile("s1", "查账单", Map.of(), List.of());

        assertThat(lease.wasTrimmed()).isFalse();
        assertThat(lease.toolConclusions()).extracting(ContextLease.ToolConclusion::capabilityId)
                .containsExactly("cap.balance", "cap.bill");
        assertThat(lease.allowsSideEffects()).isTrue();
        assertThat(lease.usedTokens()).isPositive().isLessThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("执行租约与意图投影来自同一状态版本，并给事实稳定引用")
    void compilesIntentProjectionAtSameVersion() {
        StubStore store = new StubStore();
        store.append(turn(0, "cap.account.balance.query", Map.of(
                "cards", List.of(Map.of("index", 1), Map.of("index", 2)),
                "availableBalance", "8000")));

        ContextCompilation compiled = compiler(store, 4096).compileContext(
                "agent.entry", "s1", "第二张呢", Map.of(), List.of());

        assertThat(compiled.intentContext().stateVersion())
                .isEqualTo(compiled.lease().stateVersion()).isEqualTo(1);
        assertThat(compiled.intentContext().evidenceRefs())
                .contains("fact:accounts", "fact:balance-snapshot", "turn:s1#0:utterance");
        assertThat(compiled.intentContext().conversationHistory())
                .extracting(message -> message.get("role"))
                .containsExactly("user", "assistant", "tool");
        assertThat(compiled.intentContext().conversationHistory().get(0).get("content"))
                .isEqualTo("查一下余额");
        assertThat(String.valueOf(compiled.intentContext().conversationHistory().get(2).get("content")))
                .contains("availableBalance=8000", "cards=");
    }

    @Test
    @DisplayName("完整消息按原顺序进入模型，工具调用与结果共享 callId，助手展示内容不重建")
    void projectsExactOrderedToolTranscript() {
        StubStore store = new StubStore();
        List<ConversationTurn.Message> messages = List.of(
                new ConversationTurn.Message("m-user", ConversationTurn.MessageRole.USER,
                        ConversationTurn.MessageType.TEXT, null, null, "查工资卡余额",
                        Map.of(), true, true),
                new ConversationTurn.Message("m-call", ConversationTurn.MessageRole.ASSISTANT,
                        ConversationTurn.MessageType.TOOL_CALL, "call-42", "cap.balance",
                        null, Map.of("parameters", Map.of("cardRef", "card-2")), false, true),
                new ConversationTurn.Message("m-result", ConversationTurn.MessageRole.TOOL,
                        ConversationTurn.MessageType.TOOL_RESULT, "call-42", "cap.balance",
                        null, Map.of("output", Map.of("balance", "8000")), false, true),
                new ConversationTurn.Message("m-assistant", ConversationTurn.MessageRole.ASSISTANT,
                        ConversationTurn.MessageType.TEXT, null, null, "工资卡可用余额为 8,000 元。",
                        Map.of("responsePhase", "FINAL",
                                "displaySlots", Map.of("balance", "8,000 元"),
                                "actions", List.of(Map.of("event", "DETAIL", "label", "查看明细"))),
                        true, true));
        store.append(new ConversationTurn("tenant-a", "agent.entry", "s1", 0, "trace-1", "task-1",
                "查工资卡余额", Decision.EXECUTE_CAPABILITY, null, "cap.balance",
                Enums.ToolOutcome.SUCCEEDED, Enums.PendingAction.NONE, List.of(),
                Map.of("balance", "8000"), Instant.now(), messages));

        List<Map<String, Object>> history = compiler(store, 4096).compileContext(
                "tenant-a", "agent.entry", "s1", "余额是多少", Map.of(), List.of())
                .intentContext().conversationHistory();

        assertThat(history).extracting(item -> item.get("role"))
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(history).extracting(item -> item.get("type"))
                .containsExactly("TEXT", "TOOL_CALL", "TOOL_RESULT", "TEXT");
        assertThat(history.get(1)).containsEntry("callId", "call-42");
        assertThat(history.get(2)).containsEntry("callId", "call-42");
        assertThat(history.get(3)).containsEntry("text", "工资卡可用余额为 8,000 元。");
        assertThat(String.valueOf(history.get(3).get("data")))
                .contains("查看明细", "8,000 元");
    }

    @Test
    @DisplayName("任何用户可见但模型不可见的消息在契约层直接拒绝")
    void rejectsUserVisibleModelHiddenMessage() {
        assertThatThrownBy(() -> new ConversationTurn.Message("m1",
                ConversationTurn.MessageRole.ASSISTANT, ConversationTurn.MessageType.TEXT,
                null, null, "用户能看到", Map.of(), true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_VISIBLE_MESSAGE_MUST_BE_MODEL_VISIBLE");
    }

    @Test
    @DisplayName("结构化按钮轮即使没有 query，也必须把用户实际看到的助手回复交给下一轮模型")
    void keepsMessageOnlyStructuredActionTurn() {
        StubStore store = new StubStore();
        List<ConversationTurn.Message> messages = List.of(
                new ConversationTurn.Message("action", ConversationTurn.MessageRole.USER,
                        ConversationTurn.MessageType.TEXT, null, null, "",
                        Map.of("action", Map.of("event", "CONFIRM")), true, true),
                new ConversationTurn.Message("assistant", ConversationTurn.MessageRole.ASSISTANT,
                        ConversationTurn.MessageType.TEXT, null, null, "已确认执行。",
                        Map.of("responsePhase", "ACK"), true, true));
        store.append(new ConversationTurn("tenant-a", "agent.entry", "s1", 0, "trace-action", null,
                "", Decision.RESUME_TASK, null, null, null, Enums.PendingAction.NONE,
                List.of(), Map.of(), Instant.now(), messages));

        assertThat(compiler(store, 4096).compileContext(
                "tenant-a", "agent.entry", "s1", "下一步", Map.of(), List.of())
                .intentContext().conversationHistory())
                .extracting(item -> item.get("text"))
                .containsExactly("", "已确认执行。");
    }

    @Test
    @DisplayName("Runtime 附加证据与历史共用 token 预算")
    void currentRuntimeEvidenceSharesBudget() {
        ContextEvidence oversized = new ContextEvidence("runtime:loop:1",
                ContextEvidence.Kind.RUNTIME_STATE,
                Map.of("runtimeFacts", "结果".repeat(500)), null, "loop-1", null,
                Instant.now(), null, ContextEvidence.Sensitivity.SENSITIVE);

        ContextCompilation compiled = compiler(new StubStore(), 100).compileContext(
                "tenant-a", "agent.entry", "s1", "继续", Map.of(), List.of(), List.of(oversized));

        assertThat(compiled.intentContext().evidenceRefs()).doesNotContain("runtime:loop:1");
        assertThat(compiled.lease().trimmed()).extracting(ContextLease.TrimmedItem::ref)
                .contains("runtime:loop:1");
        assertThat(compiled.lease().usedTokens()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("委托事实的稳定别名继承权威来源，供域解析器安全回查")
    void canonicalAliasPreservesDelegatedAuthority() {
        StubStore store = new StubStore();
        Instant observedAt = Instant.parse("2026-08-01T09:00:00Z");
        Instant validUntil = observedAt.plusSeconds(30);
        List<Map<String, Object>> cards = List.of(Map.of("index", 1), Map.of("index", 2));
        ContextEvidence delegated = new ContextEvidence(
                "fact:agent.account:cards", ContextEvidence.Kind.TOOL_FACT,
                Map.of("cards", cards), "agent.account", "remote-account-task",
                "remote-turn", observedAt, validUntil, ContextEvidence.Sensitivity.SENSITIVE);
        store.append(turn(0, "cap.account.balance.query", Map.of(
                "cards", cards,
                "contextDeltaFacts", List.of(delegated))));

        ContextCompilation compiled = compiler(store, 4096).compileContext(
                "agent.entry", "s1", "第二张呢", Map.of(), List.of());

        ContextEvidence alias = compiled.intentContext().evidence().stream()
                .filter(item -> "fact:accounts".equals(item.ref()))
                .findFirst().orElseThrow();
        assertThat(alias.sourceAgentId()).isEqualTo("agent.account");
        assertThat(alias.sourceTaskId()).isEqualTo("remote-account-task");
        assertThat(alias.observedAt()).isEqualTo(observedAt);
        assertThat(alias.validUntil()).isEqualTo(validUntil);
        assertThat(alias.value()).containsEntry("cards", cards);
    }

    @Test
    @DisplayName("超预算时裁旧留新：用户追问的总是最近那条")
    void trimsOldestFirstAndReportsWhatWasCut() {
        StubStore store = new StubStore();
        for (long i = 0; i < 8; i++) {
            store.append(turn(i, "cap.no" + i, Map.of("filler", "账户余额明细".repeat(10))));
        }

        ContextLease lease = compiler(store, 200)
                .compile("s1", "再查一次", Map.of(), List.of());

        assertThat(lease.wasTrimmed()).isTrue();
        assertThat(lease.usedTokens())
                .as("裁完仍超预算说明裁剪只是记了账没真丢东西")
                .isLessThanOrEqualTo(200);

        List<String> kept = lease.toolConclusions().stream()
                .map(ContextLease.ToolConclusion::capabilityId).toList();
        List<String> cut = lease.trimmed().stream()
                .map(ContextLease.TrimmedItem::ref).toList();

        assertThat(kept).as("留下的必须是最近的").contains("cap.no7");
        assertThat(cut).as("裁掉的必须最旧的那批").contains("turn:s1#0");
        assertThat(lease.trimmed()).allSatisfy(item -> {
            assertThat(item.tokens()).as("裁剪项要能说清省下多少").isPositive();
            assertThat(item.reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("已确认事实与待办项永不被裁：裁了就等于忘记用户确认过什么")
    void neverTrimsConfirmedFactsOrPendingItems() {
        StubStore store = new StubStore();
        for (long i = 0; i < 20; i++) {
            store.append(turn(i, "cap.no" + i, Map.of("filler", "历史内容".repeat(20))));
        }
        Map<String, Object> confirmed = Map.of("payee", "张三", "amount", "1000");
        List<ContextLease.PendingItem> pending = List.of(
                new ContextLease.PendingItem("amount", Enums.PendingAction.CONFIRM, List.of()));

        ContextLease lease = compiler(store, 220)
                .compile("s1", "给张三转 1000", confirmed, pending);

        assertThat(lease.trustworthy()).isTrue();
        assertThat(lease.confirmedFacts()).containsAllEntriesOf(confirmed);
        assertThat(lease.pendingItems()).isEqualTo(pending);
        assertThat(lease.wasTrimmed()).as("挤掉的应该是历史").isTrue();
    }

    @Test
    @DisplayName("历史读不到就签降级租约，而不是当成空历史继续办")
    void unreadableHistoryStopsSideEffects() {
        StubStore store = new StubStore();
        store.broken = true;

        ContextLease lease = compiler(store, 4096)
                .compile("s1", "确认转账", Map.of(), List.of());

        assertThat(lease.trustworthy()).isFalse();
        assertThat(lease.allowsSideEffects())
                .as("这正是 v0.7 §4.0「上下文异常时有副作用停止」的落点")
                .isFalse();
        assertThat(lease.goal()).as("话术仍要能说清用户在办什么").isEqualTo("确认转账");
    }

    @Test
    @DisplayName("光已确认事实就超预算也判降级：装不下就别假装装下了")
    void oversizedConfirmedFactsAlsoDegrade() {
        ContextLease lease = compiler(new StubStore(), 20)
                .compile("s1", "转账", Map.of("note", "备注".repeat(200)), List.of());

        assertThat(lease.trustworthy()).isFalse();
    }

    @Test
    @DisplayName("租约过期后不许再动账：任务态可能已被另一路请求改写")
    void expiredLeaseStopsSideEffects() {
        ContextLease lease = new ContextLease("l1", "s1", "转账", Map.of(), List.of(), List.of(),
                4096, 10, List.of(), true, 1L, Instant.now().minusSeconds(1));

        assertThat(lease.trustworthy()).isTrue();
        assertThat(lease.allowsSideEffects()).isFalse();
    }

    @Test
    @DisplayName("超预算的租约不得签发：构造期就拦，别指望调用方去查")
    void leaseCannotBeIssuedOverBudget() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new ContextLease("l1", "s1", "转账", Map.of(), List.of(), List.of(),
                        100, 101, List.of(), true, 1L, Instant.now().plusSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("裁剪没有真正执行");
    }
}
