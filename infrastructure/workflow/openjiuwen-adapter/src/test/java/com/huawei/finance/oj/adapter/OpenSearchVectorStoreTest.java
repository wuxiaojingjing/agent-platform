package com.huawei.finance.oj.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.registry.index.CapabilityDocument;
import com.huawei.finance.registry.index.CapabilityHit;
import com.huawei.finance.registry.index.CapabilitySearchService;
import com.openjiuwen.core.retrieval.common.SearchResult;
import com.openjiuwen.core.retrieval.vector_store.VectorStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 能力卡索引作为 OJ 向量库。
 *
 * <p>不起 OpenSearch：这里要证的是**适配语义**——分通道分值有没有带出去、alpha 有没有被
 * 误用成融合、写操作是不是真的被拒。真实检索行为由 {@code capability-registry}
 * 自己的集成用例覆盖，在这里重复起一遍中间件只会让本类在没 Docker 时整类跳过。
 */
class OpenSearchVectorStoreTest {

    /** 只覆盖两条召回方法，其余继承自真实类型，保证签名漂移时这里会编译失败。 */
    private static final class StubSearch extends CapabilitySearchService {

        private final List<CapabilityHit> bm25Hits;
        private final List<CapabilityHit> knnHits;

        StubSearch(List<CapabilityHit> bm25Hits, List<CapabilityHit> knnHits) {
            super(null, null, null);
            this.bm25Hits = bm25Hits;
            this.knnHits = knnHits;
        }

        @Override
        public List<CapabilityHit> bm25(String query, List<String> terms, int size) {
            return bm25Hits;
        }

        @Override
        public List<CapabilityHit> knn(float[] vector, int k) {
            return knnHits;
        }
    }

    private static CapabilityHit hit(String id, double score, String risk) {
        CapabilityDocument doc = new CapabilityDocument(id, id, "描述", "检索正文",
                List.of(), List.of("账户"), "QUERY", risk, List.of(), "1", null);
        return new CapabilityHit(id, score, doc);
    }

    private static VectorStore store(List<CapabilityHit> bm25, List<CapabilityHit> knn) {
        return new OpenSearchVectorStore(new StubSearch(bm25, knn), "agent-platform-capabilities");
    }

    @Test
    @DisplayName("两路分值分开挂在 metadata 上，不合成一个分数")
    void channelScoresStaySeparate() {
        VectorStore store = store(List.of(hit("cap.a", 3.2, "R0")), List.of(hit("cap.a", 0.81, "R0")));

        List<SearchResult> results = store.hybridSearch("查余额", List.of(0.1f, 0.2f), 5, 0.5, null, Map.of());

        assertThat(results).hasSize(1);
        Map<String, Object> metadata = results.get(0).getMetadata();
        assertThat(metadata.get(OpenSearchVectorStore.META_BM25_SCORE)).isEqualTo(3.2);
        assertThat(metadata.get(OpenSearchVectorStore.META_SEMANTIC_SCORE))
                .as("附录 B 的 RecallResult.Scores 要求分通道给分，合成一个分数就还原不回来了")
                .isEqualTo(0.81);
    }

    @Test
    @DisplayName("alpha 不参与计算——按它加权会抹掉三级短路赖以判定的绝对分含义")
    void alphaIsIgnoredOnPurpose() {
        VectorStore store = store(List.of(hit("cap.a", 3.2, "R0")), List.of(hit("cap.a", 0.81, "R0")));

        List<SearchResult> alphaZero = store.hybridSearch("q", List.of(0.1f), 5, 0.0, null, Map.of());
        List<SearchResult> alphaOne = store.hybridSearch("q", List.of(0.1f), 5, 1.0, null, Map.of());

        assertThat(alphaZero.get(0).getScore()).isEqualTo(alphaOne.get(0).getScore());
        assertThat(alphaZero.get(0).getMetadata()).isEqualTo(alphaOne.get(0).getMetadata());
    }

    @Test
    @DisplayName("两路各自命中的能力都要保留，不能只留交集")
    void resultsFromBothChannelsAreUnioned() {
        VectorStore store = store(List.of(hit("cap.a", 3.2, "R0")), List.of(hit("cap.b", 0.9, "R2")));

        List<SearchResult> results = store.hybridSearch("q", List.of(0.1f), 5, 0.5, null, Map.of());

        assertThat(results).extracting(SearchResult::getId).containsExactlyInAnyOrder("cap.a", "cap.b");
    }

    @Test
    @DisplayName("按 metadata 过滤，多值字段按包含判定")
    void filtersAreApplied() {
        VectorStore store = store(List.of(hit("cap.a", 3.2, "R0"), hit("cap.b", 2.0, "R2")), List.of());

        List<SearchResult> results = store.sparseSearch("q", 5,
                Map.of(OpenSearchVectorStore.META_RISK_LEVEL, "R2"), Map.of());

        assertThat(results).extracting(SearchResult::getId).containsExactly("cap.b");
    }

    @Test
    @DisplayName("写操作一律拒绝，不静默不做")
    void writesAreRejected() {
        VectorStore store = store(List.of(), List.of());

        assertThatThrownBy(() -> store.add(List.of(Map.of("id", "x")), 1, Map.of()))
                .as("静默不做会让调用方以为写进去了，下一次重建索引才会发现没有")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("只读");
        assertThatThrownBy(() -> store.delete(List.of("x"), Map.of(), Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.queryByFilters(Map.of(), 10))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("索引名不能在运行时改——会读到与当前向量不可比的旧索引")
    void collectionNameIsImmutableAtRuntime() {
        VectorStore store = store(List.of(), List.of());

        assertThatThrownBy(() -> store.setCollectionName("别的索引"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.withCollection("另一份").getCollectionName()).isEqualTo("另一份");
    }
}
