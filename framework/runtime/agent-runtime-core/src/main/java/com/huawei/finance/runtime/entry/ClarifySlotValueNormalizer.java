package com.huawei.finance.runtime.entry;

import com.huawei.finance.orchestrator.continuation.SlotValueNormalizer;
import com.huawei.finance.registry.asset.ClarifyConfig;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Uses the configured clarify collection without exposing aliases as UI options. */
public final class ClarifySlotValueNormalizer implements SlotValueNormalizer {

    private final Supplier<ClarifyConfig> clarify;

    public ClarifySlotValueNormalizer(Supplier<ClarifyConfig> clarify) {
        this.clarify = Objects.requireNonNull(clarify, "clarify");
    }

    @Override
    public Object normalize(String slot, Object value, List<String> allowedValues) {
        if (slot == null || value == null || allowedValues == null || allowedValues.isEmpty()) {
            return value;
        }
        String text = String.valueOf(value).trim();
        if (allowedValues.contains(text)) return text;
        ClarifyConfig.SlotClarify definition = clarify.get().getSlots().get(slot);
        if (definition == null) return value;
        String canonical = definition.getValueMapping().getOrDefault(text, text);
        return allowedValues.stream()
                .filter(option -> canonical.equals(
                        definition.getValueMapping().getOrDefault(option, option)))
                .<Object>map(option -> option)
                .findFirst()
                .orElse(value);
    }
}
