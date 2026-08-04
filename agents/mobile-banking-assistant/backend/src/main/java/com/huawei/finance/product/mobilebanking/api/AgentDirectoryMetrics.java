package com.huawei.finance.product.mobilebanking.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 把控制台统一 Agent 视图导出为低基数 Gauge。 */
@Component
public class AgentDirectoryMetrics {

    private static final String METRIC = "huawei.finance.agent.directory.count";
    private static final String[] DIMENSIONS = {
            "configured", "online", "unhealthy", "offline", "implemented", "scaffold"
    };

    private final AgentDirectoryController directory;

    public AgentDirectoryMetrics(AgentDirectoryController directory, MeterRegistry registry) {
        this.directory = directory;
        for (String dimension : DIMENSIONS) {
            Gauge.builder(METRIC, this, metrics -> metrics.value(dimension))
                    .tag("dimension", dimension)
                    .description("Configured and discovered Agent counts")
                    .register(registry);
        }
    }

    @SuppressWarnings("unchecked")
    private double value(String dimension) {
        Object raw = directory.agents().get("summary");
        if (!(raw instanceof Map<?, ?> summary)) {
            return 0;
        }
        Object value = ((Map<String, Object>) summary).get(dimension);
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
