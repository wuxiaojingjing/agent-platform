package com.huawei.finance.orchestrator.continuation;

import static com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicContinuationRulesTest {
    private final DeterministicContinuationRules rules = new DeterministicContinuationRules();

    @Test
    void naturalLanguageTaskSelectionIsLeftToTheUnderstandingModel() {
        Context context = context("查余额");

        assertThat(rules.resolve("先办查余额", null, context).event()).isEqualTo(Event.UNRESOLVED);
        assertThat(rules.resolve("1", null, context).event()).isEqualTo(Event.UNRESOLVED);
        assertThat(rules.resolve("第一件", null, context).event()).isEqualTo(Event.UNRESOLVED);
        assertThat(rules.resolve("先办查账单", null, context).event()).isEqualTo(Event.UNRESOLVED);
    }

    @Test
    void naturalLanguageCancelAndNewGoalAreLeftToTheUnderstandingModel() {
        Context context = context("查余额");

        Resolution cancel = rules.resolve("算了", null, context);
        Resolution switchGoal = rules.resolve("算了，看看我的理财持仓", null, context);

        assertThat(cancel.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(switchGoal.event()).isEqualTo(Event.UNRESOLVED);
    }

    @Test
    void naturalLanguageR2ConfirmationIsLeftToTheUnderstandingModel() {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL),
                Map.of(), "转账", 1, SwitchMode.ALLOW_SWITCH);
        Context context = new Context(snapshot, List.of());

        assertThat(rules.resolve("确认执行转账", null, context).event()).isEqualTo(Event.UNRESOLVED);
        assertThat(rules.resolve("确认", null, context).event()).isEqualTo(Event.UNRESOLVED);
    }

    @Test
    void naturalLanguagePendingSwitchDecisionIsLeftToTheUnderstandingModel() {
        Context context = new Context(context("换卡").foreground(), List.of(),
                new PendingSwitchView("switch-1", 3, List.of(Event.SWITCH_ACCEPT, Event.SWITCH_REJECT)));

        Resolution accepted = rules.resolve("好，切换", null, context);
        Resolution rejected = rules.resolve("继续当前任务", null, context);

        assertThat(accepted.event()).isEqualTo(Event.UNRESOLVED);
        assertThat(rejected.event()).isEqualTo(Event.UNRESOLVED);
    }

    @Test
    void naturalLanguageCorrectionIsLeftToTheUnderstandingModel() {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CORRECTION, Event.CONFIRM, Event.CANCEL),
                Map.of("payee", List.of("张三", "*"), "amount", List.of("1000", "*")),
                "转账", 1, SwitchMode.ALLOW_SWITCH);

        Resolution result = rules.resolve("不是张三，是李四", null,
                new Context(snapshot, List.of()));

        assertThat(result.event()).isEqualTo(Event.UNRESOLVED);
    }

    @Test
    void structuredActionsRemainDeterministic() {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM, Event.CANCEL), Map.of(), "转账", 7,
                SwitchMode.ALLOW_SWITCH);

        Resolution result = rules.resolve("", new StructuredAction("CONFIRM", "task-1", 7),
                new Context(snapshot, List.of()));

        assertThat(result.event()).isEqualTo(Event.CONFIRM);
        assertThat(result.targetRef()).isEqualTo("task-1");
        assertThat(result.reasonCode()).isEqualTo("STRUCTURED_ACTION");
    }

    @Test
    void exactRuntimeDeclaredSlotValueRemainsDeterministic() {
        Snapshot snapshot = new Snapshot(RuntimeType.TASK, "task-1", "CLARIFY_PENDING",
                new PendingInteraction("SLOT", "q1", "cardType", List.of("信用卡", "借记卡")),
                List.of(Event.FILL_SLOT), Map.of("cardType", List.of("信用卡", "借记卡")),
                "换卡", 1, SwitchMode.ALLOW_SWITCH);

        Resolution result = rules.resolve("信用卡", null, new Context(snapshot, List.of()));

        assertThat(result.event()).isEqualTo(Event.FILL_SLOT);
        assertThat(result.slotUpdates()).containsExactly(Map.entry("cardType", "信用卡"));
        assertThat(result.reasonCode()).isEqualTo("EXACT_SLOT_VALUE");
    }

    private static Context context(String summary) {
        Snapshot snapshot = new Snapshot(RuntimeType.STATIC_PLAN, "plan-1", "IN_PROGRESS", null,
                List.of(Event.CONTINUE_CURRENT, Event.CANCEL, Event.SWITCH_TO_NEW_GOAL),
                Map.of(), summary, 1, SwitchMode.ALLOW_SWITCH);
        return new Context(snapshot, List.of());
    }
}
