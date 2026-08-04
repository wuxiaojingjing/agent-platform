package com.huawei.finance.obs.trace;

import com.huawei.finance.stability.Api;
import java.util.List;

/**
 * 仲裁前的一条候选，投影到「可以进 APM 的字段」这个子集上。
 *
 * <p>这个类型存在的唯一理由是**它装不下用户说的话**。
 *
 * <p>直接把领域侧的候选对象递给 trace 记录器会方便得多，但那类对象上挂着
 * 归一化后的用户输入、正则与 HanLP 抽出的槽位（收款人、金额、卡号）、以及像人名的实体候选。
 * 记录器一旦持有它，「今天没写进 span」就只是当下的实现细节：日后有人为了排查加一行
 * {@code span.tag("query", ...)}，评审时看不出这是把用户原话与转账金额送进了行内 APM
 * 与其下游的日志归集。APM 的数据保留期与访问控制通常弱于业务库，这条路不该存在。
 *
 * <p>所以边界画在类型上而不是规范上：能进来的只有能力标识、打分和证据串。
 * 要记用户文本，得先改这个 record 的字段——那是一次躲不过评审的改动。
 *
 * @param candidateId 能力标识，如 {@code cap.account.balance.query}。能力元数据，非用户数据
 * @param fusedScore  融合总分，即排序依据
 * @param semantic    语义通道分
 * @param rule        规则通道分
 * @param negative    负向打压分。非零说明这条被压过，排序异常时先看它
 * @param evidence    命中证据串，形如 {@code bm25:0.83}、{@code keyword:余额}。
 *                    可能含能力卡里的业务关键词，故默认不写入 span，由
 *                    {@link DecisionTracePolicy#includeEvidence()} 决定
 */
@Api
public record ScoredCandidate(
        String candidateId,
        double fusedScore,
        double semantic,
        double rule,
        double negative,
        List<String> evidence) {

    public ScoredCandidate {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
