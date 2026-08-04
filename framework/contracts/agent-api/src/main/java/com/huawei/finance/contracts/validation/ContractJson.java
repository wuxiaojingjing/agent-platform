package com.huawei.finance.contracts.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 契约序列化用的共享 ObjectMapper。
 *
 * <p>全工程共用一份配置，否则「同一个 DTO 在两个模块序列化出不同 JSON」这类问题会在
 * 跨模块联调时才暴露。
 */
public final class ContractJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 契约演进时新增字段不应让老消费方直接失败，字段级校验交给 JSON Schema
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ContractJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
