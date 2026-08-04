package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基线的保守 GOAL 解析:按**资产里给的关键词**把目标落到能力，拿不准就不认领。
 *
 * <p>关键词来自能力卡上已有的 {@code keywords} 字段，不写在 Java 里，也不另开一份词表——
 * 另开一份的话，同一条能力就有两处词表，改了一处忘了另一处时召回和 GOAL 解析会各认一套——写在代码里的话，
 * 加一个说法要改代码、过流水线、发版本，而这类词表是运营天天要动的东西
 * （同 {@code ExternalizedConstantsTest} 守的那批常量）。
 *
 * <p><b>唯一命中才认领。</b>两条能力同时命中说明这句话本身有歧义，此时不认领，
 * 让入口去澄清:选一个「看起来更像」的去办，赌错的代价是一次错误办理，
 * 而澄清的代价只是多问一轮。这个不对称决定了方向。
 *
 * <p>命中还要过 {@link TechDomainAgent#supports}:词表可能列了本域还没开放的能力
 * （卡先行、实现后到是常态）。不核对的话，未开放能力会被当成「落到了」，
 * 然后在执行处才发现，回的原因码就从 DOMAIN_NOT_OPEN 变成一次莫名的失败。
 */
public class KeywordGoalResolver implements GoalCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(KeywordGoalResolver.class);

    /** 能力 ID → 该能力的目标关键词。 */
    private final Map<String, List<String>> keywords;

    public KeywordGoalResolver(Map<String, List<String>> keywords) {
        this.keywords = Map.copyOf(keywords);
    }

    @Override
    public Optional<String> resolve(String goal, TechDomainAgent agent) {
        if (goal == null || goal.isBlank()) {
            return Optional.empty();
        }

        Map<String, Integer> hits = new LinkedHashMap<>();
        keywords.forEach((capabilityId, words) -> {
            if (!agent.supports(capabilityId)) {
                return;
            }
            int matched = (int) words.stream().filter(goal::contains).count();
            if (matched > 0) {
                hits.put(capabilityId, matched);
            }
        });

        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() == 1) {
            return Optional.of(hits.keySet().iterator().next());
        }

        // 多条命中:只有当某一条命中的词**严格多于**其余全部时才敢认领。
        // 并列最高一律不认领——并列意味着这句话同时像两件事
        int best = hits.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> top = hits.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        if (top.size() > 1) {
            log.info("目标同时命中多条能力，不认领交由入口澄清 domain={} 候选={}",
                    agent.techDomainCode(), top);
            return Optional.empty();
        }
        return Optional.of(top.get(0));
    }
}
