package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.index.CapabilityHit;
import com.huawei.finance.registry.index.CapabilityIndexer;
import com.huawei.finance.registry.index.CapabilitySearchService;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.RegistryProperties;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

/**
 * 能力卡索引与只读检索，跑在真实 OpenSearch 上。
 *
 * <p>这一层没法用替身验证：要验的是 cjk 分析器对中文的切分效果、别名切换的原子性、
 * knn_vector 映射能不能建起来——全都是 OpenSearch 自己的行为。
 *
 * <p>向量用确定性假向量而不是调模型网关：本测试关心的是索引与检索链路是否通，
 * 不是 embedding 的质量；接真模型会让这组用例既慢又受网络摆布。
 */
class CapabilityIndexTest {

    private static final String OPENSEARCH_URL = "http://localhost:9200";

    private static boolean opensearchUp;
    private static OpenSearchClient client;
    private static AssetBundle bundle;

    private RegistryProperties props;
    private IndexReadiness readiness;
    private CapabilitySearchService search;

    @BeforeAll
    static void connect() {
        try {
            client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                    .builder(HttpHost.create(URI.create(OPENSEARCH_URL)))
                    .setMapper(new JacksonJsonpMapper(ContractJson.mapper()))
                    .build());
            client.info();
            opensearchUp = true;
        } catch (Exception e) {
            opensearchUp = false;
        }
        bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    @AfterAll
    static void dropTestIndices() throws Exception {
        if (!opensearchUp) {
            return;
        }
        // 测试索引名含资产摘要，不清会随每次改资产越积越多
        client.indices().delete(d -> d.index("agent-platform-test-capability-*").ignoreUnavailable(true));
    }

    @BeforeEach
    void setUp() {
        assumeTrue(opensearchUp, "OpenSearch 未就绪，跳过索引集成测试");
        props = new RegistryProperties();
        props.setOpensearchUrl(OPENSEARCH_URL);
        props.setIndexPrefix("agent-platform-test-capability");
        props.setAlias("agent-platform-test-capability");
        readiness = new IndexReadiness();
        search = new CapabilitySearchService(client, props, readiness);
    }

    @Test
    @DisplayName("索引未就绪时检索一律返回空，而不是抛异常拖垮快路径")
    void notReadyIndexServesNothing() {
        assertThat(readiness.get().searchable()).isFalse();
        assertThat(search.bm25("查余额", List.of("余额"), 5)).isEmpty();
        assertThat(search.knn(new float[]{0.1f, 0.2f}, 5)).isEmpty();
    }

    @Test
    @DisplayName("建索引 → 切别名 → 就绪，BM25 能按中文词面召回到余额能力")
    void rebuildThenBm25Recalls() throws Exception {
        CapabilityIndexer indexer = indexer(new NoVectorGateway());
        boolean vectors = indexer.rebuild(bundle);

        assertThat(vectors).isFalse();
        IndexReadiness.Snapshot snapshot = readiness.get();
        assertThat(snapshot.state()).isEqualTo(IndexReadiness.State.READY);
        assertThat(snapshot.documentCount()).isEqualTo(bundle.recallableCapabilities().size());
        assertThat(snapshot.assetVersion()).isEqualTo(bundle.assetVersion());
        // 网关不可用时索引照建，只是没有向量：BM25 可用、语义通道停用，这是两种状态
        assertThat(snapshot.searchable()).isTrue();
        assertThat(snapshot.semanticAvailable()).isFalse();

        List<CapabilityHit> hits = search.bm25("查一下余额", List.of("余额"), 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).capabilityId()).isEqualTo("cap.account.balance.query");

        // keywords 是 keyword 字段，terms 查询要求整值相等。此前按空白切分中文等于没切，
        // 这条通道从来没命中过；传入分好的词才真正生效
        assertThat(search.bm25("", List.of("余额"), 5))
                .extracting(CapabilityHit::capabilityId)
                .contains("cap.account.balance.query");

        // 无向量时 kNN 通道自行摘除，不会拿一个空索引字段去查
        assertThat(search.knn(fakeVector(0), 5)).isEmpty();
    }

    @Test
    @DisplayName("AGENT 粒度的卡不进索引：它是路由单位，不是可执行的候选")
    void agentGranularityCardsAreNotIndexed() throws Exception {
        indexer(new NoVectorGateway()).rebuild(bundle);

        List<String> indexed = new ArrayList<>();
        List<String> terms = List.of("信用卡", "账单", "还款", "转账", "余额", "理财");
        for (CapabilityHit hit : search.bm25(String.join(" ", terms), terms, 50)) {
            indexed.add(hit.capabilityId());
        }
        assertThat(indexed).isNotEmpty();
        // 召回到 agent.creditcard 这类卡，中控会拿不到可执行的工具，用户看到的是一次空转
        assertThat(indexed).noneMatch(id -> id.startsWith("agent."));
    }

    @Test
    @DisplayName("重建切别名后旧索引不再被检索到，别名只指向一个索引")
    void aliasPointsToExactlyOneIndex() throws Exception {
        CapabilityIndexer indexer = indexer(new NoVectorGateway());
        indexer.rebuild(bundle);
        String firstIndex = readiness.get().indexName();

        // 换指令模板版本 → 索引名必须跟着换，否则新旧向量会混在同一个索引里
        ModelGatewayProperties changed = new ModelGatewayProperties();
        changed.getEmbedding().setInstructionVersion("instr-v2");
        CapabilityIndexer second = new CapabilityIndexer(client, new FakeVectorGateway(),
                changed, props, readiness);
        second.rebuild(bundle);
        String secondIndex = readiness.get().indexName();

        assertThat(secondIndex).isNotEqualTo(firstIndex);
        var aliasEntries = client.indices().getAlias(a -> a.name(props.getAlias())).result();
        assertThat(aliasEntries.keySet()).containsExactly(secondIndex);
    }

    @Test
    @DisplayName("有向量时语义通道打开，kNN 能召回对应能力")
    void knnRecallsWhenVectorsPresent() throws Exception {
        CapabilityIndexer indexer = indexer(new FakeVectorGateway());
        boolean vectors = indexer.rebuild(bundle);

        assertThat(vectors).isTrue();
        assertThat(readiness.get().semanticAvailable()).isTrue();

        // 用第 0 张卡的同款向量去查，它必须排第一
        List<CapabilityHit> hits = search.knn(fakeVector(0), 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).capabilityId())
                .isEqualTo(bundle.recallableCapabilities().get(0).capabilityId());
    }

    private CapabilityIndexer indexer(ModelGatewayClient gateway) {
        return new CapabilityIndexer(client, gateway, new ModelGatewayProperties(), props, readiness);
    }

    /** 第 i 维为 1、其余为 0 的单位向量：余弦相似度下彼此正交，命中关系一目了然。 */
    private static float[] fakeVector(int index) {
        float[] v = new float[new ModelGatewayProperties().getEmbedding().getDimensions()];
        v[index % v.length] = 1.0f;
        return v;
    }

    /** 网关不可用：索引应当照建，只是不含向量。 */
    private static final class NoVectorGateway implements ModelGatewayClient {

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    /** 确定性向量：第 n 篇文档拿第 n 维为 1 的单位向量。 */
    private static final class FakeVectorGateway implements ModelGatewayClient {

        private int cursor;

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            List<float[]> vectors = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                vectors.add(fakeVector(cursor++));
            }
            return GatewayResult.ok(vectors, 1);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("not-used", 0);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("not-used", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
