package com.huawei.finance.cache.redis;

import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConstantDelay;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Shared Redisson client for platform Redis adapters. */
@AutoConfiguration
@ConditionalOnProperty(name = "huawei.finance.agent.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedissonClientAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.timeout:2000}") String timeout,
            @Value("${spring.data.redis.password:}") String password) {
        return Redisson.create(redissonConfig(host, port, timeout, password));
    }

    Config redissonConfig(String host, int port, String timeout, String password) {
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setTimeout(parseMillis(timeout))
                .setRetryDelay(new ConstantDelay(Duration.ofSeconds(1)))
                .setReconnectionDelay(new ConstantDelay(Duration.ofSeconds(1)));
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return config;
    }

    private static int parseMillis(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 2000 : Integer.parseInt(digits);
    }
}
