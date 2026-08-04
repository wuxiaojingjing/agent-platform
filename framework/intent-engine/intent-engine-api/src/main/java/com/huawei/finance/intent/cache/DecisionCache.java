package com.huawei.finance.intent.cache;

import com.huawei.finance.stability.Spi;
import com.huawei.finance.contracts.model.RouteDecision;
import java.util.Map;
import java.util.Optional;

/**
 * 一级出口缓存。
 *
 * <p>抽成接口是为了让「缓存不可用」和「缓存被绕过」这两种行为可以被单独测到。
 * 直接依赖 Redis 实现的话，验证「澄清重试绕过缓存」就得先起一个 Redis。
 */
@Spi
public interface DecisionCache {

    Optional<RouteDecision> get(String key);

    void put(String key, RouteDecision decision);

    /**
     * 写入决策，并附带控制台浏览用元数据（归一化 query、渠道等）。
     *
     * <p>默认忽略元数据，兼容内存实现。Redis 实现会把 meta 存成 sidecar，
     * 键本身仍是 SHA-256，不把原话编进主键。
     */
    default void put(String key, RouteDecision decision, Map<String, Object> browseMeta) {
        put(key, decision);
    }

    /**
     * 不缓存。每次都算，读永远未命中。
     *
     * <p>这是引擎在没有任何缓存实现时的默认形态，让「装不装缓存」成为部署选择而不是
     * 编译期依赖——本模块因此不必依赖 Redisson，基线实现搬去了 {@code cache-redis}。
     * 监管上禁止把决策结论落到进程外的行，直接用这个默认值即可。
     *
     * <p>它同时是个安全的降级：缓存是加速手段而不是数据源，全程未命中只让快路径变慢，
     * 不改变任何一条出口的语义。反过来说，它在生产上被静默启用是要能看见的——
     * {@code FastPathConfiguration} 装配它时会打一条启动告警。
     */
    static DecisionCache disabled() {
        return new DecisionCache() {
            @Override
            public Optional<RouteDecision> get(String key) {
                return Optional.empty();
            }

            @Override
            public void put(String key, RouteDecision decision) {
                // 丢弃
            }
        };
    }
}
