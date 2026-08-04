package com.huawei.finance.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.config.ConstantDelay;

class RedissonClientAutoConfigurationTest {

    @Test
    void reconnectDelayDoesNotOverflowAfterManyAttempts() {
        var config = new RedissonClientAutoConfiguration()
                .redissonConfig("127.0.0.1", 6379, "2000", "");
        var server = config.useSingleServer();

        assertThat(server.getRetryDelay()).isInstanceOf(ConstantDelay.class);
        assertThat(server.getReconnectionDelay()).isInstanceOf(ConstantDelay.class);
        assertThat(server.getReconnectionDelay().calcDelay(Integer.MAX_VALUE))
                .isEqualTo(Duration.ofSeconds(1));
    }
}
