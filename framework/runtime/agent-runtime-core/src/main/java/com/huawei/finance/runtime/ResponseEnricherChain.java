package com.huawei.finance.runtime;

import com.huawei.finance.runtime.extension.AgentRuntimeExtensionException;
import com.huawei.finance.runtime.extension.ResponseEnricher;
import com.huawei.finance.runtime.extension.ResponseEnrichmentContext;
import com.huawei.finance.runtime.extension.RuntimeExtensionFailurePolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 执行有序回复增强；扩展的唯一输出是渲染槽位。 */
final class ResponseEnricherChain {

    private static final Logger log = LoggerFactory.getLogger(ResponseEnricherChain.class);

    private final List<ResponseEnricher> enrichers;

    ResponseEnricherChain(List<ResponseEnricher> enrichers) {
        this.enrichers = enrichers == null ? List.of() : List.copyOf(enrichers);
    }

    Map<String, Object> enrich(ResponseEnrichmentContext baseline) {
        Map<String, Object> platformDefault = baseline.renderSlots();
        Map<String, Object> current = platformDefault;
        for (ResponseEnricher enricher : enrichers) {
            try {
                Map<String, Object> enriched = Objects.requireNonNull(
                        enricher.enrich(baseline.withRenderSlots(current)),
                        "ResponseEnricher 返回 null");
                current = Map.copyOf(enriched);
            } catch (RuntimeException failure) {
                RuntimeExtensionFailurePolicy policy = Objects.requireNonNullElse(
                        enricher.failurePolicy(), RuntimeExtensionFailurePolicy.SKIP_AND_RECORD);
                if (policy == RuntimeExtensionFailurePolicy.FAIL_CLOSED) {
                    throw new AgentRuntimeExtensionException(
                            "回复扩展失败: " + enricher.extensionId(), failure);
                }
                if (policy == RuntimeExtensionFailurePolicy.FALLBACK_DEFAULT) {
                    current = platformDefault;
                }
                log.warn("回复扩展已按策略收口 extension={} policy={} cause={}",
                        enricher.extensionId(), policy, failure.toString());
            }
        }
        return current;
    }
}
