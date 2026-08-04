package com.huawei.finance.registry.index;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 能力注册中心配置。 */
@ConfigurationProperties(prefix = "huawei.finance.agent.registry")
public class RegistryProperties {

    /**
     * 资产目录。留空则从启动工作目录向上定位仓库 {@code assets/}。
     *
     * <p>此前默认值是字面量 {@code assets}，于是每个部署都得按自己的工作目录覆盖一次
     * ——{@code agents/mobile-banking-assistant} 里写的就是 {@code ../assets}，
     * 而那个 {@code ../} 编的是「应用模块在 reactor 的第几层」，模块一搬就断，
     * 且失败方式是启动期抛「资产目录不存在」。
     */
    private String opensearchUrl = "http://localhost:9200";

    /** 索引名前缀。真实索引名为 前缀-资产版本-向量模型版本，通过别名对外提供。 */
    private String indexPrefix = "agent-platform-capability";

    /** 检索使用的别名。切换别名是原子操作，保证不会出现「半新半旧」的索引状态。 */
    private String alias = "agent-platform-capability";

    /** BM25 侧字段权重。名称命中比正文命中更可信。 */
    private float nameBoost = 3.0f;
    private float searchTextBoost = 1.0f;
    private float keywordBoost = 2.0f;

    /** kNN 检索的 k 值。 */
    private int knnK = 10;

    /** 启动时是否自动建索引。生产环境应由发布流水线离线执行，不在应用启动路径上做重活。 */
    private boolean buildIndexOnStartup = true;

    /** 启动建索引遇到依赖尚未就绪时的最大尝试次数（包含第一次）。 */
    private int startupRebuildMaxAttempts = 3;

    /** 启动建索引重试间隔。 */
    private long startupRebuildRetryDelayMs = 2000;

    /**
     * 资产热更新后是否自动重建索引。
     *
     * <p>FP-46：控制台保存或外部触发 {@code AssetStore.reload} 后，管道会按新资产版本
     * 建索引并切别名。关掉它等于回到「改完还要人点重建」——只在索引由外部流水线
     * 全权接管时关。
     */
    private boolean rebuildOnAssetChange = true;

    public String getOpensearchUrl() {
        return opensearchUrl;
    }

    public void setOpensearchUrl(String opensearchUrl) {
        this.opensearchUrl = opensearchUrl;
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = indexPrefix;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public float getNameBoost() {
        return nameBoost;
    }

    public void setNameBoost(float nameBoost) {
        this.nameBoost = nameBoost;
    }

    public float getSearchTextBoost() {
        return searchTextBoost;
    }

    public void setSearchTextBoost(float searchTextBoost) {
        this.searchTextBoost = searchTextBoost;
    }

    public float getKeywordBoost() {
        return keywordBoost;
    }

    public void setKeywordBoost(float keywordBoost) {
        this.keywordBoost = keywordBoost;
    }

    public int getKnnK() {
        return knnK;
    }

    public void setKnnK(int knnK) {
        this.knnK = knnK;
    }

    public boolean isBuildIndexOnStartup() {
        return buildIndexOnStartup;
    }

    public void setBuildIndexOnStartup(boolean buildIndexOnStartup) {
        this.buildIndexOnStartup = buildIndexOnStartup;
    }

    public int getStartupRebuildMaxAttempts() {
        return startupRebuildMaxAttempts;
    }

    public void setStartupRebuildMaxAttempts(int startupRebuildMaxAttempts) {
        this.startupRebuildMaxAttempts = startupRebuildMaxAttempts;
    }

    public long getStartupRebuildRetryDelayMs() {
        return startupRebuildRetryDelayMs;
    }

    public void setStartupRebuildRetryDelayMs(long startupRebuildRetryDelayMs) {
        this.startupRebuildRetryDelayMs = startupRebuildRetryDelayMs;
    }

    public boolean isRebuildOnAssetChange() {
        return rebuildOnAssetChange;
    }

    public void setRebuildOnAssetChange(boolean rebuildOnAssetChange) {
        this.rebuildOnAssetChange = rebuildOnAssetChange;
    }
}
