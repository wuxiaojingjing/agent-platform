package com.huawei.finance.registry.index;

/**
 * 单条检索命中。
 *
 * @param capabilityId 能力标识
 * @param rawScore     检索引擎原始分数，**未归一化**
 * @param document     命中的检索文档
 */
public record CapabilityHit(String capabilityId, double rawScore, CapabilityDocument document) {
}
