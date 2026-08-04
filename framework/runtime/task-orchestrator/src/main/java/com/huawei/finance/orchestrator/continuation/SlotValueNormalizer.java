package com.huawei.finance.orchestrator.continuation;

import java.util.List;

/** Resolves a configured collection member to one of the runtime-declared slot values. */
@FunctionalInterface
public interface SlotValueNormalizer {

    SlotValueNormalizer IDENTITY = (slot, value, allowedValues) -> value;

    Object normalize(String slot, Object value, List<String> allowedValues);
}
