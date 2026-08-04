package com.huawei.finance.cache.redis;

import com.huawei.finance.contracts.port.SessionLock;
import com.huawei.finance.contracts.port.SessionLockManager;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** Redisson implementation of the task orchestrator lock SPI. */
public final class RedisSessionLockManager implements SessionLockManager {

    private final RedissonClient redisson;

    public RedisSessionLockManager(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Optional<SessionLock> tryLock(String key, Duration waitTime, Duration leaseTime)
            throws InterruptedException {
        RLock lock = redisson.getLock(key);
        if (!lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS)) {
            return Optional.empty();
        }
        return Optional.of(() -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        });
    }
}
