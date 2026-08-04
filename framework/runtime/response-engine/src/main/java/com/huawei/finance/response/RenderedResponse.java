package com.huawei.finance.response;

import com.huawei.finance.contracts.model.ResponsePlan;

/**
 * 渲染结果。
 *
 * @param text            面客文本
 * @param usedTemplateKey 实际使用的模板键，兜底时与 plan 里的不同
 * @param fellBack        是否走了兜底
 * @param reason          兜底原因，正常渲染为 null
 * @param plan            对应的回复计划
 */
public record RenderedResponse(
        String text,
        String usedTemplateKey,
        boolean fellBack,
        String reason,
        ResponsePlan plan) {
}
