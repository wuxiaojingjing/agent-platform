package com.huawei.finance.runtime.bootstrap;

import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Resolution;
import com.huawei.finance.orchestrator.continuation.ContinuationModelCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Small process-local cache; keys include tenant scope and Runtime state versions. */
public class InMemoryContinuationModelCache implements ContinuationModelCache {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    public InMemoryContinuationModelCache(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, Clock.systemUTC());
    }

    InMemoryContinuationModelCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl == null || ttl.isNegative() ? Duration.ZERO : ttl;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    @Override public Optional<Resolution> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.resolution());
    }

    @Override public void put(String key, Resolution resolution) {
        if (ttl.isZero() || key == null || resolution == null) return;
        if (entries.size() >= maxEntries && !entries.containsKey(key)) {
            entries.entrySet().stream().min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(oldest -> entries.remove(oldest.getKey(), oldest.getValue()));
        }
        entries.put(key, new Entry(resolution, clock.instant().plus(ttl)));
    }

    private record Entry(Resolution resolution, Instant expiresAt) { }
}
