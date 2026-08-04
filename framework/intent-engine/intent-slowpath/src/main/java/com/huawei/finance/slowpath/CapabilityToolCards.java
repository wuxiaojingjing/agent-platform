package com.huawei.finance.slowpath;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.openjiuwen.core.foundation.tool.ToolCard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 能力卡 → OJ 工具卡投影。
 *
 * <p>只投影模型选工具时用得上的字段。风险等级、幂等语义、超时这些**不放进描述**：
 * 它们是中控的执法依据，写进给模型看的文本只会诱导「模型说它是低危的」这种推理。
 *
 * <p>快路径与慢路径 {@link ProposalTool} 共用这一份投影，避免两份描述漂移导致
 * 「同一句话在快慢路径下选中了不同能力」。
 */
public final class CapabilityToolCards {

    private CapabilityToolCards() {
    }

    public static ToolCard toToolCard(CapabilityCard capability) {
        StringBuilder description = new StringBuilder();
        description.append("能力名称：").append(capability.name());
        if (capability.description() != null) {
            description.append("。").append(capability.description());
        }
        description.append("。能力标识：").append(capability.capabilityId());
        if (!capability.supportedIntents().isEmpty()) {
            description.append("。适用意图：").append(String.join("、", capability.supportedIntents()));
        }
        String toolId = toolId(capability.capabilityId());
        return ToolCard.builder()
                .id(toolId)
                .name(toolId)
                .description(description.toString())
                .inputParams(objectSchema(capability.inputSchema()))
                .build();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> schema) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (schema != null) {
            normalized.putAll(schema);
        }
        normalized.putIfAbsent("type", "object");
        normalized.putIfAbsent("properties", Map.of());
        return Map.copyOf(normalized);
    }

    static String toolId(String capabilityId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(capabilityId.getBytes(StandardCharsets.UTF_8));
            return "capability_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
