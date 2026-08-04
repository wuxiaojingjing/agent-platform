package com.huawei.finance.cache.redis;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.intent.cache.DecisionCacheControl;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 出口缓存。
 *
 * <p>用 Redisson 而不是手写 SETNX/GET（实施架构 §2.7 复用清单）。
 *
 * <p>读写失败都当作未命中：缓存是加速手段，不是数据源。Redis 挂了应该表现为变慢，
 * 而不是快路径整体不可用。
 *
 * <p><b>住在独立模块而不是 {@code intent-fastpath} 里。</b>Redisson 一旦是引擎的编译期依赖，
 * 每个复用引擎的 Agent 都会被拖进 Redis，包括那些自带缓存中间件或按监管要求不得把
 * 决策结论落到进程外的（架构草案 §4.3 第 4 行）。引擎侧的默认值是
 * {@link DecisionCache#disabled()}，把这个模块放上 classpath 即换成 Redis。
 *
 * <p>替换实现要守住一条：缓存键由 {@code DecisionCacheKey} 生成，含渠道、页面、用户状态、
 * 资产版本、embedding 模型版本与指令模板版本。实现方不得自行简化键——少一个维度，
 * 就会把改版前的结论返给改版后的请求，而这种脏读极难在测试里复现。
 *
 * <p>控制台浏览：主键仍是哈希；{@link #put(String, RouteDecision, Map)} 会额外写
 * {@code decision-meta:} sidecar（同 TTL），里面带归一化 query 等可读字段。
 */
public class RedisDecisionCache implements DecisionCache, DecisionCacheControl {

    private static final Logger log = LoggerFactory.getLogger(RedisDecisionCache.class);

    /** 与 {@code DecisionCacheKey} 前缀对齐；meta 用独立前缀避免扫 decision:* 时混进 sidecar。 */
    public static final String DECISION_KEY_PREFIX = "huawei-finance-agent:route-decision:v3:";
    public static final String DECISION_META_PREFIX = "huawei-finance-agent:decision-meta:";

    private final RedissonClient redisson;
    private final Duration ttl;
    private final AtomicBoolean enabled;

    public RedisDecisionCache(RedissonClient redisson, Duration ttl) {
        this(redisson, ttl, true);
    }

    public RedisDecisionCache(RedissonClient redisson, Duration ttl, boolean enabled) {
        this.redisson = redisson;
        this.ttl = ttl;
        this.enabled = new AtomicBoolean(enabled);
    }

    @Override
    public Optional<RouteDecision> get(String key) {
        if (!enabled()) {
            record("read", Map.of("backend", "redis"), Map.of("result", "DISABLED"), "OK");
            return Optional.empty();
        }
        try {
            RBucket<String> bucket = redisson.getBucket(key);
            String json = bucket.get();
            if (json == null) {
                record("read", Map.of("backend", "redis"), Map.of("result", "MISS"), "OK");
                return Optional.empty();
            }
            record("read", Map.of("backend", "redis"), Map.of("result", "HIT"), "OK");
            return Optional.of(ContractJson.mapper().readValue(json, RouteDecision.class));
        } catch (Exception e) {
            log.warn("出口缓存读取失败，按未命中处理 cause={}", e.toString());
            record("read", Map.of("backend", "redis"), Map.of("result", "FALLBACK"), "ERROR");
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, RouteDecision decision) {
        put(key, decision, Map.of());
    }

    @Override
    public void put(String key, RouteDecision decision, Map<String, Object> browseMeta) {
        if (!enabled()) {
            record("write", Map.of("backend", "redis"), Map.of("result", "DISABLED"), "OK");
            return;
        }
        try {
            String json = ContractJson.mapper().writeValueAsString(decision);
            redisson.<String>getBucket(key).set(json, ttl);
            writeMeta(key, decision, browseMeta == null ? Map.of() : browseMeta);
            record("write", Map.of("backend", "redis"),
                    Map.of("storedDecision", decision.decision().name()), "OK");
        } catch (Exception e) {
            log.warn("出口缓存写入失败，忽略 cause={}", e.toString());
            record("write", Map.of("backend", "redis"), Map.of("result", "IGNORED"), "ERROR");
        }
    }

    public static String metaKeyFor(String decisionKey) {
        if (decisionKey != null && decisionKey.startsWith(DECISION_KEY_PREFIX)) {
            return DECISION_META_PREFIX + decisionKey.substring(DECISION_KEY_PREFIX.length());
        }
        return DECISION_META_PREFIX + (decisionKey == null ? "" : decisionKey);
    }

    @Override
    public boolean enabled() {
        return enabled.get();
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    @Override
    public long clear() {
        try {
            long decisions = redisson.getKeys().deleteByPattern(DECISION_KEY_PREFIX + "*");
            long metadata = redisson.getKeys().deleteByPattern(DECISION_META_PREFIX + "*");
            return decisions + metadata;
        } catch (Exception e) {
            log.warn("出口缓存清理失败 cause={}", e.toString());
            return 0;
        }
    }

    private void writeMeta(String decisionKey, RouteDecision decision,
                           Map<String, Object> browseMeta) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>(browseMeta);
            meta.putIfAbsent("decision", decision.decision().name());
            meta.putIfAbsent("confidence", decision.confidence());
            if (!decision.candidateIds().isEmpty()) {
                meta.putIfAbsent("candidateIds", decision.candidateIds());
            }
            if (decision.reasonCode() != null) {
                meta.putIfAbsent("reasonCode", decision.reasonCode().name());
            }
            String metaJson = ContractJson.mapper().writeValueAsString(meta);
            redisson.<String>getBucket(metaKeyFor(decisionKey)).set(metaJson, ttl);
        } catch (Exception e) {
            log.warn("出口缓存元数据写入失败，浏览页可能看不到原话 cause={}", e.toString());
        }
    }

    private static void record(String operation, Map<String, Object> input,
                               Map<String, Object> output, String outcome) {
        RequestContext context = RequestContextHolder.get();
        if (context != null) {
            context.recordModuleStep(new RuntimeModuleStep(
                    "decision-cache", operation, "CACHE", input, output, outcome, null));
        }
    }
}
