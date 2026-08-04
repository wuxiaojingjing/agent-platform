package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Resolution;
import com.huawei.finance.stability.Spi;
import java.util.Optional;

/** Cache for model understanding only. Policy acceptance is deliberately never cached. */
@Spi
public interface ContinuationModelCache {
    Optional<Resolution> get(String key);
    void put(String key, Resolution resolution);

    static ContinuationModelCache disabled() {
        return new ContinuationModelCache() {
            @Override public Optional<Resolution> get(String key) { return Optional.empty(); }
            @Override public void put(String key, Resolution resolution) { }
        };
    }
}
