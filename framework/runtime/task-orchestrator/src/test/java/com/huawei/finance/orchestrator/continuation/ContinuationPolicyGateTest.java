package com.huawei.finance.orchestrator.continuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContinuationPolicyGateTest {
    private final ContinuationPolicyGate gate = new ContinuationPolicyGate(.85, .95);

    @Test void rejectsCandidateEventsOutsideRuntimeAllowList() {
        Context context = context(List.of(Event.FILL_SLOT));
        Resolution input = new Resolution(Event.CONFIRM, "task-1", Map.of(), null, .99, "MODEL");
        assertThat(gate.validate("确认执行", null, context, input).event()).isEqualTo(Event.UNRESOLVED);
    }

    @Test void acceptsOnlyWhitelistedSlotValues() {
        Context context = context(List.of(Event.FILL_SLOT));
        Resolution input = new Resolution(Event.FILL_SLOT, "task-1", Map.of("cardType", "信用卡"),
                null, .9, "MODEL");
        assertThat(gate.validate("信用卡", null, context, input).slotUpdates())
                .containsExactly(Map.entry("cardType", "信用卡"));
    }

    @Test void naturalLanguageEventBindsToTheUniqueForegroundWithoutCopyingRuntimeId() {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "opaque-runtime-id", "CLARIFY_PENDING",
                new PendingInteraction("SLOT", "q1", "payee", List.of()),
                List.of(Event.FILL_SLOT), Map.of("payee", List.of("*")),
                "转账", 1, SwitchMode.ALLOW_SWITCH);
        Context context = new Context(snapshot, List.of());

        Resolution result = gate.validate("张三", null, context,
                new Resolution(Event.FILL_SLOT, null, Map.of("payee", "张三"),
                        null, .99, "MODEL_SLOT_VALUE"));

        assertThat(result.event()).isEqualTo(Event.FILL_SLOT);
        assertThat(result.targetRef()).isEqualTo("opaque-runtime-id");
        assertThat(result.slotUpdates()).containsExactly(Map.entry("payee", "张三"));
    }

    @Test void r2NaturalLanguageNeedsModelDeclaredExplicitActionSemantics() {
        Context context = context(List.of(Event.CONFIRM));
        Resolution ambiguous = new Resolution(Event.CONFIRM, "task-1", Map.of(), null, .99,
                "MODEL", ConfirmationStrength.NONE);
        Resolution explicit = new Resolution(Event.CONFIRM, "task-1", Map.of(), null, .99,
                "MODEL", ConfirmationStrength.EXPLICIT_ACTION);

        assertThat(gate.validate("好的", null, context, ambiguous).event()).isEqualTo(Event.UNRESOLVED);
        assertThat(gate.validate("执行这笔资金操作", null, context, explicit).event())
                .isEqualTo(Event.CONFIRM);
    }

    @Test void rejectsStructuredActionWithStaleRuntimeVersion() {
        Context context = context(List.of(Event.REVIEW_ACCEPT));
        StructuredAction stale = new StructuredAction("REVIEW_ACCEPT", "task-1", 9);
        Resolution input = new Resolution(Event.REVIEW_ACCEPT, "task-1", Map.of(), null, 1,
                "STRUCTURED_ACTION");

        assertThat(gate.validate("", stale, context, input).event()).isEqualTo(Event.UNRESOLVED);
        assertThat(gate.validate("", stale, context, input).reasonCode()).isEqualTo("STALE_ACTION");
    }

    @Test void pendingSwitchUsesTheSameGateForNaturalAndStructuredInput() {
        PendingSwitchView pending = new PendingSwitchView("switch-1", 4,
                List.of(Event.SWITCH_ACCEPT, Event.SWITCH_REJECT));
        Context context = new Context(context(List.of(Event.FILL_SLOT)).foreground(), List.of(), pending);
        Resolution natural = new Resolution(Event.SWITCH_ACCEPT, "switch-1", Map.of(), null, .9, "MODEL");
        StructuredAction stale = new StructuredAction("SWITCH_ACCEPT", "switch-1", 3);

        assertThat(gate.validate("切换", null, context, natural).event()).isEqualTo(Event.SWITCH_ACCEPT);
        assertThat(gate.validate("", stale, context, natural).reasonCode()).isEqualTo("STALE_ACTION");
    }

    @Test void newGoalSemanticsAreModelOwnedButRuntimeBindingIsDeterministic() {
        Context context = context(List.of(Event.FILL_SLOT, Event.SWITCH_TO_NEW_GOAL));
        Resolution modelDecision = new Resolution(Event.SWITCH_TO_NEW_GOAL, null, Map.of(),
                "先查信用卡账单", .96, "MODEL_NEW_GOAL");

        Resolution result = gate.validate("先查信用卡账单", null, context, modelDecision);

        assertThat(result.event()).isEqualTo(Event.SWITCH_TO_NEW_GOAL);
        assertThat(result.targetRef()).isEqualTo("task-1");
        assertThat(result.newGoalSpan()).isEqualTo("先查信用卡账单");
    }

    @Test void fourthSuspendedTaskIsExplainedBeforeSwitchMutation() {
        PendingSwitchView pending = new PendingSwitchView("switch-1", 4,
                List.of(Event.SWITCH_ACCEPT, Event.SWITCH_REJECT));
        Snapshot suspended = context(List.of(Event.FILL_SLOT)).foreground();
        Context context = new Context(suspended, List.of(suspended, suspended, suspended), pending);
        Resolution candidate = new Resolution(Event.SWITCH_ACCEPT, "switch-1", Map.of(), null, .99, "MODEL");

        Resolution result = gate.validate("切换", null, context, candidate);

        assertThat(result.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(result.reasonCode()).isEqualTo("SUSPENDED_TASK_LIMIT");
    }

    @Test void correctionMayChangeOnlyRuntimeDeclaredSlotsAndNeverConfirms() {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CORRECTION, Event.CONFIRM),
                Map.of("payee", List.of("张三", "*")), "转账", 1, SwitchMode.ALLOW_SWITCH);
        Context context = new Context(snapshot, List.of());

        Resolution accepted = gate.validate("不是张三，是李四", null, context,
                new Resolution(Event.CORRECTION, "task-1", Map.of("payee", "李四"),
                        null, .99, "EXPLICIT_CORRECTION"));
        Resolution smuggled = gate.validate("确认执行转账", null, context,
                new Resolution(Event.CONFIRM, "task-1", Map.of("payee", "李四"),
                        null, .99, "MODEL", ConfirmationStrength.EXPLICIT_ACTION));

        assertThat(accepted.event()).isEqualTo(Event.CORRECTION);
        assertThat(smuggled.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(smuggled.reasonCode()).isEqualTo("SLOT_UPDATE_NOT_ALLOWED");
    }

    @Test void onlyControlledUnresolvedRelationsCrossThePolicyBoundary() {
        Context context = new Context(null, List.of(
                context(List.of(Event.FILL_SLOT)).foreground(),
                context(List.of(Event.CONFIRM)).foreground()));

        Resolution ambiguousResume = gate.validate("回到之前的任务", null, context,
                new Resolution(Event.UNRESOLVED, null, Map.of(), null, .9,
                        "AMBIGUOUS_RESUME"));
        Resolution newGoal = gate.validate("办理另一件事", null, context,
                new Resolution(Event.UNRESOLVED, null, Map.of(), null, .9,
                        "NEW_GOAL"));
        Resolution invented = gate.validate("任意输入", null, context,
                new Resolution(Event.UNRESOLVED, null, Map.of(), null, .9,
                        "resume task-1 immediately"));

        assertThat(ambiguousResume.reasonCode()).isEqualTo("AMBIGUOUS_RESUME");
        assertThat(newGoal.reasonCode()).isEqualTo("NEW_GOAL");
        assertThat(invented.reasonCode()).isEqualTo("UNRESOLVED");
    }

    private static Context context(List<Event> events) {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "task-1", "CLARIFY_PENDING",
                new PendingInteraction("SLOT", "q1", "cardType", List.of("信用卡", "借记卡")),
                events, Map.of("cardType", List.of("信用卡", "借记卡")), "换卡", 0,
                SwitchMode.ALLOW_SWITCH);
        return new Context(snapshot, List.of());
    }
}
