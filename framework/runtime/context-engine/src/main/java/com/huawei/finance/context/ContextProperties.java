package com.huawei.finance.context;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 上下文最小工作集配置（FP-28）。 */
@ConfigurationProperties(prefix = "huawei.finance.agent.context")
public class ContextProperties {

    /**
     * 工作集 token 预算。
     *
     * <p>4096 来自 v0.7 §4.0 的面客约束，不是拍的。它只管本轮工作集：
     * 系统提示词、能力候选、工具 schema 都不在这里面——那几段各有自己的预算
     * （仲裁 prompt 的在 {@code huawei.finance.agent.model-gateway.arbitration}）。
     * 两笔账混在一起算，会得出「明明都在预算内却仍然超窗」的结论。
     */
    private int budgetTokens = 4096;

    /**
     * 最多回看多少轮。
     *
     * <p>预算之外再设一道轮数上限，是因为两者拦的不是同一件事：预算拦的是长，
     * 轮数拦的是久。二十轮之前的一次余额查询即使只有几十个 token，
     * 放进来也只会让模型把过期的数字当成现值。
     */
    private int maxTurns = 10;

    /**
     * 租约有效期。
     *
     * <p>短于一次请求的处理上限即可。租约的作用是防止「签发后任务态被另一路改了、
     * 这边还拿着旧的去动账」，它不需要活得比本轮请求更久。
     */
    private Duration leaseTtl = Duration.ofSeconds(30);

    /** 热读缓存里保留的轮数。小于 {@link #maxTurns} 时深回看会直接回源。 */
    private int cachedTurns = 10;

    /** 热读缓存存活时间。 */
    private Duration cacheTtl = Duration.ofMinutes(30);

    public int getBudgetTokens() {
        return budgetTokens;
    }

    public void setBudgetTokens(int budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    public void setLeaseTtl(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
    }

    public int getCachedTurns() {
        return cachedTurns;
    }

    public void setCachedTurns(int cachedTurns) {
        this.cachedTurns = cachedTurns;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
