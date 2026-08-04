package com.huawei.finance.common.event;

import java.util.List;

/**
 * 多轮输入事件分类器（v0.7 §3.5 一等公民能力，实施架构 §5 第 7 条规定为共享库形态）。
 *
 * <p>判定顺序是安全性排序，不是可读性排序：取消与确认必须排在补充之前，
 * 否则「算了」这类输入会被当成槽位补充继续执行。
 *
 * <p>兜底策略为 fail-safe：识别不出的输入返回低置信 {@code SUPPLEMENT}，
 * 置信低于阈值时调用方会回落完整快路径。宁可多跑一次召回，也不能把新任务当续轮短路掉。
 */
public class EventClassifier {

    private final EventClassifierProperties props;

    public EventClassifier(EventClassifierProperties props) {
        this.props = props;
    }

    public EventClassification classify(String normalizedQuery, ActiveTaskView activeTask, IntentSignalProbe probe) {
        String query = normalizedQuery == null ? "" : normalizedQuery.trim();
        if (activeTask == null) {
            return result(InputEvent.NEW_TASK, 1.0, "no-active-task");
        }

        if (containsAny(query, props.getCancelKeywords())) {
            return result(InputEvent.CANCEL, 0.95, "cancel-keyword");
        }

        // 确认只在任务确实等待确认时成立。脱离 CONFIRM_PENDING 的「好的」是寒暄，
        // 不能解释为对某个动作的授权（v0.7 §3.5 CONFIRMATION 行）。
        if (activeTask.awaitingConfirmation() && containsAny(query, props.getConfirmKeywords())) {
            return result(InputEvent.CONFIRMATION, 0.95, "confirm-keyword-in-confirm-pending");
        }

        if (containsAny(query, props.getCorrectionKeywords())) {
            return result(InputEvent.CORRECTION, 0.9, "correction-keyword");
        }

        // 澄清选项优先于意图信号。刚把「信用卡 / 借记卡」摆给用户，用户回其中一个就是在回答；
        // 而这两个词同时也是信用卡业务的关键词，先问探针必然把答案误判成话题切换，
        // 表现为用户明明照着选项回答了，系统却重新开了一个任务
        if (activeTask.awaitingClarification()
                && matchesExpectedAnswer(query, activeTask.expectedAnswers())) {
            return result(InputEvent.SUPPLEMENT, 0.95, "expected-slot-answer");
        }

        IntentSignalProbe.Signal signal =
                (probe == null ? IntentSignalProbe.NONE : probe).probe(query, activeTask.domain());

        if (containsAny(query, props.getParallelMarkers()) && signal != IntentSignalProbe.Signal.NONE) {
            return result(InputEvent.NEW_PARALLEL_TASK, 0.85, "parallel-marker-with-intent");
        }

        if (activeTask.awaitingClarification() && signal == IntentSignalProbe.Signal.NONE
                && query.length() <= props.getSupplementMaxLength()) {
            return result(InputEvent.SUPPLEMENT, 0.8, "short-input-while-clarifying");
        }

        if (signal == IntentSignalProbe.Signal.OTHER_DOMAIN_INTENT) {
            return result(InputEvent.TOPIC_SWITCH, 0.9, "other-domain-intent");
        }
        if (signal == IntentSignalProbe.Signal.SAME_DOMAIN_INTENT) {
            return result(InputEvent.NEW_PARALLEL_TASK, 0.8, "same-domain-new-intent");
        }

        return result(InputEvent.SUPPLEMENT, 0.4, "fallback-low-confidence");
    }

    public double shortCircuitThreshold() {
        return props.getShortCircuitThreshold();
    }

    private EventClassification result(InputEvent event, double confidence, String rule) {
        return new EventClassification(event, confidence, props.getVersion(), rule);
    }

    private static boolean containsAny(String query, List<String> keywords) {
        for (String kw : keywords) {
            if (!kw.isEmpty() && query.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesExpectedAnswer(String query, List<String> expected) {
        for (String answer : expected) {
            if (!answer.isEmpty() && query.contains(answer)) {
                return true;
            }
        }
        return false;
    }
}
