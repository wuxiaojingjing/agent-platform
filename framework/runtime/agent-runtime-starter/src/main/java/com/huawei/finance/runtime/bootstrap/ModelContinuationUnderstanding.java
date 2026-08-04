package com.huawei.finance.runtime.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.orchestrator.continuation.ContinuationUnderstandingModel;
import com.huawei.finance.orchestrator.continuation.ContinuationModelCache;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModelContinuationUnderstanding implements ContinuationUnderstandingModel {
    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties properties;
    private final AssetBundle assets;
    private final ContractValidator validator;
    private final ContinuationModelCache cache;
    private final MeterRegistry meters;

    public ModelContinuationUnderstanding(ModelGatewayClient gateway, ModelGatewayProperties properties,
                                          AssetBundle assets, ContractValidator validator,
                                          ContinuationModelCache cache, MeterRegistry meters) {
        this.gateway = gateway; this.properties = properties; this.assets = assets; this.validator = validator;
        this.cache = cache == null ? ContinuationModelCache.disabled() : cache;
        this.meters = meters;
    }

    @Override public Resolution understand(String tenantId, String agentId, String sessionId,
                                           String input, Context context) {
        return understand(tenantId, agentId, sessionId, input, context, null);
    }

    @Override public Resolution understand(String tenantId, String agentId, String sessionId,
                                           String input, Context context, IntentContext intentContext) {
        var skill = assets.continuationSkill();
        var config = properties.getContinuation();
        String cacheKey = key(tenantId, agentId, sessionId, input, context, intentContext,
                properties.resolveLogicalModel(config), skill.getVersion(), assets.assetVersion());
        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            cacheMetric("HIT");
            return cached.get();
        }
        cacheMetric("MISS");
        if (!gateway.available()) {
            modelMetric("UNAVAILABLE");
            return unresolved("MODEL_UNAVAILABLE");
        }
        Map<String,String> vars = new LinkedHashMap<>();
        vars.put("userInput", input == null ? "" : input);
        vars.put("foreground", json(context.foreground()));
        vars.put("suspended", json(context.suspended()));
        vars.put("pendingSwitch", json(context.pendingSwitch()));
        vars.put("allowedEvents", context.pendingSwitch() != null
                ? json(context.pendingSwitch().allowedEvents())
                : context.foreground() == null
                    ? (context.suspended().isEmpty() ? "[]" : json(java.util.List.of(Event.RESUME_SUSPENDED)))
                    : json(context.foreground().allowedEvents()));
        vars.put("allowedSlotsAndValues", context.foreground() == null ? "{}" : json(context.foreground().allowedSlotsAndValues()));
        vars.put("conversationHistory", intentContext == null
                ? "[]" : json(intentContext.conversationHistory()));
        vars.put("availableContext", intentContext == null ? "[]" : json(intentContext.evidence()));
        vars.put("contextStateVersion", intentContext == null
                ? "-1" : String.valueOf(intentContext.stateVersion()));
        vars.put("knowledgeExamples", json(skill.getEligibleKnowledgeExamples()));
        ChatRequest request = new ChatRequest(properties.resolveLogicalModel(config), skill.getSystem(),
                skill.renderUser(vars), config.getMaxTokens(), config.getTemperature(), true,
                skill.getVersion(), "continuation");
        var result = gateway.chat(request);
        int attempts = Math.max(1, config.getMaxAttempts());
        for (int attempt = 2; !result.available() && attempt <= attempts; attempt++) {
            if (!awaitRetry(config.getRetryBackoffMs())) break;
            modelMetric("RETRY");
            result = gateway.chat(request);
        }
        if (!result.available()) {
            modelMetric("UNAVAILABLE");
            return unresolved("MODEL_UNAVAILABLE");
        }
        String raw = stripFence(result.value());
        if (!validator.validateJson(SchemaRef.CONTINUATION_MODEL_OUTPUT, raw).valid()) {
            modelMetric("INVALID_SCHEMA");
            return unresolved("INVALID_MODEL_OUTPUT");
        }
        try {
            JsonNode node = ContractJson.mapper().readTree(raw);
            Map<String,Object> slots = ContractJson.mapper().convertValue(node.path("slotUpdates"), Map.class);
            Resolution resolution = new Resolution(Event.valueOf(node.path("event").asText()), text(node, "targetRef"), slots,
                    text(node, "newGoalSpan"), node.path("confidence").asDouble(), node.path("reasonCode").asText(),
                    ConfirmationStrength.valueOf(node.path("confirmationStrength").asText()));
            modelMetric(resolution.event() == Event.UNRESOLVED ? "MODEL_UNRESOLVED" : "RESOLVED");
            if (resolution.event() != Event.UNRESOLVED) cache.put(cacheKey, resolution);
            return resolution;
        } catch (Exception e) {
            modelMetric("INVALID_OUTPUT");
            return unresolved("INVALID_MODEL_OUTPUT");
        }
    }

    private void cacheMetric(String outcome) {
        if (meters != null) meters.counter(AgentMetrics.CONTINUATION_MODEL_CACHE,
                AgentMetrics.TAG_OUTCOME, outcome).increment();
    }

    private void modelMetric(String outcome) {
        if (meters != null) meters.counter(AgentMetrics.CONTINUATION_MODEL,
                AgentMetrics.TAG_OUTCOME, outcome).increment();
    }

    private static String key(String tenantId, String agentId, String sessionId, String input, Context context,
                              IntentContext intentContext,
                              String model, String promptVersion, String assetVersion) {
        Map<String, Object> keyMaterial = new LinkedHashMap<>();
        keyMaterial.put("tenantId", safe(tenantId));
        keyMaterial.put("agentId", safe(agentId));
        keyMaterial.put("sessionId", safe(sessionId));
        keyMaterial.put("input", safe(input));
        keyMaterial.put("context", context);
        keyMaterial.put("intentContextVersion", intentContext == null ? -1 : intentContext.stateVersion());
        keyMaterial.put("conversationHistory", intentContext == null
                ? java.util.List.of() : intentContext.conversationHistory());
        keyMaterial.put("model", safe(model));
        keyMaterial.put("promptVersion", safe(promptVersion));
        keyMaterial.put("assetVersion", safe(assetVersion));
        String material = json(Map.copyOf(keyMaterial));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String json(Object value) {
        try { return ContractJson.mapper().writeValueAsString(value); }
        catch (Exception e) { return "null"; }
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field); return value == null || value.isNull() ? null : value.asText();
    }
    private static String stripFence(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("```")) value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return value;
    }
    private static boolean awaitRetry(int millis) {
        if (millis <= 0) return true;
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    private static Resolution unresolved(String reason) {
        return new Resolution(Event.UNRESOLVED, null, Map.of(), null, 0, reason);
    }
}
