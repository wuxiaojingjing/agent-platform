package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/**
 * 召回组 → 仲裁组（v0.7 附录 B {@code RecallResult}）。
 *
 * <p>{@code degradedChannels} 为本工程补充字段：v0.7 §3.2 要求通道可开关、降级要打点，
 * 而模型网关不可用时语义通道会被摘除。摘除若不写进契约，仲裁就无法区分
 * 「语义分数为 0 是因为不相似」还是「因为通道根本没跑」，这两者的处置完全不同。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record RecallResult(
        DomainRouting domainRouting,
        List<Candidate> candidates,
        List<String> degradedChannels) {

    public RecallResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        degradedChannels = degradedChannels == null ? List.of() : List.copyOf(degradedChannels);
    }

    public boolean semanticChannelDegraded() {
        return degradedChannels.contains("semantic");
    }

    /** 领域路由。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DomainRouting(
            Enums.RoutingMode routingMode,
            List<DomainCandidate> domainCandidates,
            boolean requiresFederatedRetrieval) {

        public DomainRouting {
            routingMode = routingMode == null ? Enums.RoutingMode.UNKNOWN : routingMode;
            domainCandidates = domainCandidates == null ? List.of() : List.copyOf(domainCandidates);
        }
    }

    /** 领域候选。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DomainCandidate(String domain, double score, List<String> evidence) {

        public DomainCandidate {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /** 意图/能力候选。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Candidate(
            String candidateId,
            Enums.CandidateType candidateType,
            List<String> domains,
            Scores scores,
            List<String> matchedEvidence,
            List<String> requiredSlots,
            RiskLevel riskLevel,
            Map<String, String> sourceVersions) {

        public Candidate {
            domains = domains == null ? List.of() : List.copyOf(domains);
            matchedEvidence = matchedEvidence == null ? List.of() : List.copyOf(matchedEvidence);
            requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
            sourceVersions = sourceVersions == null ? Map.of() : Map.copyOf(sourceVersions);
            scores = scores == null ? Scores.zero() : scores;
            riskLevel = riskLevel == null ? RiskLevel.R0 : riskLevel;
        }
    }

    /**
     * 各通道得分。
     *
     * <p>{@code negative} 是负向过滤的打压分，越大代表越应被排除，融合时做减法。
     */
    public record Scores(double semantic, double rule, double graph, double negative) {

        public static Scores zero() {
            return new Scores(0, 0, 0, 0);
        }
    }
}
