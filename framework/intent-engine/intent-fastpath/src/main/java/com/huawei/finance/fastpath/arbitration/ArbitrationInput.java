package com.huawei.finance.fastpath.arbitration;

import com.huawei.finance.fastpath.recall.HybridRecall;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import java.util.Map;

/**
 * 仲裁输入。
 *
 * @param normalizedQuery 归一化查询
 * @param channel         渠道
 * @param page            当前页面
 * @param recall          召回输出
 * @param filledSlots     已填槽位
 * @param clarifyRounds   本会话已发生的澄清轮数
 */
public record ArbitrationInput(
        String normalizedQuery,
        String channel,
        String page,
        HybridRecall.Output recall,
        Map<String, Object> filledSlots,
        int clarifyRounds,
        ContextualQuery contextualQuery,
        IntentContext intentContext) {

    public ArbitrationInput(String normalizedQuery, String channel, String page,
                            HybridRecall.Output recall, Map<String, Object> filledSlots,
                            int clarifyRounds) {
        this(normalizedQuery, channel, page, recall, filledSlots, clarifyRounds, null, null);
    }

    /**
     * 换一份槽位，其余不变。
     *
     * <p>模型回填的槽位必须在 fail-safe 复核**之前**并进来。否则模型刚从
     * 「转两千给老徐」里读出金额，复核却拿着正则抽空的那份槽位判定缺参，
     * 结果是模型答对了、系统仍然去追问一遍。
     */
    public ArbitrationInput withSlots(Map<String, Object> slots) {
        return new ArbitrationInput(normalizedQuery, channel, page, recall, slots, clarifyRounds,
                contextualQuery, intentContext);
    }
}
