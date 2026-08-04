package com.huawei.finance.runtime.invocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 目标会话由来源 Agent、来源会话引用和根任务共同确定，且不泄露任一原值。 */
public final class TargetSessionKeys {
    private TargetSessionKeys() {
    }

    public static String of(String sourceAgentId, String sourceSessionRef, String rootTaskId) {
        String material = value(sourceAgentId) + '\u0000' + value(sourceSessionRef)
                + '\u0000' + value(rootTaskId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "a2a:" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
