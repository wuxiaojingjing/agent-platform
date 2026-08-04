package com.huawei.finance.nacos.discovery;

import java.util.List;
import java.util.Set;

/**
 * 注册中心里的一个智能体实例。
 *
 * @param serviceName 注册的服务名，通常一个领域进程一个
 * @param baseUrl     可直接拼 {@code /v1/query} 的基址
 * @param healthy     Nacos 判定的健康状态。**不健康的实例照样列出来**：
 *                    「有这个 Agent 但它不健康」与「压根没有这个 Agent」是两回事，
 *                    排障时最需要区分的恰恰是这两者
 * @param capabilities 实例元数据里声明的能力清单
 */
public record AgentInstance(
        String serviceName,
        String agentId,
        String implementationMode,
        String protocolVersion,
        String baseUrl,
        boolean healthy,
        Set<String> capabilities) {

    public AgentInstance {
        capabilities = Set.copyOf(capabilities);
    }

    public AgentInstance(String serviceName, String baseUrl, boolean healthy, Set<String> capabilities) {
        this(serviceName, serviceName, "unknown", "1.0", baseUrl, healthy, capabilities);
    }

    /** 元数据里能力清单用逗号分隔，这里解析。空串与多余空格都当成没写。 */
    public static Set<String> parseCapabilities(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }
}
