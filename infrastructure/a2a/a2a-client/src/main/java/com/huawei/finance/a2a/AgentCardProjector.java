package com.huawei.finance.a2a;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * 把各资产根下 capabilities/agents 下 yaml 投影成 AgentCard（架构草案 v0.3 §6 / D1）。
 *
 * <p>投影而不是另写一份卡:节点的父卡已经在资产里，AgentCard 是它对外可见的那一面。
 * 另写一份的话，两处会各自演进——而不一致的表现是「路由表说这个域能办，节点说这不是我的事」。
 *
 * <p>只投 {@code type: AGENT} 的条目。纯执行器的能力卡（那些 {@code granularity: CAPABILITY}
 * 的行）不进路由表:A2A 只寻址节点。
 */
public class AgentCardProjector {

    private static final Logger log = LoggerFactory.getLogger(AgentCardProjector.class);

    /** 卡上 {@code capabilityId} 形如 {@code agent.account}，域码取后缀。 */
    private static final String AGENT_ID_PREFIX = "agent.";

    private final List<String> locationPatterns;

    public AgentCardProjector(String locationPattern) {
        this(List.of(locationPattern));
    }

    public AgentCardProjector(List<String> locationPatterns) {
        this.locationPatterns = List.copyOf(locationPatterns);
    }

    /**
     * 扫描并投影。
     *
     * <p>单个文件解析失败只跳过并告警，不整体失败:26 个域的卡由各领域方维护，
     * 一个域的 YAML 写坏了不该让整个网关起不来——那会把「一个域的资产错」放大成「全平台不可用」。
     * 代价是那个域静默缺席，因此这里必须告警，且 {@link AgentCardRegistry#size()} 会有人核。
     */
    @SuppressWarnings("unchecked")
    public List<AgentCard> project() {
        List<AgentCard> cards = new ArrayList<>();
        Yaml yaml = new Yaml();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String locationPattern : locationPatterns) {
            try {
                Resource[] resources = resolver.getResources(locationPattern);
                for (Resource resource : resources) {
                    try (InputStream in = resource.getInputStream()) {
                        Object loaded = yaml.load(in);
                        if (!(loaded instanceof List<?> rows)) {
                            continue;
                        }
                        for (Object row : rows) {
                            if (row instanceof Map<?, ?> map) {
                                toCard((Map<String, Object>) map).ifPresent(cards::add);
                            }
                        }
                    } catch (RuntimeException | IOException e) {
                        log.warn("Agent 卡解析失败，该域将不在路由表中 file={} cause={}",
                                resource.getFilename(), e.toString());
                    }
                }
            } catch (IOException e) {
                log.error("扫描 Agent 卡失败 pattern={} cause={}", locationPattern, e.toString());
            }
        }
        log.info("AgentCard 投影完成 数量={} patterns={}", cards.size(), locationPatterns.size());
        return cards;
    }

    private static java.util.Optional<AgentCard> toCard(Map<String, Object> row) {
        String type = str(row.get("type"));
        if (!"AGENT".equals(type)) {
            return java.util.Optional.empty();
        }
        String agentId = str(row.get("capabilityId"));
        if (agentId == null || !agentId.startsWith(AGENT_ID_PREFIX)) {
            return java.util.Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<String> domains = row.get("domains") instanceof List<?> list
                ? list.stream().map(AgentCardProjector::str).toList() : List.of();

        return java.util.Optional.of(new AgentCard(
                agentId,
                domains.isEmpty() ? agentId.substring(AGENT_ID_PREFIX.length()) : domains.get(0),
                str(row.get("name")),
                str(row.get("description")),
                domains,
                str(row.get("riskLevel")),
                row.get("timeoutMs") instanceof Number n ? n.longValue() : 0L,
                str(row.get("owner")),
                str(row.get("version")),
                statusOf(str(row.get("status"))),
                Map.of(), contextContractOf(str(row.get("contextContract"))),
                str(row.get("runtime"))));
    }

    /**
     * 状态映射。
     *
     * <p>认不出的状态按 SCAFFOLD 处理，不按 ACTIVE:把一张状态写错的卡当成「已建成可接委托」，
     * 委托会真的投过去；当成「未交付」最多是这个域暂时不可达，能被发现且不动钱。
     */
    private static AgentCard.Status statusOf(String raw) {
        if (raw == null) {
            return AgentCard.Status.SCAFFOLD;
        }
        if ("DISABLED".equalsIgnoreCase(raw.trim())) {
            return AgentCard.Status.SCAFFOLD;
        }
        try {
            return AgentCard.Status.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Agent 卡状态无法识别，按未交付处理 status={}", raw);
            return AgentCard.Status.SCAFFOLD;
        }
    }

    private static AgentCard.ContextContract contextContractOf(String raw) {
        if (raw == null || raw.isBlank()) return AgentCard.ContextContract.FULL;
        try {
            return AgentCard.ContextContract.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return AgentCard.ContextContract.STATELESS_READ_ONLY;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
