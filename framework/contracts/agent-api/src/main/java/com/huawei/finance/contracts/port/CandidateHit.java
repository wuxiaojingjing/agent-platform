package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Api;

/**
 * 单条候选检索命中（能力 id + 原始分）。
 *
 * <p>不含 OpenSearch 文档体：意图引擎只需要 id 与分数去做融合，文档细节留在检索实现内部。
 *
 * <p>标 {@code @Api} 而不是 {@code @Spi}：它是 {@link CandidateSearch} 两个方法的返回类型，
 * 由行内的检索实现构造、由基线的融合读取。按 {@code @Api} 的口径，
 * 「行内构造后传给基线」的类型算 {@code @Api}，{@code @Spi} 只留给行内要实现的接口。
 *
 * <p><b>{@code rawScore} 是检索原始分，不是融合分。</b>BM25 的原始分没有上界且随语料漂移，
 * 实现方不要在这里做归一化——归一化口径属于融合配置，两处各归一次的后果是权重失去意义。
 */
@Api
public record CandidateHit(String capabilityId, double rawScore) {
}
