package com.huawei.finance.cache.redis;

import com.openjiuwen.spi.store.KVStoreFactory;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 把 {@link RedisKVStoreProvider} 注册进 OJ 的 {@link KVStoreFactory}（ADR-009）。
 *
 * <p>与 {@code OjAdapterConfiguration} 同构：注册本身不发起任何调用，所以不设开关。
 * 真正的开关是 OJ 侧配置里写不写 {@code agent-platform} 这个 type name。
 *
 * <p>{@code @ConditionalOnClass} 是必须的:agent-core-java 在本模块是 optional 依赖,
 * 使用方只要 Redis 缓存而不要 OJ 时它不在 classpath 上。没有这个条件,那种用法会在启动时
 * 抛 {@code NoClassDefFoundError};有了它,整段装配安静地不生效。
 */
@AutoConfiguration(after = RedissonClientAutoConfiguration.class)
@ConditionalOnClass(KVStoreFactory.class)
public class OjKVStoreRegistration {

    private static final Logger log = LoggerFactory.getLogger(OjKVStoreRegistration.class);

    /**
     * 只在已有 {@link RedissonClient} 时注册。
     *
     * <p>没有连接却注册一个 provider,等于给 OJ 一个会在首次使用时才炸的实现——
     * 而那时的报错离配置缺失已经很远了。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    public OjKVStoreRegistrar ojKvStoreRegistrar(RedissonClient redisson) {
        KVStoreFactory.register(RedisKVStoreProvider.TYPE_NAME,
                new RedisKVStoreProvider(redisson));
        log.info("已把本行 Redis 连接注册为 OpenJiuwen KV store type={}",
                RedisKVStoreProvider.TYPE_NAME);
        return new OjKVStoreRegistrar();
    }

    /** 注册动作的完成凭据。存在这个 Bean 表示注册跑过了,供测试断言。 */
    public static final class OjKVStoreRegistrar {
    }
}
