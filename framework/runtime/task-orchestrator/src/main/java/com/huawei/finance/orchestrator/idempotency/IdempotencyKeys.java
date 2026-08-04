package com.huawei.finance.orchestrator.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等键生成（实施架构 §8.4）。
 *
 * <p>键由任务标识、能力标识与参数内容共同决定，是确定性的：同一个任务因网络超时重发时
 * 算出同一把键，凭 {@code agent_idempotency} 的主键冲突就能挡住重复执行。
 * 如果用随机 UUID，重发会得到新键，重复扣款照样发生——幂等就成了摆设。
 *
 * <p>参数变了键就变，这也是对的：改了金额的转账是另一笔业务，不该被前一笔的记录挡住。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String of(String taskId, String capabilityId, Map<String, Object> parameters) {
        // 参数按键排序后参与摘要。Map 迭代顺序在不同实现下不一致，
        // 不排序会让同样的参数算出不同的键
        TreeMap<String, Object> sorted = new TreeMap<>(parameters == null ? Map.of() : parameters);

        StringBuilder raw = new StringBuilder(taskId).append('|').append(capabilityId);
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            raw.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return "idem-" + sha256(raw.toString()).substring(0, 32);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
