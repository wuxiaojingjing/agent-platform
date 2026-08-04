package com.huawei.finance.contracts.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 全工程唯一的 JSON Schema 校验器。
 *
 * <p>实施架构 §2.7 要求契约校验、模板变量校验、仲裁输出校验**共用一个库**。多套校验器
 * 意味着多套错误信息格式与多套 draft 行为差异，排障时无法比对。
 *
 * <p>Schema 在构造期一次性编译并缓存：快路径要在毫秒级预算内跑完，不能每次请求重新解析。
 */
public class ContractValidator {

    private final JsonSchemaFactory factory;
    private final Map<SchemaRef, JsonSchema> schemas = new EnumMap<>(SchemaRef.class);
    private final ConcurrentMap<String, JsonSchema> embeddedSchemas = new ConcurrentHashMap<>();

    public ContractValidator() {
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (SchemaRef ref : SchemaRef.values()) {
            schemas.put(ref, compile(factory, ref));
        }
    }

    private static JsonSchema compile(JsonSchemaFactory factory, SchemaRef ref) {
        ClassLoader loader = ContractValidator.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(ref.resourcePath())) {
            if (in == null) {
                throw new IllegalStateException("找不到 Schema 资源：" + ref.resourcePath());
            }
            return factory.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 Schema 失败：" + ref.resourcePath(), e);
        }
    }

    public ValidationOutcome validate(SchemaRef ref, JsonNode node) {
        Set<ValidationMessage> errors = schemas.get(ref).validate(node);
        if (errors.isEmpty()) {
            return ValidationOutcome.ok();
        }
        List<String> messages = new ArrayList<>(errors.size());
        for (ValidationMessage m : errors) {
            messages.add(m.getMessage());
        }
        return new ValidationOutcome(false, messages);
    }

    /** 校验 POJO：先按共享 ObjectMapper 序列化，确保校验的就是实际会发出去的 JSON。 */
    public ValidationOutcome validate(SchemaRef ref, Object pojo) {
        return validate(ref, ContractJson.mapper().valueToTree(pojo));
    }

    public ValidationOutcome validateJson(SchemaRef ref, String json) {
        try {
            return validate(ref, ContractJson.mapper().readTree(json));
        } catch (IOException e) {
            return new ValidationOutcome(false, List.of("不是合法 JSON：" + e.getMessage()));
        }
    }

    /** Validate a value against a JSON Schema embedded in a versioned capability card. */
    public ValidationOutcome validateSchema(Map<String, Object> schema, Object value) {
        if (schema == null || schema.isEmpty()) {
            return ValidationOutcome.ok();
        }
        JsonSchema compiled;
        try {
            compiled = embeddedSchemas.computeIfAbsent(schemaKey(schema),
                    ignored -> factory.getSchema(ContractJson.mapper().valueToTree(schema)));
        } catch (RuntimeException invalidSchema) {
            return new ValidationOutcome(false, List.of("不是合法 JSON Schema：" + invalidSchema.getMessage()));
        }
        Set<ValidationMessage> errors = compiled.validate(ContractJson.mapper().valueToTree(value));
        if (errors.isEmpty()) {
            return ValidationOutcome.ok();
        }
        List<String> messages = new ArrayList<>(errors.size());
        for (ValidationMessage error : errors) {
            messages.add(error.getMessage());
        }
        return new ValidationOutcome(false, messages);
    }

    public ValidationOutcome validateSchemaDefinition(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return ValidationOutcome.ok();
        try {
            embeddedSchemas.computeIfAbsent(schemaKey(schema),
                    ignored -> factory.getSchema(ContractJson.mapper().valueToTree(schema)));
            return ValidationOutcome.ok();
        } catch (RuntimeException invalidSchema) {
            return new ValidationOutcome(false, List.of("不是合法 JSON Schema：" + invalidSchema.getMessage()));
        }
    }

    private static String schemaKey(Map<String, Object> schema) {
        try {
            return ContractJson.mapper().writeValueAsString(schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
