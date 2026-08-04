package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 负向过滤规则（v0.7 §3.2 负向过滤通道）。
 *
 * @param ruleId      规则标识，命中后写入候选的 matchedEvidence，便于归因打压来源
 * @param description 规则意图
 * @param when        Aviator 条件表达式
 * @param suppress    被打压的能力 ID
 * @param penalty     打压分，进 Candidate.scores.negative
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NegativeRule(
        String ruleId,
        String description,
        String when,
        List<String> suppress,
        double penalty) {

    public NegativeRule {
        suppress = suppress == null ? List.of() : List.copyOf(suppress);
    }
}
