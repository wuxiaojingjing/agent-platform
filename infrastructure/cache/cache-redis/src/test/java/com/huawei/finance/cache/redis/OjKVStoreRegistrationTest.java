package com.huawei.finance.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.spi.store.BaseKVStore;
import com.openjiuwen.spi.store.KVStoreFactory;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 反向复用那条线是否真的接上了（ADR-009）。
 *
 * <p>要守的失效方式是静默的：注册没跑，OJ 侧按名字找不到 {@code agent-platform} 就回落到它自带的
 * 实现，于是 OJ 与意图引擎各连一处 Redis。功能全对、日志无异常，只有缓存与会话
 * 落在不同实例上。所以必须由用例断言「注册确实发生了」，而不是只断言上下文起得来。
 */
class OjKVStoreRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OjKVStoreRegistration.class));

    @Test
    @DisplayName("有 RedissonClient 时，agent-platform 这个 KV provider 注册进 OJ 且造得出实例")
    void registersProviderIntoOpenJiuwen() {
        runner.withBean(RedissonClient.class, OjKVStoreRegistrationTest::redissonStub)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            OjKVStoreRegistration.OjKVStoreRegistrar.class);

                    assertThat(KVStoreFactory.hasProvider(RedisKVStoreProvider.TYPE_NAME))
                            .as("OJ 侧按 type name 取不到实现时会静默回落到自带客户端，"
                                    + "于是两边各连一处 Redis")
                            .isTrue();

                    BaseKVStore store = KVStoreFactory.create(
                            RedisKVStoreProvider.TYPE_NAME, Map.of());
                    assertThat(store)
                            .as("注册进去的必须真能造出实例——只登记名字不算接上")
                            .isNotNull();
                });
    }

    private static RedissonClient redissonStub() {
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(), new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) {
                        return null;
                    }
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == char.class) {
                        return '\0';
                    }
                    return 0;
                });
    }

    @Test
    @DisplayName("没有 RedissonClient 时不注册，也不炸")
    void skipsWithoutConnection() {
        runner.run(context -> assertThat(context)
                .as("没有连接却注册 provider，等于给 OJ 一个首次使用才炸的实现")
                .doesNotHaveBean(OjKVStoreRegistration.OjKVStoreRegistrar.class));
    }
}
