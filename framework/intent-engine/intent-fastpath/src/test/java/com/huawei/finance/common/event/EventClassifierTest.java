package com.huawei.finance.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 多轮输入事件分类（v0.7 §3.5）。判定顺序是安全性排序，用例按此组织。 */
class EventClassifierTest {

    private final EventClassifier classifier = new EventClassifier(new EventClassifierProperties());

    @Test
    @DisplayName("无活跃任务一律新任务，不去猜续轮")
    void noActiveTaskIsAlwaysNewTask() {
        EventClassification result = classifier.classify("查一下余额", null, IntentSignalProbe.NONE);

        assertThat(result.event()).isEqualTo(InputEvent.NEW_TASK);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("「算了」优先判为取消，不能当成槽位补充继续执行")
    void cancelBeatsSupplement() {
        EventClassification result = classifier.classify("算了", clarifying(), IntentSignalProbe.NONE);

        assertThat(result.event()).isEqualTo(InputEvent.CANCEL);
    }

    @Test
    @DisplayName("只有确认态里的「好的」才算授权，寒暄不是")
    void confirmationOnlyCountsWhileAwaitingConfirmation() {
        assertThat(classifier.classify("好的", confirming(), IntentSignalProbe.NONE).event())
                .isEqualTo(InputEvent.CONFIRMATION);
        assertThat(classifier.classify("好的", clarifying(), IntentSignalProbe.NONE).event())
                .isNotEqualTo(InputEvent.CONFIRMATION);
    }

    @Test
    @DisplayName("用户照着选项回答即为补充，即使这个词本身也是一个业务关键词")
    void expectedAnswerWinsOverIntentSignal() {
        // 「信用卡」既是我们给出的选项，又是信用卡业务的关键词。先问意图探针，
        // 用户照着选项回答反而会被判成话题切换，任务被推倒重来
        IntentSignalProbe alwaysIntent = (q, domain) -> IntentSignalProbe.Signal.OTHER_DOMAIN_INTENT;

        EventClassification result = classifier.classify("信用卡", clarifying(), alwaysIntent);

        assertThat(result.event()).isEqualTo(InputEvent.SUPPLEMENT);
        assertThat(result.matchedRule()).isEqualTo("expected-slot-answer");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(classifier.shortCircuitThreshold());
    }

    @Test
    @DisplayName("澄清途中提别的领域的事是话题切换，不是补充")
    void otherDomainIntentIsTopicSwitch() {
        IntentSignalProbe probe = (q, domain) -> IntentSignalProbe.Signal.OTHER_DOMAIN_INTENT;

        EventClassification result = classifier.classify("我要转账给张三", clarifying(), probe);

        assertThat(result.event()).isEqualTo(InputEvent.TOPIC_SWITCH);
    }

    @Test
    @DisplayName("认不出的输入落到低置信补充，由调用方回落完整快路径")
    void unrecognizedInputFallsBackBelowThreshold() {
        EventClassification result = classifier.classify(
                "这个月的账我还没看明白到底怎么算的", clarifying(), IntentSignalProbe.NONE);

        assertThat(result.event()).isEqualTo(InputEvent.SUPPLEMENT);
        assertThat(result.confidentEnoughToShortCircuit(classifier.shortCircuitThreshold())).isFalse();
    }

    private static ActiveTaskView clarifying() {
        return new ActiveTaskView("task-1", "CLARIFY_PENDING", "creditcard", "cap.card.replace",
                "cardType", List.of("信用卡", "借记卡"), Map.of(), 1);
    }

    private static ActiveTaskView confirming() {
        return new ActiveTaskView("task-1", "CONFIRM_PENDING", "payment", "cap.transfer",
                null, List.of(), Map.of("payee", "张三", "amount", "1000"), 0);
    }
}
