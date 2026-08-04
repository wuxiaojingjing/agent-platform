package com.huawei.finance.fastpath;

import com.huawei.finance.common.event.EventClassifier;
import com.huawei.finance.common.event.EventClassifierProperties;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.fastpath.rewrite.ChineseAnalyzer;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.asset.AssetStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 意图引擎快路径装配。
 *
 * <p>{@code @AutoConfiguration} 的理由见 {@code OrchestratorConfiguration}：只有自动配置
 * 才在使用方配置之后评估，{@link ConditionalOnMissingBean} 的让位语义才成立。
 *
 * <p>只有真正对外承诺的扩展点带 {@link ConditionalOnMissingBean}，其余是内部接线。
 * 每个 Bean 都加上会把「哪些是承诺、哪些随时可改」这个信号冲淡，而使用方最需要知道的
 * 恰恰是这条线画在哪。
 *
 * <p>链上那十来个组件不再各自成 Bean，改由 {@link FastPathAssembly} 一次装出。理由是
 * 资产热重载要**整体重建**这条链：拆成十几个 Bean 就得回答「重载时哪几个要换、换的顺序
 * 是什么」，而任何一个漏换都会留下一条半新半旧的链——它不报错，只是有的判定用新阈值、
 * 有的用旧词表。留在这里的都是跨资产版本共享的东西：进程级词典、Redis 连接、扩展点。
 */
@AutoConfiguration
// 用类名字符串而不是 RegistryConfiguration.class：那个类在 capability-registry，
// 而它带着 OpenSearch 客户端与向量化网关。本模块只需要资产（asset-registry），
// 为一个顺序声明去编译期依赖整个索引侧，就等于把每个复用引擎的 Agent 都拖进 OpenSearch
// （架构草案 §4.3 第 3 行）。字符串形式是 Boot 为「引用一个不编译依赖的自动配置」提供的写法，
// 类不在 classpath 上时该约束自动忽略——这正是资产装配由行内自己接管时想要的行为。
// 代价是改名不再有编译期保护，由 ModuleDependencyTest 里那条用例守住这个名字。
@AutoConfigureAfter(name = {
        "com.huawei.finance.obs.ObsAutoConfiguration",
        "com.huawei.finance.registry.SearchOpenSearchAutoConfiguration"
})
@EnableConfigurationProperties(EventClassifierProperties.class)
public class FastPathConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FastPathConfiguration.class);

    @Bean
    public EventClassifier eventClassifier(EventClassifierProperties props) {
        return new EventClassifier(props);
    }

    /** 单例：HanLP 的词典是进程级资源，每个实例都重新加载一遍既慢又白占内存。 */
    @Bean
    public ChineseAnalyzer chineseAnalyzer() {
        return new ChineseAnalyzer();
    }

    /**
     * 跨重载复用的那些依赖，打成一包传给工厂，省得重载点再抄一遍十个参数。
     *
     * <p>出口缓存经 {@link ObjectProvider}{@code <DecisionCache>} 注入，**本类不再注册
     * disabled Bean**：Starter / {@code @ImportAutoConfiguration} 会打乱
     * {@code RedisDecisionCacheConfiguration} 的 {@code @AutoConfigureBefore}，
     * 若本类抢先 {@code @Bean DecisionCache.disabled()}，Redis 实现永远进不来。
     * 无实现时在此回退 disabled 并打告警。
     */
    @Bean
    public FastPathAssembly.Infrastructure fastPathInfrastructure(
            ChineseAnalyzer analyzer, CandidateSearch search, ModelGatewayClient gateway,
            ModelGatewayProperties modelProps, MeterRegistry meterRegistry,
            ObjectProvider<DecisionCache> cache, ContractValidator validator,
            EventClassifier eventClassifier,
            ObjectProvider<DecisionTrace> decisionTrace,
            ObjectProvider<CandidatePostProcessor> candidatePostProcessors) {
        DecisionCache resolved = cache.getIfAvailable(() -> {
            log.warn("未装配出口缓存实现，一级缓存按不缓存运行（每次请求都重新判定）。"
                    + "需要 Redis 请把 cache-redis 放上 classpath 并提供 RedissonClient；"
                    + "这是设计内的分支，不是故障。");
            return DecisionCache.disabled();
        });
        // ObjectProvider：agent-obs 的自动配置在库模块的切片测试上下文里可能没被引入，
        // 那时退回 NOOP。观测缺失不该让快路径装不起来
        return new FastPathAssembly.Infrastructure(analyzer, search, gateway, modelProps,
                meterRegistry, resolved, validator, eventClassifier,
                decisionTrace.getIfAvailable(() -> DecisionTrace.NOOP),
                candidatePostProcessors.orderedStream().toList());
    }

    /**
     * 启动时那条链。
     *
     * <p>热重载的使用方不要直接注入它——注入到的是启动那一刻的链，重载后不会变。
     * 需要跟着资产走的走 {@code FastPathProvider}（在 {@code mobile-banking-assistant} 里按请求取快照）。
     */
    @Bean
    public FastPathEngine fastPathEngine(AssetStore store, FastPathAssembly.Infrastructure infra) {
        return FastPathAssembly.build(store.current(), infra);
    }
}
