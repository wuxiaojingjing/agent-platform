package com.huawei.finance.registry.index;

import com.huawei.finance.contracts.port.CandidateHit;
import com.huawei.finance.contracts.port.CandidateSearch;
import java.util.List;

/**
 * 把 {@link CapabilitySearchService} + {@link IndexReadiness} 适配为意图引擎 SPI。
 */
public final class OpenSearchCandidateSearch implements CandidateSearch {

    private final CapabilitySearchService search;
    private final IndexReadiness readiness;

    public OpenSearchCandidateSearch(CapabilitySearchService search, IndexReadiness readiness) {
        this.search = search;
        this.readiness = readiness;
    }

    @Override
    public List<CandidateHit> bm25(String query, List<String> terms, int size) {
        return search.bm25(query, terms, size).stream()
                .map(h -> new CandidateHit(h.capabilityId(), h.rawScore()))
                .toList();
    }

    @Override
    public List<CandidateHit> knn(float[] vector, int k) {
        return search.knn(vector, k).stream()
                .map(h -> new CandidateHit(h.capabilityId(), h.rawScore()))
                .toList();
    }

    @Override
    public boolean searchable() {
        return readiness.get().searchable();
    }

    @Override
    public boolean semanticAvailable() {
        return readiness.get().semanticAvailable();
    }
}
