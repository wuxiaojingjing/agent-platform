package com.huawei.finance.registry.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 能力卡只读检索。
 *
 * <p>只读是有意的：写入只经 {@link CapabilityIndexer} 的「建新索引 + 切别名」路径。
 * 一旦允许在线单条写入，就会出现「某张卡改了但没重建索引」的漂移，
 * 而这种漂移在别名模型下是无法回滚的。
 *
 * <p>索引未就绪时所有检索返回空列表而不是报错：调用方据此把检索通道标为降级，
 * 规则召回仍可工作。抛异常会让整条快路径挂掉。
 */
public class CapabilitySearchService {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySearchService.class);

    private final OpenSearchClient client;
    private final RegistryProperties props;
    private final IndexReadiness readiness;

    public CapabilitySearchService(OpenSearchClient client, RegistryProperties props,
                                   IndexReadiness readiness) {
        this.client = client;
        this.props = props;
        this.readiness = readiness;
    }

    /**
     * BM25 词面召回。
     *
     * @param terms 分词后的词项，用于 keywords 字段的精确匹配；为空则该子句不参与
     */
    public List<CapabilityHit> bm25(String query, List<String> terms, int size) {
        if (!readiness.get().searchable()) {
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(props.getAlias())
                    .size(size)
                    .query(q -> q.bool(b -> {
                        b.should(matchQuery("name", query, props.getNameBoost()));
                        b.should(matchQuery("searchText", query, props.getSearchTextBoost()));
                        if (terms != null && !terms.isEmpty()) {
                            b.should(termsQuery("keywords", terms, props.getKeywordBoost()));
                        }
                        return b.minimumShouldMatch("1");
                    })));
            return toHits(client.search(request, CapabilityDocument.class));
        } catch (IOException | RuntimeException e) {
            log.warn("BM25 检索失败，该通道本次摘除 cause={}", e.toString());
            return List.of();
        }
    }

    /** 向量语义召回。 */
    public List<CapabilityHit> knn(float[] vector, int k) {
        if (!readiness.get().semanticAvailable() || vector == null) {
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(props.getAlias())
                    .size(k)
                    .query(q -> q.knn(knn -> knn
                            .field("vector")
                            .vector(vector)
                            .k(k))));
            return toHits(client.search(request, CapabilityDocument.class));
        } catch (IOException | RuntimeException e) {
            log.warn("kNN 检索失败，语义通道本次摘除 cause={}", e.toString());
            return List.of();
        }
    }

    private static Query matchQuery(String field, String text, float boost) {
        return Query.of(q -> q.match(m -> m.field(field).query(v -> v.stringValue(text)).boost(boost)));
    }

    /**
     * 关键词精确匹配。
     *
     * <p>{@code keywords} 是 keyword 类型字段，terms 查询要求整值相等。此前这里按空白切分
     * 输入句，而中文没有空白分隔，切出来的仍是整句，与任何一个关键词都不相等——
     * 这条通道实际上一直没有命中过。现在传入的是 HanLP 切好的词，才真正生效。
     */
    private static Query termsQuery(String field, List<String> terms, float boost) {
        return Query.of(q -> q.terms(t -> t
                .field(field)
                .terms(tv -> tv.value(terms.stream()
                        .map(term -> org.opensearch.client.opensearch._types.FieldValue.of(term))
                        .toList()))
                .boost(boost)));
    }

    private static List<CapabilityHit> toHits(SearchResponse<CapabilityDocument> response) {
        List<CapabilityHit> hits = new ArrayList<>();
        for (Hit<CapabilityDocument> hit : response.hits().hits()) {
            if (hit.source() == null) {
                continue;
            }
            hits.add(new CapabilityHit(hit.source().capabilityId(),
                    hit.score() == null ? 0.0 : hit.score(), hit.source()));
        }
        return hits;
    }
}
