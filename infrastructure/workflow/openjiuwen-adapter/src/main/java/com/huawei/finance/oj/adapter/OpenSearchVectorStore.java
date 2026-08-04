package com.huawei.finance.oj.adapter;

import com.huawei.finance.registry.index.CapabilityDocument;
import com.huawei.finance.registry.index.CapabilityHit;
import com.huawei.finance.registry.index.CapabilitySearchService;
import com.openjiuwen.core.retrieval.common.SearchResult;
import com.openjiuwen.core.retrieval.vector_store.VectorStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把已经在跑的 OpenSearch 能力卡索引接成 OJ 的向量库。
 *
 * <p>上一轮判「OJ 不支持 OpenSearch」是只看了它自带的实现清单（Milvus / PG / Chroma），
 * 没看接口——{@link VectorStore} 本身是可实现的。接进来之后，OJ 的检索组件与我们的快路径
 * 吃的是**同一份索引、同一份别名、同一套就绪状态**，而不是各建一套。
 *
 * <p><b>只读。</b>写入只走 {@code CapabilityIndexer} 的「建新索引 + 切别名」路径，
 * 单条写入会造出「某张卡改了但没重建索引」的漂移，而别名模型下这种漂移回滚不了。
 * 因此 {@link #add}、{@link #delete}、{@link #deleteTable} 一律抛错而不是静默不做——
 * 静默不做会让调用方以为写进去了。
 */
public class OpenSearchVectorStore implements VectorStore {

    /**
     * 分通道分值在 {@link SearchResult#getMetadata()} 里的键。
     *
     * <p>OJ 的 {@code hybridSearch} 只给一个合并后的分数，而附录 B 冻结的
     * {@code RecallResult.Scores} 要求分通道给分（ADR-001 §2.2），下游的证据列表与仲裁
     * 提示词都依赖它。metadata 是这里唯一能带出分通道分值的通道。
     *
     * <p>键名定成常量而不是各处写字符串：这层是 {@code Map<String,Object>}，
     * 编译期一点保护都没有，拼错一个字母的表现是「那一路分数恒为 0」，不是报错。
     */
    public static final String META_BM25_SCORE = "huawei.finance.agent.score.bm25";
    public static final String META_SEMANTIC_SCORE = "huawei.finance.agent.score.semantic";
    /** 命中文档的能力标识，等于 {@link SearchResult#getId()}，冗余一份便于下游直接取。 */
    public static final String META_CAPABILITY_ID = "huawei.finance.agent.capabilityId";
    public static final String META_RISK_LEVEL = "huawei.finance.agent.riskLevel";
    public static final String META_DOMAINS = "huawei.finance.agent.domains";

    private final CapabilitySearchService search;
    private final String collectionName;

    public OpenSearchVectorStore(CapabilitySearchService search, String collectionName) {
        this.search = search;
        this.collectionName = collectionName;
    }

    // ==================== 检索 ====================

    /** 纯向量检索。过滤条件当前不下推，见 {@link #applyFilters}。 */
    @Override
    public List<SearchResult> search(List<Float> queryVector, int topK, Map<String, Object> filters,
                                     Map<String, Object> options) {
        List<CapabilityHit> hits = search.knn(toFloatArray(queryVector), topK);
        return applyFilters(toResults(hits, META_SEMANTIC_SCORE), filters, topK);
    }

    /** 词面检索。terms 通道要求 HanLP 切好的词，这里没有分词器，只走 match 子句。 */
    @Override
    public List<SearchResult> sparseSearch(String queryText, int topK, Map<String, Object> filters,
                                           Map<String, Object> options) {
        List<CapabilityHit> hits = search.bm25(queryText, List.of(), topK);
        return applyFilters(toResults(hits, META_BM25_SCORE), filters, topK);
    }

    /**
     * 混合检索。
     *
     * <p><b>{@code alpha} 被忽略</b>，这是刻意的。OJ 的这个签名表达的是「按 alpha 把两路分数
     * 加权成一个 {@code _score}」，而本工程的融合是带饱和归一、按域 Top-K 与负向打压的一整套，
     * 且必须保留分通道分值（ADR-001）。在这里按 alpha 合成一个分数，等于在检索层就把
     * 三级短路赖以判定的绝对分含义抹掉——那不是精度问题，是安全出口失效。
     *
     * <p>所以这里只做「两路都取回、分值分开挂在 metadata 上」，融合仍由
     * {@code HybridRecall} 负责。返回的 {@code score} 取两路里较大的那个，仅用于排序展示，
     * <b>不得</b>被当作融合分使用。
     */
    @Override
    public List<SearchResult> hybridSearch(String queryText, List<Float> queryVector, int topK,
                                           double alpha, Map<String, Object> filters,
                                           Map<String, Object> options) {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (SearchResult sparse : sparseSearch(queryText, topK, null, options)) {
            merged.put(sparse.getId(), sparse);
        }
        for (SearchResult dense : search(queryVector, topK, null, options)) {
            SearchResult existing = merged.get(dense.getId());
            if (existing == null) {
                merged.put(dense.getId(), dense);
                continue;
            }
            existing.getMetadata().put(META_SEMANTIC_SCORE, dense.getMetadata().get(META_SEMANTIC_SCORE));
            existing.setScore(Math.max(existing.getScore(), dense.getScore()));
        }
        return applyFilters(new ArrayList<>(merged.values()), filters, topK);
    }

    /**
     * 按属性取文档。
     *
     * <p>能力卡索引没有「按条件扫全量」的入口，只读服务只暴露两条召回通道。
     * 与其用一次 match_all 假装支持，不如抛错——前者会在数据量涨上来时变成一次全表扫描。
     */
    @Override
    public List<SearchResult> queryByFilters(Map<String, Object> filters, int limit) {
        throw new UnsupportedOperationException(
                "能力卡索引不提供按属性全量扫描。需要遍历能力卡请读资产目录，那才是治理口径的来源");
    }

    /**
     * 过滤在本地做。
     *
     * <p>能力状态、领域、风险等级本可以下推成 OpenSearch 的 filter 子句（ADR-001 §3 配套
     * 约束 3 记着这件应做未做的事），但下推要改 {@code CapabilitySearchService} 的查询构造，
     * 那是快路径正在用的代码。本适配层不碰它，先在这一侧过滤；候选集是能力卡级别的
     * 几十条量级，本地过滤不构成瓶颈。
     */
    private static List<SearchResult> applyFilters(List<SearchResult> results, Map<String, Object> filters,
                                                   int topK) {
        List<SearchResult> kept = new ArrayList<>();
        for (SearchResult result : results) {
            if (matches(result, filters)) {
                kept.add(result);
            }
            if (kept.size() >= topK) {
                break;
            }
        }
        return kept;
    }

    private static boolean matches(SearchResult result, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object actual = result.getMetadata().get(filter.getKey());
            boolean hit = actual instanceof List<?> list
                    ? list.contains(filter.getValue())
                    : String.valueOf(actual).equals(String.valueOf(filter.getValue()));
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private static List<SearchResult> toResults(List<CapabilityHit> hits, String scoreKey) {
        List<SearchResult> results = new ArrayList<>(hits.size());
        for (CapabilityHit hit : hits) {
            CapabilityDocument doc = hit.document();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(scoreKey, hit.rawScore());
            metadata.put(META_CAPABILITY_ID, hit.capabilityId());
            if (doc != null) {
                metadata.put(META_RISK_LEVEL, doc.riskLevel());
                metadata.put(META_DOMAINS, doc.domains());
            }
            results.add(new SearchResult(hit.capabilityId(),
                    doc == null ? "" : doc.searchText(), hit.rawScore(), metadata));
        }
        return results;
    }

    private static float[] toFloatArray(List<Float> vector) {
        if (vector == null) {
            return null;
        }
        float[] array = new float[vector.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = vector.get(i);
        }
        return array;
    }

    // ==================== 写入：一律拒绝 ====================

    @Override
    public void add(List<Map<String, Object>> data, Integer batchSize, Map<String, Object> options) {
        throw readOnly("写入");
    }

    @Override
    public boolean delete(List<String> ids, Map<String, Object> filterExpr, Map<String, Object> options) {
        throw readOnly("删除");
    }

    @Override
    public void deleteTable(String tableName) {
        throw readOnly("删表");
    }

    private static UnsupportedOperationException readOnly(String what) {
        return new UnsupportedOperationException(
                "能力卡索引只读，不接受在线" + what + "。变更须走 CapabilityIndexer 的"
                        + "「建新索引 + 切别名」，否则会出现改了卡但没重建索引的漂移，且回滚不了");
    }

    // ==================== 元信息 ====================

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public void setCollectionName(String collectionName) {
        throw new UnsupportedOperationException(
                "索引名由资产版本、embedding 模型与指令版本共同决定，不能在运行时改"
                        + "（改了会读到与当前向量不可比的旧索引）");
    }

    @Override
    public VectorStore withCollection(String collectionName) {
        return new OpenSearchVectorStore(search, collectionName);
    }

    @Override
    public boolean tableExists(String tableName) {
        // 只读服务判断不了任意索引是否存在；就绪状态是唯一有意义的答案
        return collectionName.equals(tableName);
    }

    @Override
    public long count(String tableName) {
        throw new UnsupportedOperationException(
                "文档数请读 IndexReadiness 的快照，那是发布流程维护的权威计数");
    }

    @Override
    public String getDatabaseName() {
        return "opensearch";
    }

    @Override
    public String getDistanceMetric() {
        // 索引映射里 knn 字段用的就是余弦；写死是因为它由索引 mapping 决定，不由本类决定
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
        // 没有稀疏向量字段：词面通道走 BM25，不是稀疏向量
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
