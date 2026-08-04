package com.huawei.finance.slowpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.oj.adapter.ModelGatewayClientFactory;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-004：慢路径 DeepAgent 四开关打开，且不带 sysOperation。 */
class DeepAgentConfigTest {

    @Test
    @DisplayName("较完整 harness：taskLoop/planning/GPA/asyncSubagent 均为 true")
    void fullerHarnessFlagsAreOn() {
        DeepAgentConfig config = SlowPathPlanner.deepAgentConfig("m", 4);

        assertThat(config.isEnableTaskLoop()).isTrue();
        assertThat(config.isEnableTaskPlanning()).isTrue();
        assertThat(config.isAddGeneralPurposeAgent()).isTrue();
        assertThat(config.isEnableAsyncSubagent()).isTrue();
        assertThat(config.getSysOperation())
                .as("sysOperation 红线：模型不得执行系统命令")
                .isNull();
        assertThat(config.getMaxIterations()).isEqualTo(4);
        assertThat(config.getBackend()).isInstanceOf(ModelClientConfig.class);
        assertThat(((ModelClientConfig) config.getBackend()).getClientProvider())
                .isEqualTo(ModelGatewayClientFactory.PROVIDER);
        assertThat(config.getModel()).isInstanceOf(ModelRequestConfig.class);
        assertThat(((ModelRequestConfig) config.getModel()).getMaxTokens()).isEqualTo(1024);
    }
}
