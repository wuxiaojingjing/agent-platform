package com.huawei.finance.fastpath.rule;

import com.huawei.finance.registry.asset.StrongRule;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 强规则判定（v0.7 §3.3 二级短路）。
 *
 * <p>规则已在加载期按 priority 升序排好，这里取第一条命中即返回。
 * 「唯一命中」的语义由排序保证：策略与安全类规则的 priority 恒小于业务类，
 * 所以当一条拒绝规则和一条执行规则同时成立时，返回的一定是拒绝。
 *
 * <p>本引擎只判出口，**不判槽位**。§3.3 要求强规则直出「仍校验风险等级和必填槽位」，
 * 那一步在 {@code SlotGate} 里做，规则无权跳过。
 */
public class StrongRuleEngine {

    private final List<StrongRule> rules;
    private final ExpressionEvaluator evaluator;

    public StrongRuleEngine(List<StrongRule> rules, ExpressionEvaluator evaluator) {
        this.rules = rules;
        this.evaluator = evaluator;
        for (StrongRule rule : rules) {
            evaluator.precompile(rule.ruleId(), rule.when());
        }
    }

    public Optional<StrongRule> firstMatch(Map<String, Object> context) {
        for (StrongRule rule : rules) {
            if (evaluator.evaluateBoolean(rule.ruleId(), rule.when(), context)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }
}
