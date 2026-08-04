package com.huawei.finance.orchestrator.continuation;

import static com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContinuationCoordinatorTest {

    @Test
    void naturalLanguageConfirmationIsUnderstoodByModelThenValidatedByPolicy() {
        Snapshot foreground = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM, Event.CANCEL), Map.of(), "转账", 4,
                SwitchMode.ALLOW_SWITCH);
        Context context = new Context(foreground, List.of());
        AtomicInteger modelCalls = new AtomicInteger();
        ContinuationUnderstandingModel model = (tenant, agent, session, input, view) -> {
            modelCalls.incrementAndGet();
            return new Resolution(Event.CONFIRM, "task-1", Map.of(), null, .99,
                    "MODEL_EXPLICIT_ACTION", ConfirmationStrength.EXPLICIT_ACTION);
        };
        ContinuationCoordinator coordinator = coordinator(context, model);

        Decision decision = coordinator.decide("tenant", "agent", "session",
                "请将上述资金指令正式提交", null);

        assertThat(decision.modelUsed()).isTrue();
        assertThat(modelCalls).hasValue(1);
        assertThat(decision.resolution().event()).isEqualTo(Event.CONFIRM);
        assertThat(decision.resolution().confirmationStrength())
                .isEqualTo(ConfirmationStrength.EXPLICIT_ACTION);
    }

    @Test
    void naturalLanguageCanResumeTheOnlySuspendedTaskThroughTheModel() {
        Snapshot suspended = new Snapshot(RuntimeType.TASK, "task-old", "CLARIFY_PENDING", null,
                List.of(Event.FILL_SLOT, Event.CANCEL), Map.of(), "信用卡账单", 8,
                SwitchMode.ALLOW_SWITCH);
        Context context = new Context(null, List.of(suspended));
        AtomicInteger modelCalls = new AtomicInteger();
        ContinuationUnderstandingModel model = (tenant, agent, session, input, view) -> {
            modelCalls.incrementAndGet();
            return new Resolution(Event.RESUME_SUSPENDED, "task-old", Map.of(), null, .96,
                    "MODEL_RESUME_TASK");
        };
        ContinuationCoordinator coordinator = coordinator(context, model);

        Decision decision = coordinator.decide("tenant", "agent", "session",
                "回到刚才那件事", null);

        assertThat(decision.modelUsed()).isTrue();
        assertThat(modelCalls).hasValue(1);
        assertThat(decision.resolution().event()).isEqualTo(Event.RESUME_SUSPENDED);
        assertThat(decision.resolution().targetRef()).isEqualTo("task-old");
    }

    @Test
    void structuredActionSkipsTheUnderstandingModel() {
        Snapshot foreground = new Snapshot(RuntimeType.TASK, "task-1", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM), Map.of(), "转账", 4, SwitchMode.ALLOW_SWITCH);
        AtomicInteger modelCalls = new AtomicInteger();
        ContinuationCoordinator coordinator = coordinator(new Context(foreground, List.of()),
                (tenant, agent, session, input, context) -> {
                    modelCalls.incrementAndGet();
                    return new Resolution(Event.UNRESOLVED, null, Map.of(), null, 0, "UNUSED");
                });

        Decision decision = coordinator.decide("tenant", "agent", "session", "",
                new StructuredAction("CONFIRM", "task-1", 4));

        assertThat(decision.modelUsed()).isFalse();
        assertThat(modelCalls).hasValue(0);
        assertThat(decision.resolution().event()).isEqualTo(Event.CONFIRM);
    }

    @Test
    void modelMarksNonUniqueResumeWithoutChoosingForTheUser() {
        Snapshot first = new Snapshot(RuntimeType.TASK, "task-1", "CLARIFY_PENDING", null,
                List.of(Event.FILL_SLOT), Map.of(), "任务一", 2, SwitchMode.ALLOW_SWITCH);
        Snapshot second = new Snapshot(RuntimeType.TASK, "task-2", "CONFIRM_PENDING", null,
                List.of(Event.CONFIRM), Map.of(), "任务二", 3, SwitchMode.ALLOW_SWITCH);
        ContinuationCoordinator coordinator = coordinator(new Context(null, List.of(first, second)),
                (tenant, agent, session, input, context) -> new Resolution(
                        Event.UNRESOLVED, null, Map.of(), null, .9, "AMBIGUOUS_RESUME"));

        Decision decision = coordinator.decide("tenant", "agent", "session",
                "回到之前没办完的事情", null);

        assertThat(decision.modelUsed()).isTrue();
        assertThat(decision.resolution().event()).isEqualTo(Event.UNRESOLVED);
        assertThat(decision.resolution().reasonCode()).isEqualTo("AMBIGUOUS_RESUME");
        assertThat(decision.suspended()).hasSize(2);
    }

    @Test
    void configuredCollectionMemberUsesDeterministicSlotPath() {
        PendingInteraction pending = new PendingInteraction(
                "SLOT_QUESTION", "slot:cardType", "cardType", List.of("信用卡", "借记卡"));
        Snapshot foreground = new Snapshot(RuntimeType.TASK, "task-1", "CLARIFY_PENDING", pending,
                List.of(Event.FILL_SLOT, Event.CANCEL),
                Map.of("cardType", List.of("信用卡", "借记卡")), "换卡", 4,
                SwitchMode.ALLOW_SWITCH);
        AtomicInteger modelCalls = new AtomicInteger();
        ContinuationUnderstandingModel model = (tenant, agent, session, input, view) -> {
            modelCalls.incrementAndGet();
            return new Resolution(Event.UNRESOLVED, null, Map.of(), null, 0, "UNUSED");
        };
        ContinuationContextAssembler assembler = new ContinuationContextAssembler(null, null) {
            @Override
            public Context assemble(String tenantId, String agentId, String sessionId) {
                return new Context(foreground, List.of());
            }
        };
        ContinuationCoordinator coordinator = new ContinuationCoordinator(
                assembler, new DeterministicContinuationRules(), model,
                new ContinuationPolicyGate(.85, .95), null,
                (slot, value, allowed) -> "贷记卡".equals(value) ? "信用卡" : value);

        Decision decision = coordinator.decide("tenant", "agent", "session", "贷记卡", null);

        assertThat(decision.modelUsed()).isFalse();
        assertThat(modelCalls).hasValue(0);
        assertThat(decision.resolution().event()).isEqualTo(Event.FILL_SLOT);
        assertThat(decision.resolution().slotUpdates()).containsEntry("cardType", "信用卡");
    }

    private static ContinuationCoordinator coordinator(Context context,
                                                       ContinuationUnderstandingModel model) {
        ContinuationContextAssembler assembler = new ContinuationContextAssembler(null, null) {
            @Override
            public Context assemble(String tenantId, String agentId, String sessionId) {
                return context;
            }
        };
        return new ContinuationCoordinator(assembler, new DeterministicContinuationRules(), model,
                new ContinuationPolicyGate(.85, .95));
    }
}
