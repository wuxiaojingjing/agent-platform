package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.contracts.model.Enums;

/**
 * 模板定义。
 *
 * @param key         模板键，进 ResponsePlan.templateKey
 * @param version     模板版本，进 ResponsePlan.templateVersion
 * @param phase       适用的回复阶段
 * @param content     Freemarker 模板正文
 * @param variables   变量 JSON Schema，渲染前校验
 * @param fallbackKey 校验或渲染失败时的兜底模板；兜底模板自身为空，避免回退成环
 */
public record TemplateDef(
        String key,
        String version,
        Enums.ResponsePhase phase,
        String content,
        JsonNode variables,
        String fallbackKey) {

    public boolean hasFallback() {
        return fallbackKey != null && !fallbackKey.isBlank();
    }
}
