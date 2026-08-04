package com.huawei.finance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ResponseComponent;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.PendingInteraction;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Snapshot;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.SwitchMode;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.response.AnswerAudit;
import com.huawei.finance.response.ResponseRealizer;
import com.huawei.finance.response.TemplateVariableValidator;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ResumeResponseBridgeTest {

    private static RuntimeEngines engines;
    private final ResumeResponseBridge bridge = new ResumeResponseBridge();

    @BeforeAll
    static void loadAssets() {
        AssetBundle assets = new AssetLoader(new ContractValidator()).loadDefault();
        ResponseRealizer renderer = new ResponseRealizer(assets, new TemplateVariableValidator(),
                new SimpleMeterRegistry(), AnswerAudit.passThrough(), null);
        engines = new RuntimeEngines(assets, null, null, renderer);
    }

    @Test
    void restoringSlotQuestionKeepsQuestionAndChoices() {
        Snapshot snapshot = snapshot("task-1", "CLARIFY_PENDING",
                new PendingInteraction("SLOT_QUESTION", "slot:cardType", "cardType",
                        List.of("信用卡", "借记卡")),
                List.of(Event.FILL_SLOT, Event.CANCEL), "cap.card.replace", Map.of());

        AgentResponse response = bridge.restored(engines, context(), snapshot);

        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(response.plan().cardComponents()).containsExactly(ResponseComponent.CHOICE_LIST);
        assertThat(response.plan().slots()).containsEntry("options", List.of("信用卡", "借记卡"));
        assertThat(response.text()).contains("信用卡").contains("借记卡");
        assertThat(response.taskId()).isEqualTo("task-1");
    }

    @Test
    void restoringReviewUsesCapabilityTemplateAndVersionedActions() {
        Snapshot snapshot = snapshot("task-2", "REVIEW_PENDING",
                new PendingInteraction("REVIEW", "review:task-2", null, List.of()),
                List.of(Event.REVIEW_ACCEPT, Event.CANCEL), "cap.card.replace",
                Map.of("cardType", "CREDIT"));

        AgentResponse response = bridge.restored(engines, context(), snapshot);

        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.REVIEW);
        assertThat(response.plan().templateKey()).isEqualTo("tpl.card-replace.review");
        assertThat(response.plan().cardComponents()).containsExactly(ResponseComponent.REVIEW_SUMMARY);
        assertThat(response.actions()).extracting(action -> action.event())
                .containsExactly("REVIEW_ACCEPT", "CANCEL");
        assertThat(response.actions()).allMatch(action -> action.version() == 7);
        assertThat(response.text()).contains("信用卡");
    }

    @Test
    void restoringConfirmationKeepsCommittedTransferFacts() {
        Snapshot snapshot = snapshot("task-3", "CONFIRM_PENDING",
                new PendingInteraction("CONFIRM", "confirm:task-3", null, List.of()),
                List.of(Event.CONFIRM, Event.CANCEL), "cap.transfer",
                Map.of("payee", "张三", "amount", "1000"));

        AgentResponse response = bridge.restored(engines, context(), snapshot);

        assertThat(response.plan().responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(response.plan().templateKey()).isEqualTo("tpl.transfer.confirm");
        assertThat(response.plan().slots()).containsEntry("payee", "张三")
                .containsEntry("amount", "1000").containsEntry("currency", "¥");
        assertThat(response.actions()).extracting(action -> action.event())
                .containsExactly("CONFIRM", "CANCEL");
        assertThat(response.text()).contains("张三").contains("1000").contains("确认");
    }

    private static Snapshot snapshot(String ref, String state, PendingInteraction pending,
                                     List<Event> events, String capabilityId,
                                     Map<String, Object> confirmedFacts) {
        return new Snapshot(RuntimeType.TASK, ref, state, pending, events, Map.of(), capabilityId,
                7, SwitchMode.ALLOW_SWITCH, "继续原任务", confirmedFacts, Map.of());
    }

    private static RequestContext context() {
        return new RequestContext("trace-resume", "session-1", "user-1", "space-1",
                "agent.mobile-banking-assistant", "MOBILE_BANK", "home", "LOGGED_IN", false);
    }
}
