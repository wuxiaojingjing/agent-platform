package com.huawei.finance.gateway;

import com.huawei.finance.stability.Api;

/**
 * 结构化 Chat 调用参数。
 *
 * <p>{@code temperature} 默认 0：v0.7 §3.3 要求生产仲裁固定解码参数、接近确定性配置，
 * 并与模型、规则、Skill、数据集版本共同记录。温度不是调参旋钮，是版本的一部分。
 *
 * @param model         模型标识（同时作为观测标签 {@code modelVersion}）
 * @param systemPrompt  系统提示词
 * @param userPrompt    用户提示词
 * @param maxTokens     输出上限
 * @param temperature   解码温度
 * @param jsonMode      是否要求 response_format=json_object
 * @param promptVersion 提示词 / Skill 版本，仅观测用；可为 null
 */
@Api
public record ChatRequest(
        String model,
        String systemPrompt,
        String userPrompt,
        int maxTokens,
        double temperature,
        boolean jsonMode,
        String promptVersion,
        String purpose) {

    public ChatRequest {
        purpose = purpose == null || purpose.isBlank() ? "arbitration" : purpose;
    }

    public ChatRequest(String model, String systemPrompt, String userPrompt,
                       int maxTokens, double temperature, boolean jsonMode, String promptVersion) {
        this(model, systemPrompt, userPrompt, maxTokens, temperature, jsonMode, promptVersion, "arbitration");
    }

    /** 兼容旧调用点：不带 promptVersion。 */
    public ChatRequest(String model, String systemPrompt, String userPrompt,
                       int maxTokens, double temperature, boolean jsonMode) {
        this(model, systemPrompt, userPrompt, maxTokens, temperature, jsonMode, null, "arbitration");
    }
}
