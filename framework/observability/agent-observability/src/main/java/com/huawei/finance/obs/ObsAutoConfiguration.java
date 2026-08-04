package com.huawei.finance.obs;

import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.obs.trace.DecisionTracePolicy;
import com.huawei.finance.obs.trace.MicrometerDecisionTrace;
import com.huawei.finance.obs.trace.PropertyBackedTracePolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 观测装配。
 *
 * <p>用 {@code @AutoConfiguration} 而不是 {@code @Configuration}：自动配置是在使用方的
 * 配置**之后**才评估的，{@link ConditionalOnMissingBean} 因此能真的让位给使用方的 Bean。
 * 写成普通 {@code @Configuration} 并被 {@code @ComponentScan} 扫到时，两者的加载先后
 * 取决于扫描顺序，「使用方覆盖基线」这件事就成了碰运气——而它恰恰是基线产品的卖点。
 */
@AutoConfiguration
@EnableConfigurationProperties(ObsProperties.class)
public class ObsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public DecisionTracePolicy decisionTracePolicy(ObsProperties properties) {
        return new PropertyBackedTracePolicy(properties);
    }

    /**
     * @param tracer        用 {@code ObjectProvider} 取。未接 APM 的部署与库模块测试里没有 Tracer Bean，
     *                      直接构造注入会让整个上下文起不来——观测缺失不该导致服务起不来，
     *                      这个方向搞反了代价很大。
     * @param meterRegistry 同理，也必须是 {@code ObjectProvider}。Boot 4 把指标自动配置拆成了
     *                      单独的 starter，没引它的部署（比如行内那个只装 Agent Server 的进程）
     *                      就没有 {@link MeterRegistry} Bean，而按类型直接注入会让进程起不来。
     *                      缺它时退到一个不带导出器的 {@link SimpleMeterRegistry}：
     *                      指标存在但没人收，比「服务起不来」轻得多。
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionTrace decisionTrace(ObjectProvider<Tracer> tracer,
                                       ObjectProvider<MeterRegistry> meterRegistry,
                                       DecisionTracePolicy policy) {
        MeterRegistry registry = meterRegistry.getIfAvailable(() -> {
            log.warn("没有 MeterRegistry Bean，分段耗时指标不会被导出。"
                    + "要看板与告警的话，请引入 spring-boot-starter-micrometer-metrics");
            return new SimpleMeterRegistry();
        });
        return new MicrometerDecisionTrace(tracer.getIfAvailable(), registry, policy);
    }
}
