package com.huawei.finance.oj.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.common.SearchResult;
import com.openjiuwen.core.retrieval.embedding.Embedding;
import com.openjiuwen.core.retrieval.retriever.Retriever;
import com.openjiuwen.core.retrieval.vector_store.VectorStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OJ 侧召回吃的是与快路径同一份索引，且模式选择必须真的落到不同的通道上。 */
class CapabilityRetrieverTest {

    /** 记下被调到的是哪条通道：模式选错时结果照样有内容，只有这个能看出来。 */
    private static final class RecordingStore implements VectorStore {

        final List<String> calls = new ArrayList<>();

        private static SearchResult hit(String id, double score) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(OpenSearchVectorStore.META_CAPABILITY_ID, id);
            return new SearchResult(id, "检索正文", score, metadata);
        }

        @Override
        public List<SearchResult> search(List<Float> queryVector, int topK, Map<String, Object> filters,
                                         Map<String, Object> options) {
            calls.add("dense");
            return List.of(hit("cap.dense", 0.9));
        }

        @Override
        public List<SearchResult> sparseSearch(String queryText, int topK, Map<String, Object> filters,
                                               Map<String, Object> options) {
            calls.add("sparse");
            return List.of(hit("cap.sparse", 3.0));
        }

        @Override
        public List<SearchResult> hybridSearch(String queryText, List<Float> queryVector, int topK,
                                               double alpha, Map<String, Object> filters,
                                               Map<String, Object> options) {
            calls.add("hybrid");
            return List.of(hit("cap.high", 5.0), hit("cap.low", 0.2));
        }

        @Override
        public List<SearchResult> queryByFilters(Map<String, Object> filters, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(List<Map<String, Object>> data, Integer batchSize, Map<String, Object> options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(List<String> ids, Map<String, Object> filterExpr, Map<String, Object> options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteTable(String tableName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getCollectionName() {
            return "agent-platform-capabilities";
        }

        @Override
        public void setCollectionName(String collectionName) {
        }

        @Override
        public VectorStore withCollection(String collectionName) {
            return this;
        }

        @Override
        public boolean tableExists(String tableName) {
            return true;
        }

        @Override
        public long count(String tableName) {
            return 0;
        }

        @Override
        public String getDatabaseName() {
            return "opensearch";
        }

        @Override
        public String getDistanceMetric() {
            return "cosine";
        }

        @Override
        public String getIndexType() {
            return "hnsw";
        }

        @Override
        public String getTextField() {
            return "searchText";
        }

        @Override
        public String getVectorField() {
            return "vector";
        }

        @Override
        public String getSparseVectorField() {
            return null;
        }

        @Override
        public String getMetadataField() {
            return null;
        }

        @Override
        public String getDocIdField() {
            return "capabilityId";
        }
    }

    private static final Embedding EMBEDDING = new Embedding() {
        @Override
        public List<Float> embedQuery(String text) {
            return List.of(0.1f, 0.2f);
        }

        @Override
        public List<List<Float>> embedDocuments(List<?> texts, Integer batchSize) {
            return List.of();
        }

        @Override
        public int getDimension() {
            return 2;
        }
    };

    @Test
    @DisplayName("默认走混合：单通道在中文短问句上召回率不够，这是本工程一开始就做混合的原因")
    void defaultsToHybrid() {
        RecordingStore store = new RecordingStore();
        Retriever retriever = new CapabilityRetriever(store, EMBEDDING);

        retriever.retrieve("查余额");

        assertThat(store.calls).containsExactly("hybrid");
    }

    @Test
    @DisplayName("模式真的分流到不同通道")
    void modesHitDifferentChannels() {
        RecordingStore store = new RecordingStore();
        Retriever retriever = new CapabilityRetriever(store, EMBEDDING);

        retriever.retrieve("查余额", 5, null, "sparse", Map.of());
        retriever.retrieve("查余额", 5, null, "dense", Map.of());

        assertThat(store.calls).containsExactly("sparse", "dense");
    }

    @Test
    @DisplayName("分值阈值按检索原始分过滤")
    void scoreThresholdFilters() {
        Retriever retriever = new CapabilityRetriever(new RecordingStore(), EMBEDDING);

        List<RetrievalResult> results = retriever.retrieve("查余额", 5, 1.0, "hybrid", Map.of());

        assertThat(results).extracting(RetrievalResult::getDocId).containsExactly("cap.high");
    }

    @Test
    @DisplayName("能力标识随结果带出，OJ 侧才拿得到与快路径同一个 id")
    void capabilityIdIsCarried() {
        Retriever retriever = new CapabilityRetriever(new RecordingStore(), EMBEDDING);

        List<RetrievalResult> results = retriever.retrieve("查余额");

        assertThat(results.get(0).getMetadata())
                .containsEntry(OpenSearchVectorStore.META_CAPABILITY_ID, "cap.high");
    }
}
