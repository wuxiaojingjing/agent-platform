package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Api;

/** An acquired session-scoped lock. Closing the handle releases the lock. */
@FunctionalInterface
@Api
public interface SessionLock extends AutoCloseable {

    @Override
    void close();
}
