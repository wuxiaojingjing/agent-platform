package com.huawei.finance.oj.adapter;

import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.common.SearchResult;
import com.openjiuwen.core.retrieval.embedding.Embedding;
import com.openjiuwen.core.retrieval.retriever.Retriever;
import com.openjiuwen.core.retrieval.vector_store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OJ 侧的能力卡召回，吃的是快路径同一份索引。
 *
 * <p>这是实施架构 §5「禁止 Studio 与 Java Runtime 各维护一套工具清单」在检索侧的落地：
 * OJ 的 Agent 问「有哪些能力能干这件事」时，答案与快路径召回来自同一个别名、
 * 同一批向量、同一套就绪状态。各建一套的坏处不是重复，是**两边给的答案会不一样**，
 * 而不一样的时候没有任何迹象。
 *
 * <p><b>为什么只实现 {@link Retriever}，不实现 {@code KnowledgeBase}。</b>
 * 后者是一条文档入库流水线（解析 → 切块 → 抽取 → 建索引），我们这里一步都没有：
 * 能力卡是治理资产，由发布流程整批重建索引，不存在「上传一篇文档」这回事。
 * 把那些方法实现成空壳，就成了我们否决 OJ HTTP 组件时批评的同一件事——
 * 接口上承诺了，实现里没有。
 */
public class CapabilityRetriever implements Retriever {

    private final VectorStore store;
    private final Embedding embedding;

    public CapabilityRetriever(VectorStore store, Embedding embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    /**
     * @param mode {@code sparse} 只走词面、{@code dense} 只走向量，其余按混合。
     *             混合是默认：单通道在中文短问句上的召回率明显不够，这也是本工程一开始
     *             就做混合召回的原因
     * @param scoreThreshold 低于此分的丢弃；为 null 不过滤。注意这里的分是**检索原始分**，
     *                       不是融合分——BM25 的原始分没有上界，拿一个绝对阈值卡它
     *                       会随语料变化而漂移，调用方要清楚自己在卡什么
     */
    @Override
    public List<RetrievalResult> retrieve(String query, int topK, Double scoreThreshold, String mode,
                                          Map<String, Object> options) {
        List<SearchResult> hits = switch (mode == null ? "hybrid" : mode) {
            case "sparse" -> store.sparseSearch(query, topK, null, options);
            case "dense" -> store.search(embedding.embedQuery(query), topK, null, options);
            default -> store.hybridSearch(query, embedding.embedQuery(query), topK, 0.5, null, options);
        };

        List<RetrievalResult> results = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            if (scoreThreshold != null && hit.getScore() < scoreThreshold) {
                continue;
            }
            results.add(new RetrievalResult(hit.getText(), hit.getScore(), hit.getMetadata(),
                    hit.getId(), hit.getId()));
        }
        return results;
    }

    /**
     * 批量召回逐条执行。
     *
     * <p>没有做成一次 msearch：能力卡召回的批量场景只有离线评测，那里不在乎这点往返，
     * 而为它引入一条与在线不同的查询路径，等于让评测测的不是线上那条路。
     */
    @Override
    public List<List<RetrievalResult>> batchRetrieve(List<String> queries, int topK, String mode,
                                                     Map<String, Object> options) {
        List<List<RetrievalResult>> all = new ArrayList<>(queries.size());
        for (String query : queries) {
            all.add(retrieve(query, topK, null, mode, options));
        }
        return all;
    }

    @Override
    public boolean supportsMode(String mode) {
        return List.of("sparse", "dense", "hybrid").contains(mode);
    }
}
