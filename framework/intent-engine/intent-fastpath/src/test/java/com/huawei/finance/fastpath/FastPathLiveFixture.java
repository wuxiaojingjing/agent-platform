package com.huawei.finance.fastpath;

import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayConfiguration;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.OpenAiCompatibleModelGateway;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.index.CapabilityIndexer;
import com.huawei.finance.registry.index.CapabilitySearchService;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.OpenSearchCandidateSearch;
import com.huawei.finance.registry.index.RegistryProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

/**
 * 接真 OpenSearch（以及有密钥时接真模型网关）的快路径装配。
 *
 * <p>为什么要有这一份：{@link FastPathFixture} 把检索通道整体停用，跑的其实是**降级态**。
 * 降级态可无限重跑、完全确定，做回归锁很合适；但它测不到「检索到底救不救得回来」，
 * 而 FP-52 的种子集第一次跑就撞上了这个问题——两条明确的转账请求在降级态下被判无候选。
 * 那条结论只有拿真索引跑一遍才能确认它是**降级态专属**，而不是任何配置下都拒。
 *
 * <p>三档能力，按外部供给自动降级，每一档都明确知道自己少了什么：
 *
 * <ol>
 *   <li>OpenSearch 不在：整类跳过。</li>
 *   <li>OpenSearch 在、无 API key：BM25 字面通道可用，向量通道不可用
 *       （建索引时算不出向量，查询时也无法把问句转成向量）。</li>
 *   <li>两者都在：全通道，与线上装配一致。</li>
 * </ol>
 *
 * <p>索引前缀与别名都带 {@code eval} 字样并在跑完删掉，绝不碰应用自己那套
 * {@code agent-platform-capability-*}：评测把线上索引重建一遍，等于让一次测试改变了被测系统的状态。
 */
final class FastPathLiveFixture {

    static final String OPENSEARCH_URL = "http://localhost:9200";
    private static final String INDEX_PREFIX = "agent-platform-eval-capability";

    private FastPathLiveFixture() {
    }

    /** 探活。连不上不抛异常——上层要据此整类跳过，而不是红一片。 */
    static OpenSearchClient connect() {
        try {
            OpenSearchClient client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                    .builder(HttpHost.create(URI.create(OPENSEARCH_URL)))
                    .setMapper(new JacksonJsonpMapper(ContractJson.mapper()))
                    .build());
            client.info();
            return client;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 真网关，无密钥时返回 null。
     *
     * <p>不回落到「假向量」：假向量能让索引建起来、让 kNN 有东西返回，
     * 却让相似度彻底失去意义。那样跑出来的召回结论比没有更坏——它看起来是真的。
     */
    static ModelGatewayClient realGateway() {
        String apiKey = System.getenv("SILICONFLOW_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        ModelGatewayProperties props = new ModelGatewayProperties();
        ModelGatewayConfiguration config = new ModelGatewayConfiguration();
        var cm = config.modelGatewayConnectionManager(props);
        var httpClient = config.modelGatewayHttpClient(cm, props);
        var restClient = config.modelGatewayRestClient(httpClient, props);
        CircuitBreaker cb = config.modelGatewayCircuitBreaker(
                config.modelGatewayCircuitBreakerRegistry(props));
        var retry = config.modelGatewayRetry(config.modelGatewayRetryRegistry(props));
        return new OpenAiCompatibleModelGateway(restClient, props, cb, retry,
                new SimpleMeterRegistry(), apiKey);
    }

    static RegistryProperties properties() {
        RegistryProperties props = new RegistryProperties();
        props.setOpensearchUrl(OPENSEARCH_URL);
        props.setIndexPrefix(INDEX_PREFIX);
        props.setAlias(INDEX_PREFIX);
        return props;
    }

    static AssetBundle assets() {
        return new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    /**
     * 建一次索引，返回就绪状态。
     *
     * <p>建索引很贵（有密钥时要为每张卡算向量），因此调用方应当在 {@code @BeforeAll} 里建一次，
     * 之后所有用例共用同一个 {@link IndexReadiness} 与 {@link CandidateSearch}。
     *
     * @param indexGateway 建索引用的网关；为 null 则不算向量，只建字面索引
     */
    static IndexReadiness buildIndex(OpenSearchClient client, RegistryProperties props,
                                     AssetBundle bundle, ModelGatewayClient indexGateway)
            throws Exception {
        IndexReadiness readiness = new IndexReadiness();
        ModelGatewayClient gateway = indexGateway == null
                ? new FastPathFixture.UnavailableGateway() : indexGateway;
        new CapabilityIndexer(client, gateway, new ModelGatewayProperties(), props, readiness)
                .rebuild(bundle);
        return readiness;
    }

    static CandidateSearch candidateSearch(OpenSearchClient client, RegistryProperties props,
                                           IndexReadiness readiness) {
        CapabilitySearchService search = new CapabilitySearchService(client, props, readiness);
        return new OpenSearchCandidateSearch(search, readiness);
    }

    static void dropIndices(OpenSearchClient client) {
        try {
            client.indices().delete(d -> d.index(INDEX_PREFIX + "-*").ignoreUnavailable(true));
        } catch (Exception e) {
            // 清理失败不该让用例变红：留下的索引只占磁盘，而把它算成失败会掩盖真正的结论
            System.err.println("评测索引清理失败（可手工删 " + INDEX_PREFIX + "-*）：" + e);
        }
    }

    /** 把真检索接进标准装配。{@code queryGateway} 为 null 时查询侧走不可用替身。 */
    static FastPathFixture.Built build(CandidateSearch search, ModelGatewayClient queryGateway) {
        ModelGatewayClient gateway = queryGateway == null
                ? new FastPathFixture.UnavailableGateway() : queryGateway;
        return FastPathFixture.build(gateway, search);
    }
}
