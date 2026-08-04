package com.huawei.finance.product.mobilebanking.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 从标准 Agent 目录投影控制台清单；只表示已配置，不表示进程在线。 */
final class ConfiguredAgentCatalog {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfiguredAgentCatalog() {
    }

    static List<ConfiguredAgent> load(Path sharedRoot, List<CapabilityCard> cards) {
        Map<String, TreeSet<String>> capabilities = new LinkedHashMap<>();
        for (CapabilityCard card : cards) {
            if (card.parentCapabilityId() != null && !card.parentCapabilityId().isBlank()) {
                capabilities.computeIfAbsent(card.parentCapabilityId(), ignored -> new TreeSet<>())
                        .add(card.capabilityId());
            }
        }

        Map<String, ConfiguredAgent> agents = new LinkedHashMap<>();
        for (Path assets : AgentAssetLocations.discoverAgentAssetRoots(sharedRoot)) {
            Path definition = assets.getParent().resolve("agent.yaml");
            if (!Files.isRegularFile(definition)) {
                continue;
            }
            ConfiguredAgent agent = read(definition, capabilities);
            agents.put(agent.agentId(), agent);
        }
        return agents.values().stream()
                .sorted(Comparator.comparing(ConfiguredAgent::agentId))
                .toList();
    }

    private static ConfiguredAgent read(
            Path definition, Map<String, TreeSet<String>> capabilities) {
        try {
            JsonNode node = YAML.readTree(definition.toFile()).path("agent");
            JsonNode implementation = YAML.readTree(definition.toFile()).path("implementation");
            String agentId = requiredText(node, "id", definition);
            String displayName = requiredText(node, "displayName", definition);
            return new ConfiguredAgent(
                    agentId,
                    displayName,
                    strings(node.path("roles")),
                    strings(node.path("domains")),
                    implementation.path("mode").asText("scaffold").trim().toLowerCase(),
                    List.copyOf(capabilities.getOrDefault(agentId, new TreeSet<>())));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 Agent 定义失败：" + definition, e);
        }
    }

    private static String requiredText(JsonNode node, String field, Path definition) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(definition + " 缺少 agent." + field);
        }
        return value;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    record ConfiguredAgent(
            String agentId,
            String displayName,
            List<String> roles,
            List<String> domains,
            String implementationMode,
            List<String> capabilities) {
    }
}
