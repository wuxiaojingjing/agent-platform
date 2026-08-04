package com.huawei.finance.product.mobilebanking.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 控制台用的 Redis 缓存浏览器。
 *
 * <p>只扫本平台约定前缀。出口决策主键是 SHA-256；可读 query 在
 * {@code decision-meta:} sidecar（写入时附带）。旧条目没有 meta 时页面会标明。
 */
@Component
public class CacheInspector {

    private static final Logger log = LoggerFactory.getLogger(CacheInspector.class);
    private static final int DEFAULT_LIMIT = 200;
    private static final int VALUE_PREVIEW_CHARS = 8_000;
    private static final String DECISION_PREFIX = "huawei-finance-agent:route-decision:v3:";
    private static final String DECISION_META_PREFIX = "huawei-finance-agent:decision-meta:";

    private static final List<BucketKind> KINDS = List.of(
            new BucketKind("decision", "出口决策", DECISION_PREFIX + "*"),
            new BucketKind("turns", "会话轮次投影", "huawei-finance-agent:*:turns:*"),
            new BucketKind("affinity", "会话亲和", "huawei-finance-agent:*:affinity:session:*"),
            new BucketKind("lock", "会话锁", "huawei-finance-agent:*:lock:session:*"),
            new BucketKind("other", "其它平台键", "huawei-finance-agent:*")
    );

    private final ObjectProvider<RedissonClient> redisson;
    private final ObjectMapper mapper;

    public CacheInspector(ObjectProvider<RedissonClient> redisson) {
        this.redisson = redisson;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    public Map<String, Object> snapshot(String kindFilter, int limit) {
        RedissonClient client = redisson.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        if (client == null) {
            body.put("available", false);
            body.put("message", "未装配 RedissonClient，出口缓存可能走 DecisionCache.disabled()");
            body.put("kinds", List.of());
            body.put("entries", List.of());
            return body;
        }

        int cap = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BucketKind kind : KINDS) {
            if (kindFilter != null && !kindFilter.isBlank() && !kind.id().equals(kindFilter)) {
                continue;
            }
            int before = entries.size();
            scan(client, kind, entries, cap - entries.size());
            counts.put(kind.id(), entries.size() - before);
            if (entries.size() >= cap) {
                break;
            }
        }

        entries.sort(Comparator
                .comparing((Map<String, Object> e) -> String.valueOf(e.get("kind")))
                .thenComparing(e -> {
                    Object q = e.get("query");
                    return q == null || String.valueOf(q).isBlank() ? "\uFFFF" : String.valueOf(q);
                })
                .thenComparing(e -> String.valueOf(e.get("key"))));

        body.put("available", true);
        body.put("limit", cap);
        body.put("truncated", entries.size() >= cap);
        body.put("counts", counts);
        body.put("kinds", KINDS.stream().map(k -> Map.of(
                "id", k.id(),
                "label", k.label(),
                "pattern", k.pattern())).toList());
        body.put("entries", entries);
        body.put("note", "出口决策：列表「问法」来自 decision-meta sidecar；「缓存内容」是 RouteDecision。"
                + " 旧键无 meta 时需再触发一次写入。turns 是会话轮次投影，真源在 Postgres。");
        return body;
    }

    public boolean delete(String key) {
        RedissonClient client = redisson.getIfAvailable();
        if (client == null || key == null || key.isBlank()) {
            return false;
        }
        if (!key.startsWith("huawei-finance-agent:")) {
            throw new IllegalArgumentException("只允许删除本平台前缀键");
        }
        boolean deleted = client.getBucket(key).delete();
        if (key.startsWith(DECISION_PREFIX)) {
            client.getBucket(DECISION_META_PREFIX + key.substring(DECISION_PREFIX.length())).delete();
        }
        return deleted;
    }

