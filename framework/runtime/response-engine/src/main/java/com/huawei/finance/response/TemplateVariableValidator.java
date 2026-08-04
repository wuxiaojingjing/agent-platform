package com.huawei.finance.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ValidationOutcome;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.huawei.finance.registry.asset.TemplateDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板变量校验。
 *
 * <p>与契约校验共用 networknt 一个库（实施架构 §2.7），但 Schema 来自资产而非 classpath，
 * 因此不能复用 {@code ContractValidator} 的枚举索引。编译结果按模板键缓存：
 * 资产在进程内不变，重复编译纯属浪费。
 *
 * <p>为什么必须校验：Freemarker 遇到未定义变量会抛异常，遇到 null 则渲染成空串。
 * 后者在面客链路上表现为「您的可用余额为 元」——语句通顺、数字消失，
 * 比直接报错危险得多。
 */
public class TemplateVariableValidator {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public ValidationOutcome validate(TemplateDef template, Map<String, Object> variables) {
        JsonNode schemaNode = template.variables();
        if (schemaNode == null || schemaNode.isMissingNode() || schemaNode.isNull()) {
            return ValidationOutcome.ok();
        }
        JsonSchema schema = compiled.computeIfAbsent(
                template.key() + "@" + template.version(), k -> factory.getSchema(schemaNode));

        Set<ValidationMessage> errors = schema.validate(ContractJson.mapper().valueToTree(variables));
        if (errors.isEmpty()) {
            return ValidationOutcome.ok();
        }
        List<String> messages = new ArrayList<>(errors.size());
        for (ValidationMessage m : errors) {
            messages.add(m.getMessage());
        }
        return new ValidationOutcome(false, messages);
    }
}
