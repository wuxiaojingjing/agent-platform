package com.huawei.finance.response;

import com.huawei.finance.contracts.model.ResponsePlan;

/**
 * 送审的答案。
 *
 * <p>带上 {@code plan} 而不只是文本，是因为审核口径往往依赖场景：
 * 同一句话出现在 R2 转账确认页与出现在余额查询结果里，合规要求并不相同。
 *
 * @param text            渲染出来的面客文本
 * @param usedTemplateKey 实际使用的模板键，兜底时与 {@code plan.templateKey()} 不同；无模板时为 null
 * @param fellBack        是否已经是兜底文本。审核器据此避免把兜底再往下降一级
 * @param plan            对应的回复计划，含场景码、风险提示码、渠道
 */
public record AnswerDraft(
        String text,
        String usedTemplateKey,
        boolean fellBack,
        ResponsePlan plan) {
}
