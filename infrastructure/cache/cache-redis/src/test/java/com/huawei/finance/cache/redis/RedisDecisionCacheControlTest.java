package com.huawei.finance.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

class RedisDecisionCacheControlTest {

    @Test
    void disabledCacheBypassesRedisAndClearOnlyDeletesDecisionKeys() {
        AtomicInteger bucketCalls = new AtomicInteger();
        List<String> deletedPatterns = new ArrayList<>();
        RedissonClient redisson = redissonStub(bucketCalls, deletedPatterns);
        RedisDecisionCache cache = new RedisDecisionCache(redisson, Duration.ofMinutes(5), false);

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.get("decision-key")).isEmpty();
        cache.put("decision-key", null);
        assertThat(bucketCalls).hasValue(0);

        assertThat(cache.clear()).isEqualTo(4);
        assertThat(deletedPatterns).containsExactly(
                RedisDecisionCache.DECISION_KEY_PREFIX + "*",
                RedisDecisionCache.DECISION_META_PREFIX + "*");

        cache.setEnabled(true);
        assertThat(cache.enabled()).isTrue();
        assertThat(cache.get("decision-key")).isEmpty();
        assertThat(bucketCalls).hasValue(1);
    }

    private static RedissonClient redissonStub(
            AtomicInteger bucketCalls, List<String> deletedPatterns) {
        RBucket<?> bucket = (RBucket<?>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(), new Class<?>[]{RBucket.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        RKeys keys = (RKeys) Proxy.newProxyInstance(
                RKeys.class.getClassLoader(), new Class<?>[]{RKeys.class},
                (proxy, method, args) -> {
                    if ("deleteByPattern".equals(method.getName())) {
                        deletedPatterns.add(String.valueOf(args[0]));
                        return 2L;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(), new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKeys" -> keys;
                    case "getBucket" -> {
                        bucketCalls.incrementAndGet();
                        yield bucket;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
