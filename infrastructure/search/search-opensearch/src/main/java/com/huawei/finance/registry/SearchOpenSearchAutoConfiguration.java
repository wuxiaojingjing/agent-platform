package com.huawei.finance.registry;

import com.huawei.finance.contracts.agent.AgentIdentity;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.ScopeKeys;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.contracts.port.AssetCatalog;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetStore;
import com.huawei.finance.registry.index.CapabilityIndexer;
import com.huawei.finance.registry.index.CapabilitySearchService;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.IndexRebuildPipeline;
import com.huawei.finance.registry.index.OpenSearchCandidateSearch;
import com.huawei.finance.registry.index.RegistryProperties;
import java.util.Locale;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 能力注册中心装配。 */
@AutoConfiguration
@EnableConfigurationProperties(RegistryProperties.class)
public class SearchOpenSearchAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SearchOpenSearchAutoConfiguration.class);

    /**
     * 索引前缀 / 别名带上 agentId（架构草案阶段 1）。
     *
     * <p>挂在最早被创建、且依赖 {@link RegistryProperties} 的 Bean 上：仅在仍是历史默认值
     * {@code agent-platform-capability} 时改写，显式配置优先。建索引的 ApplicationRunner 之前必须已生效。
     */
    @Bean
    public IndexReadiness indexReadiness(RegistryProperties props, AgentIdentity agent) {
        applyAgentIndexScope(props, agent);
        return new IndexReadiness();
    }

    static void applyAgentIndexScope(RegistryProperties props, AgentIdentity agent) {
        String seg = ScopeKeys.segment(agent.id(), "agent")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String scoped = "agent-platform-" + seg + "-capability";
        if ("agent-platform-capability".equals(props.getIndexPrefix())) {
            props.setIndexPrefix(scoped);
        }
        if ("agent-platform-capability".equals(props.getAlias())) {
            props.setAlias(scoped);
        }
        log.info("检索索引作用域 agent={} prefix={} alias={}",
                agent.id(), props.getIndexPrefix(), props.getAlias());
    }

    @Bean(destroyMethod = "close")
    public OpenSearchTransport openSearchTransport(RegistryProperties props) {
        return ApacheHttpClient5TransportBuilder
                .builder(HttpHost.create(java.net.URI.create(props.getOpensearchUrl())))
                .setMapper(new JacksonJsonpMapper(ContractJson.mapper()))
                .build();
    }

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }

    @Bean
    public CapabilityIndexer capabilityIndexer(OpenSearchClient client, ModelGatewayClient gateway,
                                               ModelGatewayProperties modelProps,
                                               RegistryProperties props, IndexReadiness readiness) {
        return new CapabilityIndexer(client, gateway, modelProps, props, readiness);
    }

    /**
     * 资产变更 → 索引重建。注册在 {@link AssetStore#onReload}，保存资产后无需再点按钮。
     */
    @Bean
    public IndexRebuildPipeline indexRebuildPipeline(CapabilityIndexer indexer,
                                                     IndexReadiness readiness,
                                                     RegistryProperties props,
                                                     AssetStore store) {
        IndexRebuildPipeline pipeline = new IndexRebuildPipeline(indexer, readiness, props);
        store.onReload(pipeline::onAssetReload);
        return pipeline;
    }

    @Bean
    public CapabilitySearchService capabilitySearchService(OpenSearchClient client,
                                                           RegistryProperties props,
                                                           IndexReadiness readiness) {
        return new CapabilitySearchService(client, props, readiness);
    }

    /**
     * 意图引擎检索 SPI：对外不暴露 OpenSearch 类型。
     *
     * <p>{@code @ConditionalOnMissingBean} 是这个 SPI 能被接管的前提。缺了它，行内注册自己的
     * {@code CandidateSearch} 得到的是一个重复 Bean 冲突，而不是接管——「标了 @Spi 却装不进去」
     * 正是 {@code StabilityBoundaryTest} 守的那条线。行内若要换成自建检索（ES、向量库、
     * 或行内已有的搜索中台），注册一个同类型 Bean 即可，OpenSearch 这条基线实现自动让位。
     */
    @Bean
    @ConditionalOnMissingBean
    public CandidateSearch candidateSearch(CapabilitySearchService search, IndexReadiness readiness) {
        return new OpenSearchCandidateSearch(search, readiness);
    }

    /**
     * 启动时构建索引。
     *
     * <p>失败不阻止启动：OpenSearch 不可用时索引停留在 NOT_READY，检索通道整体摘除，
     * 快路径退化为纯规则召回。让应用起不来反而更糟——规则能覆盖的高危拦截场景
     * （如额度调整拒绝）会一起消失。
     */
    @Bean
    public ApplicationRunner capabilityIndexRunner(IndexRebuildPipeline pipeline, AssetStore store,
                                                   RegistryProperties props) {
        return args -> {
            if (!props.isBuildIndexOnStartup()) {
                log.info("已关闭启动建索引，等待外部流水线构建");
                return;
            }
            IndexRebuildPipeline.Result result = pipeline.forceRebuildWithRetry(
                    store.current(),
                    props.getStartupRebuildMaxAttempts(),
                    props.getStartupRebuildRetryDelayMs());
            if (result.outcome() == IndexRebuildPipeline.Outcome.FAILED) {
                log.error("索引构建重试耗尽，检索通道停用，快路径退化为纯规则召回 attempts={} detail={}",
                        Math.max(1, props.getStartupRebuildMaxAttempts()), result.detail());
            }
        };
    }
}
