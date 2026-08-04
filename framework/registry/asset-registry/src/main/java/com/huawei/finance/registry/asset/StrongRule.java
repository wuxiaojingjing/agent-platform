package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import java.util.Map;

/**
 * 强规则（v0.7 §3.3 二级短路）。
 *
 * @param ruleId      规则标识，进 evidenceRefs 与打点
 * @param description 规则意图，评审时读的是这一行
 * @param category    POLICY / SAFETY / STRONG_BUSINESS，决定判定优先级分组
 * @param priority    同组内的判定顺序，小者先判
 * @param when        Aviator 条件表达式
 * @param decision    命中后的出口
 * @param capabilityId 正向规则命中的能力，拒绝类规则为空
 * @param reasonCode  原因码
 * @param handoffCode 转人工/拒绝的细分码
 * @param templateKey 指定回复模板，为空则由回复编排按出口选择
 * @param slots       模板变量的静态取值
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StrongRule(
        String ruleId,
        String description,
        String category,
        int priority,
        String when,
        Decision decision,
        String capabilityId,
        ReasonCode reasonCode,
        String handoffCode,
        String templateKey,
        Map<String, String> slots) {

    public StrongRule {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    /** 正向规则会指向具体能力，因此必须继续走风险等级与必填槽位校验（v0.7 §3.3）。 */
    public boolean isPositive() {
        return capabilityId != null && !capabilityId.isBlank();
    }
}
