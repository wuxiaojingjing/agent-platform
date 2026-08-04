package com.huawei.finance.obs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 观测配置。
 *
 * <p>这里只放「记多少」这类量的调节。「记什么可以出现在 APM 里」那类规则是
 * {@link com.huawei.finance.obs.trace.DecisionTracePolicy} 的事——那是判断，不是数值，
 * 塞进配置文件会变成一串谁也说不清语义的布尔开关。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.obs")
public class ObsProperties {

    /**
     * 最多把几条候选写进 span。默认 5。
     *
     * <p>取 5 不是随手定的：融合排序的事故几乎都发生在头部几名之间，而 span 属性数
     * 是有成本的。要看全量候选应当去查召回日志，不该让每一次正常请求都为此付费。
     */
    private int maxTracedCandidates = 5;

    /** 是否把命中证据串写进 span。默认关，理由见 {@code DecisionTracePolicy#includeEvidence()}。 */
    private boolean includeEvidence = false;

    /** 是否记录被负向规则压掉的候选。默认开，理由见 {@code DecisionTracePolicy#includeSuppressed()}。 */
    private boolean includeSuppressed = true;

    public int getMaxTracedCandidates() {
        return maxTracedCandidates;
    }

    public void setMaxTracedCandidates(int maxTracedCandidates) {
        this.maxTracedCandidates = maxTracedCandidates;
    }

    public boolean isIncludeEvidence() {
        return includeEvidence;
    }

    public void setIncludeEvidence(boolean includeEvidence) {
        this.includeEvidence = includeEvidence;
    }

    public boolean isIncludeSuppressed() {
        return includeSuppressed;
    }

    public void setIncludeSuppressed(boolean includeSuppressed) {
        this.includeSuppressed = includeSuppressed;
    }
}