    private void scan(RedissonClient client, BucketKind kind, List<Map<String, Object>> out, int remaining) {
        if (remaining <= 0) {
            return;
        }
        try {
            int taken = 0;
            for (String key : client.getKeys().getKeysByPattern(kind.pattern(), remaining)) {
                if ("other".equals(kind.id()) && !isOtherOnly(key)) {
                    continue;
                }
                out.add(readEntry(client, kind, key));
                taken++;
                if (taken >= remaining) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("扫描缓存失败 kind={} cause={}", kind.id(), e.toString());
        }
    }

    private static boolean isOtherOnly(String key) {
        return !(key.startsWith(DECISION_PREFIX)
                || key.startsWith(DECISION_META_PREFIX)
                || key.contains(":turns:")
                || key.contains(":affinity:session:")
                || key.contains(":lock:session:"));
    }

    private Map<String, Object> readEntry(RedissonClient client, BucketKind kind, String key) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind.id());
        row.put("kindLabel", kind.label());
        row.put("key", key);
        try {
            RBucket<Object> bucket = client.getBucket(key);
            long ttlMs = bucket.remainTimeToLive();
            row.put("ttlMillis", ttlMs);
            row.put("ttlLabel", formatTtl(ttlMs));
            Object raw = bucket.get();
            if (raw == null) {
                row.put("value", null);
                row.put("valuePreview", null);
                return row;
            }
            String text = raw instanceof String s ? s : String.valueOf(raw);
            row.put("valuePreview", truncate(pretty(text)));
            row.put("bytes", text.length());
            if (text.length() <= VALUE_PREVIEW_CHARS) {
                row.put("value", tryParse(text));
            } else {
                row.put("value", null);
                row.put("truncatedValue", true);
            }
            if ("decision".equals(kind.id()) && key.startsWith(DECISION_PREFIX)) {
                attachDecisionBrowse(client, key, row);
            }
        } catch (Exception e) {
            row.put("error", e.toString());
        }
        return row;
    }

    /** 把 decision-meta sidecar 里的 query 等字段抬到列表顶层，方便按问法浏览。 */
    private void attachDecisionBrowse(RedissonClient client, String decisionKey, Map<String, Object> row) {
        String metaKey = DECISION_META_PREFIX + decisionKey.substring(DECISION_PREFIX.length());
        try {
            Object metaRaw = client.getBucket(metaKey).get();
            if (metaRaw == null) {
                row.put("query", null);
                row.put("queryMissing", true);
                return;
            }
            String metaText = metaRaw instanceof String s ? s : String.valueOf(metaRaw);
            Object parsed = tryParse(metaText);
            row.put("browseMeta", parsed);
            if (parsed instanceof Map<?, ?> map) {
                Object query = map.get("query");
                Object rawQuery = map.get("rawQuery");
                row.put("query", query != null ? String.valueOf(query) : null);
                row.put("rawQuery", rawQuery != null ? String.valueOf(rawQuery) : null);
                if (map.get("channel") != null) {
                    row.put("channel", String.valueOf(map.get("channel")));
                }
                if (map.get("page") != null) {
                    row.put("page", String.valueOf(map.get("page")));
                }
                if (map.get("spaceId") != null) {
                    row.put("spaceId", String.valueOf(map.get("spaceId")));
                }
            }
            row.put("queryMissing", row.get("query") == null || String.valueOf(row.get("query")).isBlank());
        } catch (Exception e) {
            row.put("queryMissing", true);
            log.debug("读取 decision-meta 失败 key={} cause={}", decisionKey, e.toString());
        }
    }

    private Object tryParse(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            return mapper.convertValue(node, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    private String pretty(String text) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(text));
        } catch (Exception e) {
            return text;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= VALUE_PREVIEW_CHARS ? text : text.substring(0, VALUE_PREVIEW_CHARS) + "…";
    }

    private static String formatTtl(long ttlMs) {
        if (ttlMs < 0) {
            return "无过期";
        }
        if (ttlMs == 0) {
            return "即将过期";
        }
        long sec = TimeUnit.MILLISECONDS.toSeconds(ttlMs);
        if (sec < 60) {
            return sec + "s";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + "m";
        }
        return (min / 60) + "h" + (min % 60) + "m";
    }

    private record BucketKind(String id, String label, String pattern) {
    }
}
