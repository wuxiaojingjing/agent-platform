package com.huawei.finance.context;

import com.huawei.finance.contracts.port.DomainReferenceResolver;
import com.huawei.finance.contracts.port.ContextStateVersionProvider;
import com.openjiuwen.core.context.token.SimpleTokenCounter;
import com.openjiuwen.core.context.token.TokenCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 上下文装配。{@code @AutoConfiguration} 的理由同 {@code OrchestratorConfiguration}。 */
@AutoConfiguration
@EnableConfigurationProperties(ContextProperties.class)
public class ContextConfiguration {

    /**
     * 计数器复用 OJ 的实现（ADR-002）。
     *
     * <p>它是给使用方覆盖的扩展点：行内若锁定了具体模型的分词器，声明自己的
     * {@code TokenCounter} Bean 即可。计数偏差的代价是单向的——估少了会超窗，
     * 而超窗表现为模型截断输入，不报错。
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenCounter contextTokenCounter() {
        return new SimpleTokenCounter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextLeaseCompiler contextLeaseCompiler(TurnStore turnStore, TokenCounter contextTokenCounter,
                                                     ContextProperties props, MeterRegistry meterRegistry) {
        return new ContextLeaseCompiler(turnStore, contextTokenCounter, props, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextualQueryRewriter contextualQueryRewriter(
            ObjectProvider<ContextualQueryModel> models) {
        return new ModelDrivenContextualQueryRewriter(models.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextStateVersionProvider contextStateVersionProvider(TurnStore turnStore) {
        return new TurnStoreContextStateVersionProvider(turnStore);
    }

    /**
     * 域内指代解析的组合（阶段 1.5）。
     *
     * <p>{@code ObjectProvider} 而不是 {@code List} 注入：域模块可以一个都不在
     * classpath 上（只跑引擎的测试就是这种），那时组合为空、槽位原样透传。
     * 用 {@code List} 注入在无候选时会启动失败，把「域没装」变成「服务起不来」。
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainReferenceResolution domainReferenceResolution(
            ObjectProvider<DomainReferenceResolver> resolvers) {
        return new DomainReferenceResolution(resolvers.orderedStream().toList());
    }
}
