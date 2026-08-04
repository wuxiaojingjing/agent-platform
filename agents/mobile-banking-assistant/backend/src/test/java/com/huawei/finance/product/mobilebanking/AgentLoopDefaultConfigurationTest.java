package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AgentLoopDefaultConfigurationTest {
    @Test
    void loopIsEnabledByDefaultForEveryDeploymentProfile() throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/application.yml")) {
            var root = new ObjectMapper(new YAMLFactory()).readTree(source);
            assertThat(root.at("/huawei/finance/agent/loop/enabled").asBoolean()).isTrue();
        }
    }
}
