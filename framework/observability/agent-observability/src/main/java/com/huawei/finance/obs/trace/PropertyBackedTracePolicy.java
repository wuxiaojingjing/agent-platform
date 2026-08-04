package com.huawei.finance.obs.trace;

import com.huawei.finance.obs.ObsProperties;

/**
 * 基线自带的默认策略：照 {@code huawei.finance.agent.obs.*} 配置执行。
 *
 * <p>使用方要更细的规则（按渠道分级、按能力域白名单、证据串逐条脱敏）就自己声明一个
 * {@link DecisionTracePolicy} Bean，本实现会自动让位，见 {@code ObsAutoConfiguration}。
 */
public class PropertyBackedTracePolicy implements DecisionTracePolicy {

    private final ObsProperties properties;

    public PropertyBackedTracePolicy(ObsProperties properties) {
        this.properties = properties;
    }

    @Override
    public int maxCandidates() {
        // 负数配置按 0 处理而不是抛异常：观测配置写错不该让应用起不来
        return Math.max(0, properties.getMaxTracedCandidates());
    }

    @Override
    public boolean includeEvidence() {
        return properties.isIncludeEvidence();
    }

    @Override
    public boolean includeSuppressed() {
        return properties.isIncludeSuppressed();
    }
}
