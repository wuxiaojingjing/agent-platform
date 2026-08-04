package com.huawei.finance.fastpath.arbitration;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.SlotNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 必填槽位校验。
 *
 * <p>独立成类是为了保证**没有任何出口路径能绕过它**。v0.7 §3.3 对二级短路的原文是
 * 「由规则仲裁直接给出出口，不调用模型，但仍校验风险等级和必填槽位」；
 * 若把槽位校验写在模型仲裁分支里，强规则直出与缓存直出这两条路就天然漏掉了。
 */
public class SlotGate {

    /**
     * @param card       目标能力
     * @param filledSlots 已填槽位，含本轮抽取与会话内已确认的
     * @return 缺失的必填槽位，空列表表示齐全
     */
    public List<String> missingSlots(CapabilityCard card, Map<String, Object> filledSlots) {
        if (card == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String slot : card.requiredSlots()) {
            Object value = filledSlots.get(slot);
            if (value == null || String.valueOf(value).isBlank()) {
                if (SlotNames.AMOUNT.equals(slot)
                        && "REQUERY_THEN_HALF".equals(filledSlots.get(SlotNames.AMOUNT_BASIS))) {
                    continue;
                }
                missing.add(slot);
            }
        }
        return missing;
    }
}
