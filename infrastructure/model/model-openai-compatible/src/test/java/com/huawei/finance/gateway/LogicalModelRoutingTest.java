package com.huawei.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogicalModelRoutingTest {

    @Test
    void contextContinuationAndLoopReuseArbitrationModelByDefault() {
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.getArbitration().setModel("arbitration-model");

        assertThat(properties.resolveLogicalModel(properties.getContextRewrite()))
                .isEqualTo("arbitration-model");
        assertThat(properties.resolveLogicalModel(properties.getContinuation()))
                .isEqualTo("arbitration-model");
        assertThat(properties.resolveLogicalModel(properties.getLoop()))
                .isEqualTo("arbitration-model");
    }

    @Test
    void explicitLogicalModelOverrideRemainsIndependent() {
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.getContextRewrite().setModel("context-model");

        assertThat(properties.resolveLogicalModel(properties.getContextRewrite()))
                .isEqualTo("context-model");
    }

    @Test
    void everyNaturalLanguageLogicalPurposeUsesTheChatEndpointAndCredential() {
        assertThat(new String[]{"arbitration", "context-rewrite", "continuation",
                "loop-planner", "agent-tools", "prompt-optimization"})
                .allMatch(OpenAiCompatibleModelGateway::usesChatEndpoint);
        assertThat(OpenAiCompatibleModelGateway.usesChatEndpoint("embedding")).isFalse();
        assertThat(OpenAiCompatibleModelGateway.usesChatEndpoint("rerank")).isFalse();
    }
}
