package com.huawei.finance.fastpath.event;

import com.huawei.finance.common.event.IntentSignalProbe;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.fastpath.recall.RuleRecall;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.Map;

/**
 * 用规则通道给事件分类器提供「这句话里有没有新意图」的信号。
 *
 * <p>只用规则通道，不碰 OpenSearch 也不碰模型：探测发生在续轮短路判定之前，
 * 而续轮短路存在的意义就是省掉这些开销。为了判断「要不要省」先把开销花掉，
 * 逻辑上是自相矛盾的。
 *
 * <p>阈值取自 {@code fusion.yaml} 的 {@code thresholds.intentSignalMin}，与召回阈值同处一份资产。
 * 它刻意比召回阈值高：这里宁可漏报也不能误报，把一句普通的槽位补充误判成新意图，
 * 会中断一个正在进行的任务。两者必须一起调——原先这一条写死在 Java 里，
 * 调低召回线时它不会跟着动，相对关系就悄悄反转了。
 */
public class RuleBasedIntentProbe implements IntentSignalProbe {

    private final AssetBundle bundle;
    private final RuleRecall ruleRecall;

    public RuleBasedIntentProbe(AssetBundle bundle, RuleRecall ruleRecall) {
        this.bundle = bundle;
        this.ruleRecall = ruleRecall;
    }

    @Override
    public Signal probe(String normalizedQuery, String activeDomain) {
        RuleRecall.Result result = ruleRecall.recall(normalizedQuery);

        String best = null;
        double bestScore = 0.0;
        for (Map.Entry<String, Double> e : result.scores().entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }

        if (best == null || bestScore < bundle.fusion().getThresholds().getIntentSignalMin()) {
            return Signal.NONE;
        }

        CapabilityCard card = bundle.capability(best);
        if (card == null || activeDomain == null) {
            return Signal.OTHER_DOMAIN_INTENT;
        }
        return card.domains().contains(activeDomain) ? Signal.SAME_DOMAIN_INTENT : Signal.OTHER_DOMAIN_INTENT;
    }
}
