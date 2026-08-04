package com.huawei.finance.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record AgentDefinition(String id, List<String> domains, String mode, String artifact) {

    static AgentDefinition load(Path home) throws IOException {
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(home.resolve("agent.yaml").toFile());
        JsonNode agent = root.path("agent");
        JsonNode implementation = root.path("implementation");
        String id = required(agent, "id", home);
        List<String> domains = new ArrayList<>();
        agent.path("domains").forEach(value -> domains.add(value.asText().trim()));
        if (domains.isEmpty() || domains.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException(home.resolve("agent.yaml") + " 必须声明非空 agent.domains");
        }
        String mode = required(implementation, "mode", home).toLowerCase();
        if (!List.of("application", "extension", "scaffold").contains(mode)) {
            throw new IllegalStateException("不支持的 implementation.mode：" + mode);
        }
        String artifact = implementation.path("artifact").asText("").trim();
        if (!mode.equals("scaffold") && artifact.isEmpty()) {
            throw new IllegalStateException(mode + " Agent 必须声明 implementation.artifact");
        }
        if (mode.equals("extension") && blank(System.getenv("LOADER_PATH"))) {
            throw new IllegalStateException("extension Agent 必须通过 LOADER_PATH 提供领域扩展 JAR");
        }
        return new AgentDefinition(id, List.copyOf(domains), mode, artifact);
    }

    private static String required(JsonNode node, String field, Path home) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(home.resolve("agent.yaml") + " 缺少 " + field);
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
