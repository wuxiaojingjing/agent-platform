package com.huawei.finance.agent.promptopt;

import java.util.ArrayList;
import java.util.List;

/** Safety and regression gates for context-rewrite prompt candidates. */
public final class ContextOptimizerGuard {

    static final List<String> ANCHORS = List.of(
            "不能执行能力、不能回答用户、不能根据旧余额生成交易金额、不能补造上下文中不存在的事实",
            "usedContextRefs、invalidatedContextRefs 和 resolution.contextRef 只能引用 availableContext 中的 ref",
            "mention 必须是 originalQuery 中的连续原文",
            "不得使用旧余额直接计算金额，不得输出计算后金额",
            "只输出这一份 JSON");
    static final int MAX_SYSTEM_CHARS = 3200;
    static final int MIN_GAIN = 2;

    private ContextOptimizerGuard() {
    }

    public record Ruling(boolean accepted, List<String> reasons) {
        static Ruling ok() { return new Ruling(true, List.of()); }
    }

    static Ruling reviewText(String candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate == null || candidate.isBlank()) return new Ruling(false, List.of("候选为空"));
        ANCHORS.stream().filter(anchor -> !candidate.contains(anchor))
                .forEach(anchor -> reasons.add("删掉安全锚句「" + anchor + "」"));
        if (candidate.length() > MAX_SYSTEM_CHARS) reasons.add("提示词超过 3200 字符");
        if (!candidate.contains("JSON")) reasons.add("不再要求 JSON 输出");
        return reasons.isEmpty() ? Ruling.ok() : new Ruling(false, reasons);
    }

    static Ruling reviewBehaviour(ContextRewriteScorer.Score baseline,
                                  ContextRewriteScorer.Score candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate.invalidJson() > baseline.invalidJson()) reasons.add("Schema 失败增加");
        if (candidate.outOfScopeRefs() > baseline.outOfScopeRefs()) reasons.add("越界引用增加");
        if (candidate.unsafeDerivedAmount() > baseline.unsafeDerivedAmount()) reasons.add("旧余额派生金额增加");
        if (candidate.incoherentContract() > baseline.incoherentContract()) reasons.add("原子契约不一致增加");
        if (candidate.passed() < baseline.passed() + MIN_GAIN) {
            reasons.add("通过数提升不足，要求至少 +" + MIN_GAIN);
        }
        return reasons.isEmpty() ? Ruling.ok() : new Ruling(false, reasons);
    }
}
