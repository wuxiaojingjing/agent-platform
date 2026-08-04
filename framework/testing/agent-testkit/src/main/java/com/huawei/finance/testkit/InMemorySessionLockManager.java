package com.huawei.finance.testkit;

import com.huawei.finance.contracts.port.SessionLock;
import com.huawei.finance.contracts.port.SessionLockManager;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Process-local session lock for deterministic component tests. */
public final class InMemorySessionLockManager implements SessionLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<SessionLock> tryLock(String key, Duration waitTime, Duration leaseTime)
            throws InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        if (!lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)) {
            return Optional.empty();
        }
        return Optional.of(() -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        });
    }
}
