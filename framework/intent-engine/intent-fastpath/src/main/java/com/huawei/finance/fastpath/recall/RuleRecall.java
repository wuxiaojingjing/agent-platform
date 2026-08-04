package com.huawei.finance.fastpath.recall;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则召回（v0.7 §3.2 规则通道：承接关键词、渠道、页面和强业务规则）。
 *
 * <p>纯内存匹配，不依赖 OpenSearch 也不依赖模型。这一点是刻意的：检索与模型都可能不可用，
 * 而规则通道是最后的保底，它必须在别的通道全挂时仍能工作。
 */
public class RuleRecall {

    private final AssetBundle bundle;

    public RuleRecall(AssetBundle bundle) {
        this.bundle = bundle;
    }

    /**
     * @return 能力 ID → 得分（0-1），以及命中证据
     */
    public Result recall(String normalizedQuery) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, List<String>> evidence = new HashMap<>();

        for (CapabilityCard card : bundle.recallableCapabilities()) {
            List<String> hits = new ArrayList<>();
            double score = 0.0;

            // 与示例问法完全一致：这是最强证据，直接给满分
            for (String utterance : card.utterances()) {
                if (normalizedQuery.equals(utterance)) {
                    score = 1.0;
                    hits.add("utterance:" + utterance);
                    break;
                }
            }

            if (score < 1.0) {
                score = keywordCoverage(normalizedQuery, card.keywords(), hits);
            }

            if (score > 0) {
                scores.put(card.capabilityId(), score);
                evidence.put(card.capabilityId(), hits);
            }
        }
        return new Result(scores, evidence);
    }

    /**
     * 关键词对查询的覆盖度。
     *
     * <p>用「命中关键词的总字数 / 查询字数」而不是「命中个数 / 关键词总数」：
     * 后者会让关键词列得越少的卡分数越高，鼓励大家把词表写短，这是错误的激励。
     * 覆盖度衡量的是「这句话有多少内容被这张卡解释了」，与词表长度无关。
     */
    private static double keywordCoverage(String query, List<String> keywords, List<String> hits) {
        if (query.isEmpty()) {
            return 0.0;
        }
        int covered = 0;
        for (String keyword : keywords) {
            if (!keyword.isEmpty() && query.contains(keyword)) {
                covered += keyword.length();
                hits.add("keyword:" + keyword);
            }
        }
        return Math.min(1.0, (double) covered / query.length());
    }

    /**
     * @param scores   能力 ID → 规则得分
     * @param evidence 能力 ID → 命中证据，进 Candidate.matchedEvidence
     */
    public record Result(Map<String, Double> scores, Map<String, List<String>> evidence) {

        public double scoreOf(String capabilityId) {
            return scores.getOrDefault(capabilityId, 0.0);
        }

        public List<String> evidenceOf(String capabilityId) {
            return evidence.getOrDefault(capabilityId, List.of());
        }
    }
}
