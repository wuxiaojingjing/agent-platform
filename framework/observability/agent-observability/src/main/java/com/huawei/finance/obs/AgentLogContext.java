package com.huawei.finance.obs;

import java.util.Map;
import org.slf4j.MDC;

/** Restorable MDC scope containing identifiers safe for operational logs. */
public final class AgentLogContext implements AutoCloseable {

    private final Map<String, String> previous;

    private AgentLogContext(Map<String, String> values) {
        this.previous = MDC.getCopyOfContextMap();
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                MDC.put(key, value);
            }
        });
    }

    public static AgentLogContext open(Map<String, String> values) {
        return new AgentLogContext(values);
    }

    @Override
    public void close() {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
