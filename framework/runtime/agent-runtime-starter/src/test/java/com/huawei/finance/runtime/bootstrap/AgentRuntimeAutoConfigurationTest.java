package com.huawei.finance.runtime.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.runtime.spi.SessionAffinityPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

class AgentRuntimeAutoConfigurationTest {

    @Test
    void suppliesNoopSessionAffinityForSingleInstanceHosts() throws NoSuchMethodException {
        var configuration = new AgentRuntimeAutoConfiguration();

        assertThat(configuration.sessionAffinityPort()).isSameAs(SessionAffinityPort.NONE);
        assertThat(AgentRuntimeAutoConfiguration.class.getMethod("sessionAffinityPort")
                .getAnnotation(ConditionalOnMissingBean.class)
                .value()).containsExactly(SessionAffinityPort.class);
    }
}
