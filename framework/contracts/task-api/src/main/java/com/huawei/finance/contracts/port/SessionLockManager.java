package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Spi;
import java.time.Duration;
import java.util.Optional;

/** Infrastructure-neutral distributed lock used by the task orchestrator. */
@Spi
public interface SessionLockManager {

    Optional<SessionLock> tryLock(String key, Duration waitTime, Duration leaseTime)
            throws InterruptedException;
}
