package com.huawei.finance.common.context;

import com.huawei.finance.stability.Api;
import java.util.Map;

/** Bounded module I/O for local console diagnosis; callers must not export it to spans or metrics. */
@Api
public record RuntimeModuleStep(
        String module,
        String operation,
        String role,
        Map<String, Object> input,
        Map<String, Object> output,
        String outcome,
        Long durationMs) {

    public RuntimeModuleStep {
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
    }
}
