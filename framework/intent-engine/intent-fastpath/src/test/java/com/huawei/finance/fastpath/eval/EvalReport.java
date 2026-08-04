package com.huawei.finance.fastpath.eval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 评测汇总（FP-52）。
 *
 * <p>这个类存在的唯一理由是**不让通过率变成一个能被误读的数字**。它把结果按两个维度分开数：
 *
 * <ul>
 *   <li>标注来源：工程写的现状锁只能证明「没变」，业务签署的真值才能谈「对不对」。
 *       两者混成一个百分比之后，那个百分比什么也不说明，却看起来什么都说明了。</li>
 *   <li>用例状态：{@code known-gap} 永远不计入通过率。已知缺口跑出了预期中的错误结果，
 *       那叫「缺口还在」，不叫「通过」。</li>
 * </ul>
 *
 * <p>计划 §1 规则 3 那句「结构达标不等于效果达标」，在这里落成代码。
 */
public final class EvalReport {

    private final Map<String, Integer> byDecision = new TreeMap<>();
    private int locked;
    private int lockedMatched;
    private int knownGaps;
    private int knownGapsDrifted;
    private int businessLabeled;

    public void record(EvalCase testCase, String actualDecision, boolean matched) {
        byDecision.merge(actualDecision, 1, Integer::sum);
        if (EvalCase.LabelSource.BUSINESS.equals(testCase.getLabeledBy())) {
            businessLabeled++;
        }
        if (testCase.knownGap()) {
            knownGaps++;
            if (!matched) {
                knownGapsDrifted++;
            }
            return;
        }
        locked++;
        if (matched) {
            lockedMatched++;
        }
    }

    public int locked() {
        return locked;
    }

    public int knownGaps() {
        return knownGaps;
    }

    public int businessLabeled() {
        return businessLabeled;
    }

    /** 已知缺口偏离了记录下来的现状——它变了，好是坏都得有人看一眼。 */
    public int knownGapsDrifted() {
        return knownGapsDrifted;
    }

    public Map<String, Integer> decisionMix() {
        return new LinkedHashMap<>(byDecision);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("\n===== 快路径评测汇总（FP-52）=====\n");
        sb.append(String.format("现状锁    %d/%d 一致%n", lockedMatched, locked));
        sb.append(String.format("已知缺口  %d 条（不计入通过率）%s%n", knownGaps,
                knownGapsDrifted > 0 ? "，其中 " + knownGapsDrifted + " 条已偏离记录" : ""));
        sb.append("出口分布  ").append(byDecision).append('\n');
        sb.append(String.format("业务签署  %d 条%n", businessLabeled));
        if (businessLabeled == 0) {
            sb.append("⚠️ 无业务签署用例：本报告只能说明「行为未变」，不能作为效果结论。\n");
            sb.append("   门槛与真值待业务部给（计划 §7 阻断项 1、2b）。\n");
        }
        sb.append("运行配置  规则通道单通道（语义通道未接，等于在跑降级态；数字是下限）\n");
        return sb.toString();
    }
}
