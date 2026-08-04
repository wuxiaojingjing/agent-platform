package com.huawei.finance.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.common.context.ScopeKeys;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.context.ConversationTurn;
import com.huawei.finance.context.TurnStore;
import com.huawei.finance.contracts.validation.ContractJson;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** PostgreSQL source of truth with a best-effort Redis read projection. */
public final class HybridTurnStore implements TurnStore {

    private static final Logger log = LoggerFactory.getLogger(HybridTurnStore.class);

    private final TurnStore authoritative;
    private final RedissonClient redisson;
    private final Duration ttl;
    private final int cachedTurns;

    public HybridTurnStore(TurnStore authoritative, RedissonClient redisson,
                           Duration ttl, int cachedTurns) {
        this.authoritative = authoritative;
        this.redisson = redisson;
        this.ttl = ttl;
        this.cachedTurns = cachedTurns;
    }

    @Override
    public ConversationTurn append(ConversationTurn turn) {
        ConversationTurn persisted = authoritative.append(turn);
        invalidate(persisted.tenantId(), persisted.agentId(), persisted.sessionId());
        record("append", Map.of("ownerAgent", persisted.agentId(), "factKeys", persisted.facts().keySet()),
                Map.of("sourceOfTruth", "postgres", "cacheAction", "INVALIDATE",
                        "stateVersion", persisted.seq()), "OK");
        return persisted;
    }

    @Override
    public List<ConversationTurn> recent(String tenantId, String agentId, String sessionId, int limit) {
        if (limit <= cachedTurns) {
            List<ConversationTurn> cached = readCache(tenantId, agentId, sessionId);
            if (cached != null && cached.size() >= limit) {
                record("recent", Map.of("ownerAgent", agentId, "requestedTurns", limit),
                        Map.of("source", "redis", "cache", "HIT", "returnedTurns", limit), "OK");
                return cached.subList(cached.size() - limit, cached.size());
            }
        }
        List<ConversationTurn> fresh = authoritative.recent(tenantId, agentId, sessionId,
                Math.max(limit, cachedTurns));
        writeCache(tenantId, agentId, sessionId, fresh);
        List<ConversationTurn> result = fresh.size() <= limit
                ? fresh : fresh.subList(fresh.size() - limit, fresh.size());
        record("recent", Map.of("ownerAgent", agentId, "requestedTurns", limit),
                Map.of("source", "postgres", "cache", "MISS", "returnedTurns", result.size(),
                        "cacheAction", "REFRESH"), "OK");
        return result;
    }

    private String key(String tenantId, String agentId, String sessionId) {
        return ScopeKeys.turns(agentId, tenantId, sessionId);
    }

    private void invalidate(String tenantId, String agentId, String sessionId) {
        try {
            redisson.getBucket(key(tenantId, agentId, sessionId)).delete();
        } catch (Exception e) {
            log.warn("轮次缓存失效失败，下轮将回源 session={} cause={}", sessionId, e.toString());
        }
    }

    private List<ConversationTurn> readCache(String tenantId, String agentId, String sessionId) {
        try {
            RBucket<String> bucket = redisson.getBucket(key(tenantId, agentId, sessionId));
            String json = bucket.get();
            return json == null ? null : ContractJson.mapper().readValue(
                    json, new TypeReference<List<ConversationTurn>>() { });
        } catch (Exception e) {
            log.warn("轮次缓存读取失败，回源 session={} cause={}", sessionId, e.toString());
            return null;
        }
    }

    private void writeCache(String tenantId, String agentId, String sessionId,
                            List<ConversationTurn> turns) {
        try {
            String json = ContractJson.mapper().writeValueAsString(turns);
            redisson.<String>getBucket(key(tenantId, agentId, sessionId)).set(json, ttl);
        } catch (Exception e) {
            log.warn("轮次缓存写入失败，忽略 session={} cause={}", sessionId, e.toString());
        }
    }

    private static void record(String operation, Map<String, Object> input,
                               Map<String, Object> output, String outcome) {
        RequestContext context = RequestContextHolder.get();
        if (context != null) {
            context.recordModuleStep(new RuntimeModuleStep(
                    "conversation-memory", operation, "MEMORY", input, output, outcome, null));
        }
    }
}
