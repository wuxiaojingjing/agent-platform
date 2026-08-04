package com.huawei.finance.slowpath;

import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.ConditionResolver;
import com.huawei.finance.intent.IntentPlanner;
import com.huawei.finance.intent.SlowPathProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 慢路径装配。
 *
 * <p>规划器**无条件**建 Bean，开关只决定调用方要不要用它。这样做是为了让开关的语义
 * 停留在一处：调用点看得见 {@code enabled} 判断，而不是靠 Bean 在不在来隐式决定行为——
 * 后者的失败方式是启动时少一个 Bean、运行时报 NoSuchBean，而不是老老实实走降级。
 */
@AutoConfiguration
@EnableConfigurationProperties(SlowPathProperties.class)
public class SlowPathConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IntentPlanner intentPlanner(SlowPathProperties props) {
        return new ReActIntentPlanner(props);
    }

    /**
     * 条件依赖求值。
     *
     * <p>与规划器一样无条件建 Bean：条件是规则拆解就能认出来的东西，慢路径开关关着时
     * 一样存在。开关管的是「要不要请模型重排计划」，不是「要不要看条件」。
     */
    @Bean
    @ConditionalOnMissingBean
    public ConditionEvaluator conditionEvaluator() {
        return new ConditionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConditionResolver conditionResolver(SlowPathProperties props) {
        return new DeepAgentConditionResolver(props);
    }
}
