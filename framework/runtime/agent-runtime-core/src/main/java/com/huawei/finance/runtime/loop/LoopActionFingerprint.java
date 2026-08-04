package com.huawei.finance.runtime.loop;

import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.orchestrator.loop.LoopContracts.ActionType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Computes the platform-owned semantic identity used for repeat-action detection. */
public final class LoopActionFingerprint {
    private LoopActionFingerprint() { }

    public static String of(ActionType type, String target, Map<String, Object> parameters) {
        try {
            String payload = ContractJson.mapper().writeValueAsString(canonical(parameters));
            String material = type.name() + '|' + String.valueOf(target) + '|' + payload;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("LOOP_FINGERPRINT_INPUT_INVALID", e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(String.valueOf(key), canonical(item)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            List<Object> ordered = new ArrayList<>(list.size());
            list.forEach(item -> ordered.add(canonical(item)));
            return ordered;
        }
        return value;
    }
}
