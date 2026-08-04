package com.huawei.finance.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Decision;
import org.junit.jupiter.api.Test;

class RouteDispatcherTest {
    @Test void everyDecisionHasExactlyOneHandler() {
        RouteDispatcher dispatcher = new RouteDispatcher();
        for (Decision decision : Decision.values()) assertThat(dispatcher.handler(decision)).isNotNull();
    }

    @Test void platformRegistryIsExplicitlyOptional() {
        assertThat(new RouteDispatcher().hasPlatformRegistry()).isFalse();
    }
}
