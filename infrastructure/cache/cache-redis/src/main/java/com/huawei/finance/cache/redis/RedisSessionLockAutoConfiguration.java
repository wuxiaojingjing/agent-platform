package com.huawei.finance.cache.redis;

import com.huawei.finance.contracts.port.SessionLockManager;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = RedissonClientAutoConfiguration.class)
public class RedisSessionLockAutoConfiguration {

    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public SessionLockManager sessionLockManager(RedissonClient redisson) {
        return new RedisSessionLockManager(redisson);
    }
}
