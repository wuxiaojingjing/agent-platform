package com.huawei.finance.cache.redis;

import com.openjiuwen.extensions.store.kv.RedisStore;
import com.openjiuwen.spi.store.BaseKVStore;
import com.openjiuwen.spi.store.KVStoreProvider;
import java.util.Map;
import java.util.Objects;
import org.redisson.api.RedissonClient;

/**
 * 把本工程的 Redis 连接注册进 OpenJiuwen 的 KV 扩展点（ADR-009 的「反向复用」）。
 *
 * <p>方向要说清：**不是**把 {@code DecisionCache} 架在 OJ 的 {@code RedisStore} 上——
 * 那条路走不通，因为 {@code BaseKVStore.set} 不带 TTL（{@code RedisStore.set} 的方法体
 * 把 TTL 恒传 null），而缓存的核心语义就是过期。四条理由记在 ADR-009 里。
 *
 * <p>成立的是反过来：OJ 侧的 session / memory store 本就以永久写入为常态，正好是
 * {@code RedisStore.set} 的语义。于是我们把**自己这套已配好的连接**交给它，
 * 让 OJ 与意图引擎共用一套 Redis 配置，而不是各配一份、各建一组连接池。
 *
 * <p>{@code RedisStore} 的构造器收 {@code Object} 并靠反射按方法名派发，所以
 * {@link RedissonClient} 可以直接传进去，不需要任何适配代码。这也是它唯一
 * 让我们受益的地方——在缓存路径上那层反射是成本，在这里它是我们不用写适配器的原因。
 */
public final class RedisKVStoreProvider implements KVStoreProvider {

    /** OJ 侧按这个名字取实现：配置里写 {@code agent-platform} 即用本行这套连接。 */
    public static final String TYPE_NAME = "agent-platform";

    private final RedissonClient redisson;

    public RedisKVStoreProvider(RedissonClient redisson) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
    }

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    /**
     * {@code config} 一律忽略：连接参数以本工程的 {@code RedissonClient} 为准。
     *
     * <p>若顺着这个 Map 再建一条连接，就会出现两套配置各连一处的局面——而它的失败方式
     * 是静默的：功能全对，只是缓存与会话落在不同实例上，谁都不报错。共用连接正是本类的目的。
     */
    @Override
    public BaseKVStore create(Map<String, Object> config) {
        return new RedisStore(redisson);
    }
}
