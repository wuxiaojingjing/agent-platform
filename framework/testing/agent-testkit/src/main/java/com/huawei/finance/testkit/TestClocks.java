package com.huawei.finance.testkit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class TestClocks {

    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private TestClocks() {
    }

    public static Clock fixedUtc() {
        return Clock.fixed(DEFAULT_INSTANT, ZoneOffset.UTC);
    }
}
