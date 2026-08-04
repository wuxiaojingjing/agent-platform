package com.huawei.finance.runtime.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.response.ResponseTextModel;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAI-compatible adapter for response text realization. */
public final class GatewayResponseTextModel implements ResponseTextModel {

    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties properties;
    private final ObjectMapper mapper;

    public GatewayResponseTextModel(ModelGatewayClient gateway, ModelGatewayProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public Result realize(Request request) {
        if (!gateway.available()) return Result.unavailable("response-model-unavailable");
        String model = firstNonBlank(request.model(), properties.getResponse().getModel(),
                properties.getArbitration().getModel());
        if (model == null) return Result.unavailable("response-model-not-configured");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", request.mode().name());
        input.put("responsePhase", request.phase().name());
        input.put("visibleConversation", request.conversationHistory());
        input.put("currentUserMessage", request.userQuery());
        input.put("committedFacts", request.committedFacts());
        input.put("riskNoticeCodes", request.riskNoticeCodes());
        input.put("approvedText", request.baseText());
        if (request.mode() == Enums.RenderMode.MODEL_SELECT) {
            input.put("approvedTemplates", request.approvedTemplates());
            input.put("outputSchema", Map.of("templateKey", "one key from approvedTemplates"));
        } else {
            input.put("outputSchema", Map.of("text", "user-visible text only"));
        }

        try {
            GatewayResult<String> response = gateway.chat(new ChatRequest(
                    model, request.systemPrompt(), mapper.writeValueAsString(input),
                    request.maxTokens(), request.temperature(), true,
                    request.promptVersion(), "response-realization"));
            if (!response.available() || response.value() == null) {
                return Result.unavailable(response.reason());
            }
            JsonNode output = mapper.readTree(response.value());
            if (request.mode() == Enums.RenderMode.MODEL_SELECT) {
                String key = output.path("templateKey").asText("").trim();
                return key.isEmpty() ? Result.unavailable("template-key-missing")
                        : Result.template(key, model);
            }
            String text = output.path("text").asText("").trim();
            return text.isEmpty() ? Result.unavailable("response-text-missing")
                    : Result.text(text, model);
        } catch (Exception error) {
            return Result.unavailable("response-model-invalid-output");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
