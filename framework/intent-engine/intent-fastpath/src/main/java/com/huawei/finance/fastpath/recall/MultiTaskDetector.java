package com.huawei.finance.fastpath.recall;

import com.huawei.finance.registry.asset.FusionConfig;
import java.util.List;

/**
 * 多任务与条件依赖信号检测（v0.7 §3.2：多任务、跨域关系触发 MULTI）。
 *
 * <p>这里的定位是**兜底**，不是主判。多意图判定的主责在仲裁模型（Arbitration Skill 规则 4），
 * 因为它读得懂「不足就别转」和「余额不足会怎样」的区别，而词表读不懂。
 * 但多意图漏识别属于「复杂误判为简单」，是 §3.3 要求门槛更严的那一类——模型偶发地把
 * 「查余额，再转 1000；不足就别转」判成单一转账就会真的转账，所以确定性信号必须在场，
 * 让 fail-safe 有据可依。
 *
 * <p>词表单独成立不足以判定。条件词表里有「如果」「要是」这类极高频的词，
 * 「如果我卡丢了怎么办」「要是余额不够会怎样」都会命中，而它们是再单纯不过的单一咨询。
 * 只凭词表就判多任务，这些请求会被直接推进慢路径，在 A 线上表现为「请您逐项办理」——
 * 用户只问了一件事，系统却说他说了好几件。所以词表信号要与**召回证据**同时成立：
 * 确实召回到两个以上够分量的能力，才算得上多任务。
 */
public class MultiTaskDetector {

    private final FusionConfig.MultiTask config;

    public MultiTaskDetector(FusionConfig.MultiTask config) {
        this.config = config;
    }

    /**
     * @param normalizedQuery      归一化查询
     * @param distinctCapabilities 融合后得分显著的不同能力数量
     * @param recallTrustworthy    召回通道是否完好。语义通道降级时能力计数会系统性偏低，
     *                             此时不能拿它当否定证据——否则一断 embedding，
     *                             所有多任务都会被判成单任务，正好倒向最危险的一侧
     */
    public Signal detect(String normalizedQuery, int distinctCapabilities, boolean recallTrustworthy) {
        boolean hasConjunction = containsAny(normalizedQuery, config.getConjunctions());
        boolean hasConditional = containsAny(normalizedQuery, config.getConditionals());
        boolean enoughCapabilities = distinctCapabilities >= config.getMinDistinctCapabilities();

        boolean lexicalSignal = hasConjunction || hasConditional;
        // 「再…；不足就…」同时出现时，词表本身已构成场景 3 级多任务——不能再被
        // 「融合只数出一张够分量的卡」否定。否则语义通道把转账分压到阈值下时，
        // 整句会落到 LOW_MARGIN 单域，计划开不起来
        boolean strongLexical = hasConjunction && hasConditional;

        // 召回不可信时退回纯词表判定：宁可把单任务误判成多任务（多问一句），
        // 也不能因为数不出能力而把多任务放行成直出（真的转账）
        boolean multiTask = strongLexical
                || (recallTrustworthy ? lexicalSignal && enoughCapabilities : lexicalSignal);

        return new Signal(multiTask, hasConjunction, hasConditional);
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (!marker.isEmpty() && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param multiTask      是否判定为多任务
     * @param hasConjunction 是否出现并列连接词
     * @param hasConditional 是否出现条件依赖表述
     */
    public record Signal(boolean multiTask, boolean hasConjunction, boolean hasConditional) {

        public String evidence() {
            if (hasConditional) {
                return "multitask:conditional";
            }
            if (hasConjunction) {
                return "multitask:conjunction";
            }
            return "multitask:none";
        }
    }
}
