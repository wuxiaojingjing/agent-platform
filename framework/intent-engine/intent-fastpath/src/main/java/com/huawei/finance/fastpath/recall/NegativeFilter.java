package com.huawei.finance.fastpath.recall;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.fastpath.rule.ExpressionEvaluator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.NegativeRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负向过滤（v0.7 §3.2 负向过滤通道：处理近邻误入、越界和互斥）。
 *
 * <p>打压分与命中原因一并返回。只减分不记原因，线上出现「该召回的被压没了」时
 * 无法判断是哪条规则干的，只能靠猜。
 */
public class NegativeFilter {

    /** {@code suppress} 里以此结尾的条目按前缀匹配整类能力，而不是一个具体 ID。 */
    private static final String CLASS_WILDCARD = "*";

    private final List<NegativeRule> rules;
    private final ExpressionEvaluator evaluator;

    /**
     * @param universe 能力 ID 全集，用于把 {@code suppress} 里的 {@code cap.nav.*} 展开成具体 ID。
     *                 **不允许省略。**手工枚举「这一类都要压制」时，漏掉一个的后果是它
     *                 <em>不被压制</em>——而那正是危险的方向：新上的一张导航卡会默默地
     *                 去抢业务办理卡的名次，且加载、条数、指标全都正常。
     *                 24 张 nav 卡里曾有 15 张漏在枚举之外，`cap.nav.creditcard_service_信用卡账单`
     *                 因此压过了 `cap.creditcard.bill.query`
     */
    public NegativeFilter(List<NegativeRule> rules, ExpressionEvaluator evaluator,
                          Collection<String> universe) {
        this.evaluator = evaluator;
        this.rules = expand(rules == null ? List.of() : rules,
                universe == null ? List.of() : universe);
    }

    /**
     * 按资产装配，能力全集取自 {@code bundle}。
     *
     * <p>装配点一律走这里而不是直接调构造器：全集参数一旦可省，就会有人省掉它，
     * 而省掉之后类通配静默失效——不报错，只是不再压制。
     */
    public static NegativeFilter of(AssetBundle bundle, ExpressionEvaluator evaluator) {
        return new NegativeFilter(bundle.negativeRules(), evaluator,
                bundle.capabilities().stream().map(CapabilityCard::capabilityId).toList());
    }

    /**
     * 把带类通配的 {@code suppress} 展开成具体 ID，在构造期一次做完。
     *
     * <p>放在构造期而不是每次 {@code apply}：展开结果只随资产变化，而 {@code apply}
     * 在每次请求的召回路径上。
     */
    private static List<NegativeRule> expand(List<NegativeRule> source, Collection<String> universe) {
        List<NegativeRule> expanded = new ArrayList<>(source.size());
        for (NegativeRule rule : source) {
            List<String> targets = new ArrayList<>();
            for (String entry : rule.suppress()) {
                if (entry != null && entry.endsWith(CLASS_WILDCARD)) {
                    String prefix = entry.substring(0, entry.length() - CLASS_WILDCARD.length());
                    universe.stream().filter(id -> id != null && id.startsWith(prefix)).forEach(targets::add);
                } else {
                    targets.add(entry);
                }
            }
            expanded.add(new NegativeRule(rule.ruleId(), rule.description(), rule.when(),
                    targets, rule.penalty()));
        }
        return List.copyOf(expanded);
    }

    public Result apply(Map<String, Object> context) {
        Map<String, Double> penalties = new HashMap<>();
        Map<String, List<String>> reasons = new HashMap<>();

        for (NegativeRule rule : rules) {
            if (!evaluator.evaluateBoolean(rule.ruleId(), rule.when(), context)) {
                continue;
            }
            for (String capabilityId : rule.suppress()) {
                // 多条规则同时打压时取累加而非取最大：两个独立理由都说不该选它，
                // 证据是叠加的，取最大会丢掉其中一条
                penalties.merge(capabilityId, rule.penalty(), Double::sum);
                reasons.computeIfAbsent(capabilityId, k -> new ArrayList<>())
                        .add("negative:" + rule.ruleId());
            }
        }
        return new Result(penalties, reasons);
    }

    /**
     * @param penalties 能力 ID → 累计打压分
     * @param reasons   能力 ID → 打压原因
     */
    public record Result(Map<String, Double> penalties, Map<String, List<String>> reasons) {

        public double penaltyOf(String capabilityId) {
            return penalties.getOrDefault(capabilityId, 0.0);
        }

        public List<String> reasonsOf(String capabilityId) {
            return reasons.getOrDefault(capabilityId, List.of());
        }
    }
}
