package com.huawei.finance.response;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

/** 回复编排与渲染装配。 */
@AutoConfiguration
@EnableConfigurationProperties(ResponseProperties.class)
public class ResponseConfiguration {

    @Bean
    public TemplateVariableValidator templateVariableValidator() {
        return new TemplateVariableValidator();
    }

    /**
     * 答案侧审核器（FP-37）。
     *
     * <p>A 线直通。B 线润色上线时在这里换成 {@code AnswerAudit.of(List.of(...))}，
     * 渲染链路一行都不必改——这就是现在留这个位置的全部目的。
     */
    @Bean
    @ConditionalOnMissingBean
    public AnswerAudit answerAudit() {
        return AnswerAudit.passThrough();
    }

    @Bean
    public ResponseRealizer templateRenderer(AssetBundle bundle, TemplateVariableValidator validator,
                                             MeterRegistry meterRegistry, AnswerAudit answerAudit,
                                             ObjectProvider<ResponseTextModel> textModel) {
        return new ResponseRealizer(bundle, validator, meterRegistry, answerAudit,
                textModel.getIfAvailable(ResponseTextModel::unavailable));
    }

    @Bean
    public ResponsePlanner responsePlanner(AssetBundle bundle, ContractValidator validator,
                                           ResponseProperties props, MeterRegistry meterRegistry) {
        return new ResponsePlanner(bundle, validator, props, meterRegistry);
    }
}
