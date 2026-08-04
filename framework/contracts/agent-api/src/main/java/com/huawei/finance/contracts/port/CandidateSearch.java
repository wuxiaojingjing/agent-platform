package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Spi;
import java.util.List;

/**
 * 能力候选检索 SPI（架构草案阶段 0）。
 *
 * <p>意图引擎只依赖本接口，不依赖 OpenSearch 客户端或索引实现类。
 * 索引未就绪时实现应返回空列表，并由 {@link #searchable()} / {@link #semanticAvailable()}
 * 告知调用方把对应通道标为降级。
 */
@Spi
public interface CandidateSearch {

    /**
     * BM25 词面召回。
     *
     * @param query 检索文本
     * @param terms 分词后的词项；为空则该子句不参与
     * @param size  返回条数上限
     */
    List<CandidateHit> bm25(String query, List<String> terms, int size);

    /** 向量语义召回。 */
    List<CandidateHit> knn(float[] vector, int k);

    /** 词面检索是否可用（索引就绪）。 */
    boolean searchable();

    /** 语义检索是否可用（索引就绪且向量已写入）。 */
    boolean semanticAvailable();

    /** 检索通道整体不可用（单测默认装配 / 索引未就绪时的空实现）。 */
    static CandidateSearch unavailable() {
        return new CandidateSearch() {
            @Override
            public List<CandidateHit> bm25(String query, List<String> terms, int size) {
                return List.of();
            }

            @Override
            public List<CandidateHit> knn(float[] vector, int k) {
                return List.of();
            }

            @Override
            public boolean searchable() {
                return false;
            }

            @Override
            public boolean semanticAvailable() {
                return false;
            }
        };
    }
}
