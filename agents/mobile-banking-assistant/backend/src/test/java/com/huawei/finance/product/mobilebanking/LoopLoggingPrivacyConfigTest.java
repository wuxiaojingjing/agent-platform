package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LoopLoggingPrivacyConfigTest {

    @Test
    void openJiuwenPromptAndToolLoggersRemainDisabled() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("agent: \"OFF\"");
            assertThat(yaml).contains("tool: \"OFF\"");
            assertThat(yaml).contains("controller: \"OFF\"");
        }
    }
}
