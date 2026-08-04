package com.huawei.finance.intent.extension;

import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.stability.Api;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 召回结束、仲裁开始前的不可变候选快照。
 *
 * <p>{@code fusedScores} 的迭代顺序与 {@link RecallResult#candidates()} 一致。扩展可以通过
 * {@link #withCandidates(List, Map)} 过滤或重排候选，但平台仍会在执行后校验候选来源和安全字段。
 */
@Api
public record CandidateSet(RecallResult recall, Map<String, Double> fusedScores) {

    public CandidateSet {
        recall = Objects.requireNonNull(recall, "recall");
        List<RecallResult.Candidate> candidates = recall.candidates();
        Map<String, Double> supplied = fusedScores == null ? Map.of() : fusedScores;
        Set<String> ids = new LinkedHashSet<>();
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (RecallResult.Candidate candidate : candidates) {
            String id = Objects.requireNonNull(candidate.candidateId(), "candidateId");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("候选 ID 重复: " + id);
            }
            Double score = supplied.get(id);
            if (score == null || !Double.isFinite(score)) {
                throw new IllegalArgumentException("候选缺少有效融合分: " + id);
            }
            ordered.put(id, score);
        }
        if (!supplied.keySet().equals(ids)) {
            throw new IllegalArgumentException("融合分必须与候选 ID 一一对应");
        }
        fusedScores = Collections.unmodifiableMap(ordered);
    }

    /** 保留当前路由和降级事实，只替换候选顺序及其融合分。 */
    public CandidateSet withCandidates(List<RecallResult.Candidate> candidates,
                                       Map<String, Double> scores) {
        return new CandidateSet(new RecallResult(
                recall.domainRouting(), candidates, recall.degradedChannels()), scores);
    }
}
