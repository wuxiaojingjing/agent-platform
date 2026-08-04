package com.huawei.finance.runtime.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.context.ContextualQueryModel;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.registry.asset.AssetBundle;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Model implementation for natural-language contextual reference, omission and correction. */
public final class ModelContextualQueryRewriter implements ContextualQueryModel {

    private static final Logger log = LoggerFactory.getLogger(ModelContextualQueryRewriter.class);
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "originalQuery", "standaloneQuery", "eventType", "resolutions",
            "usedContextRefs", "unusedContextRefs", "slotUpdates", "invalidatedContextRefs",
            "confidence", "reasonCode", "stateVersion");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "originalQuery", "standaloneQuery", "eventType", "resolutions",
            "usedContextRefs", "unusedContextRefs", "slotUpdates", "invalidatedContextRefs",
            "confidence", "reasonCode", "stateVersion", "modelVersion", "promptVersion");

    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties properties;
    private final AssetBundle assets;
    private final ContractValidator validator;
    private final MeterRegistry meters;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public ModelContextualQueryRewriter(ModelGatewayClient gateway, ModelGatewayProperties properties,
                                        AssetBundle assets, ContractValidator validator,
                                        MeterRegistry meters) {
        this.gateway = gateway;
        this.properties = properties;
        this.assets = assets;
        this.validator = validator;
        this.meters = meters;
    }

    @Override
    public ContextualQuery rewrite(String query, IntentContext context) {
        var config = properties.getContextRewrite();
        var skill = assets.contextRewriteSkill();
        String key = key(query, context, properties.resolveLogicalModel(config),
                skill.getVersion(), assets.assetVersion());
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            metric("CACHE_HIT");
            return cached.value();
        }
        if (!gateway.available()) {
            metric("UNAVAILABLE");
            return ContextualQuery.identity(query, context.stateVersion(), context.evidenceRefs());
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("originalQuery", query == null ? "" : query);
        vars.put("stateVersion", String.valueOf(context.stateVersion()));
        vars.put("goal", context.goal() == null ? "" : context.goal());
        vars.put("confirmedFacts", json(context.confirmedFacts()));
        vars.put("knowledgeExamples", json(skill.getEligibleKnowledgeExamples()));
        vars.put("conversationHistory", json(context.conversationHistory()));
        vars.put("availableContext", json(context.evidence().stream()
                .filter(item -> item.sensitivity() != ContextEvidence.Sensitivity.RESTRICTED)
                .toList()));
        ChatRequest request = new ChatRequest(
                properties.resolveLogicalModel(config), skill.getSystem(), skill.renderUser(vars),
                config.getMaxTokens(), config.getTemperature(), true, skill.getVersion(),
                "context-rewrite");
        var response = gateway.chat(request);
        int attempts = Math.max(1, config.getMaxAttempts());
        for (int attempt = 2; !response.available() && attempt <= attempts; attempt++) {
            if (!awaitRetry(config.getRetryBackoffMs())) break;
            metric("RETRY");
            response = gateway.chat(request);
        }
        if (!response.available()) {
            metric("UNAVAILABLE");
            return ContextualQuery.identity(query, context.stateVersion(), context.evidenceRefs());
        }
        String raw = stripFence(response.value());
        var validation = validator.validateJson(SchemaRef.CONTEXTUAL_QUERY_OUTPUT, raw);
        if (!validation.valid()) {
            metric("INVALID_SCHEMA");
            log.warn("context rewrite 模型输出未通过契约 codes={}", schemaFailureCodes(raw));
            return ContextualQuery.identity(query, context.stateVersion(), context.evidenceRefs());
        }
        try {
            JsonNode node = ContractJson.mapper().readTree(raw);
            List<ContextualQuery.Resolution> resolutions = ContractJson.mapper().convertValue(
                    node.path("resolutions"), new TypeReference<>() { });
            List<String> used = ContractJson.mapper().convertValue(
                    node.path("usedContextRefs"), new TypeReference<>() { });
            List<String> unused = ContractJson.mapper().convertValue(
                    node.path("unusedContextRefs"), new TypeReference<>() { });
            List<String> invalidated = ContractJson.mapper().convertValue(
                    node.path("invalidatedContextRefs"), new TypeReference<>() { });
            Map<String, Object> updates = ContractJson.mapper().convertValue(
                    node.path("slotUpdates"), new TypeReference<>() { });
            ContextualQuery result = new ContextualQuery(
                    node.path("originalQuery").asText(), node.path("standaloneQuery").asText(),
                    ContextualQuery.EventType.valueOf(node.path("eventType").asText()),
                    resolutions, used, unused, updates, invalidated,
                    node.path("confidence").asDouble(), node.path("reasonCode").asText(),
                    node.path("stateVersion").asLong(),
                    properties.resolveLogicalModel(config), skill.getVersion());
            int maxEntries = Math.max(0, config.getCacheMaxEntries());
            if (maxEntries > 0 && cache.size() >= maxEntries) cache.clear();
            if (maxEntries > 0) {
                cache.put(key, new Cached(result,
                        Instant.now().plusSeconds(Math.max(0, config.getCacheTtlSeconds()))));
            }
            metric(result.consumedContext() ? "CONSUMED" : "NOT_REQUIRED");
            return result;
        } catch (RuntimeException | java.io.IOException invalid) {
            metric("INVALID_OUTPUT");
            return ContextualQuery.identity(query, context.stateVersion(), context.evidenceRefs());
        }
    }

    private void metric(String outcome) {
        if (meters != null) meters.counter(AgentMetrics.CONTEXT_REWRITE,
                AgentMetrics.TAG_OUTCOME, outcome, "implementation", "MODEL").increment();
    }

    private static boolean awaitRetry(int backoffMs) {
        if (backoffMs <= 0) return true;
        try {
            Thread.sleep(backoffMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String key(String query, IntentContext context, String model,
                              String promptVersion, String assetVersion) {
        String material = json(Map.of(
                "query", query == null ? "" : query,
                "stateVersion", context.stateVersion(),
                "evidence", context.evidence(),
                "model", model, "promptVersion", promptVersion, "assetVersion", assetVersion));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(Object value) {
        try { return ContractJson.mapper().writeValueAsString(value); }
        catch (Exception e) { return "null"; }
    }

    private static String stripFence(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        return value;
    }

    static List<String> schemaFailureCodes(String raw) {
        try {
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.startsWith("{") && !trimmed.endsWith("}")) {
                return List.of("TRUNCATED_JSON");
            }
            JsonNode node = ContractJson.mapper().readTree(raw);
            if (node == null || !node.isObject()) return List.of("NON_OBJECT");
            List<String> codes = new ArrayList<>();
            for (String field : REQUIRED_FIELDS) {
                if (!node.has(field)) codes.add("MISSING:" + field);
            }
            node.fieldNames().forEachRemaining(field -> {
                if (!ALLOWED_FIELDS.contains(field) && !codes.contains("ADDITIONAL_PROPERTY")) {
                    codes.add("ADDITIONAL_PROPERTY");
                }
            });
            requireText(node, "originalQuery", codes);
            requireText(node, "standaloneQuery", codes);
            requireText(node, "eventType", codes);
            requireArray(node, "resolutions", codes);
            requireArray(node, "usedContextRefs", codes);
            requireArray(node, "unusedContextRefs", codes);
            requireObject(node, "slotUpdates", codes);
            requireArray(node, "invalidatedContextRefs", codes);
            if (node.has("confidence") && !node.path("confidence").isNumber()) {
                codes.add("TYPE:confidence");
            }
            requireText(node, "reasonCode", codes);
            if (node.has("stateVersion") && !node.path("stateVersion").isIntegralNumber()) {
                codes.add("TYPE:stateVersion");
            }
            return codes.isEmpty() ? List.of("SCHEMA_CONSTRAINT") : List.copyOf(codes);
        } catch (Exception invalidJson) {
            return List.of("INVALID_JSON");
        }
    }

    private static void requireText(JsonNode node, String field, List<String> codes) {
        if (node.has(field) && !node.path(field).isTextual()) codes.add("TYPE:" + field);
    }

    private static void requireArray(JsonNode node, String field, List<String> codes) {
        if (node.has(field) && !node.path(field).isArray()) codes.add("TYPE:" + field);
    }

    private static void requireObject(JsonNode node, String field, List<String> codes) {
        if (node.has(field) && !node.path(field).isObject()) codes.add("TYPE:" + field);
    }

    private record Cached(ContextualQuery value, Instant expiresAt) { }
}
