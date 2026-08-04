package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 融合权重、分流阈值与通道开关（v0.7 §3.2 要求全部配置化）。
 *
 * <p>用 class 而非 record：Jackson 反序列化嵌套结构时 record 对缺省字段的处理更僵硬，
 * 而这份配置需要允许运维只覆盖其中一两项。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FusionConfig {

    private Weights weights = new Weights();
    private Channels channels = new Channels();
    private Thresholds thresholds = new Thresholds();
    private TopN topN = new TopN();
    private Normalization normalization = new Normalization();
    private MultiTask multiTask = new MultiTask();
    private Planning planning = new Planning();
    private Clarify clarify = new Clarify();
    private Cache cache = new Cache();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Weights {
        private double semantic = 0.45;
        private double bm25 = 0.25;
        private double rule = 0.30;
        private double graph = 0.0;

        public double getSemantic() {
            return semantic;
        }

        public void setSemantic(double v) {
            this.semantic = v;
        }

        public double getBm25() {
            return bm25;
        }

        public void setBm25(double v) {
            this.bm25 = v;
        }

        public double getRule() {
            return rule;
        }

        public void setRule(double v) {
            this.rule = v;
        }

        public double getGraph() {
            return graph;
        }

        public void setGraph(double v) {
            this.graph = v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Channels {
        private boolean semanticEnabled = true;
        private boolean bm25Enabled = true;
        private boolean ruleEnabled = true;
        private boolean graphEnabled = false;
        private boolean rerankEnabled = false;

        public boolean isSemanticEnabled() {
            return semanticEnabled;
        }

        public void setSemanticEnabled(boolean v) {
            this.semanticEnabled = v;
        }

        public boolean isBm25Enabled() {
            return bm25Enabled;
        }

        public void setBm25Enabled(boolean v) {
            this.bm25Enabled = v;
        }

        public boolean isRuleEnabled() {
            return ruleEnabled;
        }

        public void setRuleEnabled(boolean v) {
            this.ruleEnabled = v;
        }

        public boolean isGraphEnabled() {
            return graphEnabled;
        }

        public void setGraphEnabled(boolean v) {
            this.graphEnabled = v;
        }

        /**
         * 重排开关（FP-1H）。
         *
         * <p>默认关。开启后融合 Top-N 再过一次重排模型，多一次网关往返。
         * 次数硬门已废（ADR-003）；网关侧 {@code huawei.finance.agent.model.rerank.enabled} 也须打开，
         * 否则调用不可用、召回记降级并保留融合序。
         */
        public boolean isRerankEnabled() {
            return rerankEnabled;
        }

        public void setRerankEnabled(boolean v) {
            this.rerankEnabled = v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Thresholds {
        private double top1Min = 0.55;
        private double marginMin = 0.12;
        private double domainCloseMargin = 0.08;
        private double intentSignalMin = 0.7;

        /**
         * 续轮「这句话里有没有新意图」的探测线。
         *
         * <p>刻意高于 {@link #top1Min}：这里宁可漏报也不能误报。把一句普通的槽位补充
         * 误判成新意图，会中断一个正在进行的任务；而漏判的代价只是多问一轮。
         *
         * <p>与其余阈值同处一份资产，是因为它们必须一起调。这一条原先写死在
         * {@code RuleBasedIntentProbe} 里，调低召回线时它不会跟着动，
         * 两者的相对关系就悄悄反转了——而那正是它唯一要守住的性质。
         */
        public double getIntentSignalMin() {
            return intentSignalMin;
        }

        public void setIntentSignalMin(double v) {
            this.intentSignalMin = v;
        }

        public double getTop1Min() {
            return top1Min;
        }

        public void setTop1Min(double v) {
            this.top1Min = v;
        }

        public double getMarginMin() {
            return marginMin;
        }

        public void setMarginMin(double v) {
            this.marginMin = v;
        }

        public double getDomainCloseMargin() {
            return domainCloseMargin;
        }

        public void setDomainCloseMargin(double v) {
            this.domainCloseMargin = v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopN {
        private int candidates = 5;
        private int domains = 3;

        public int getCandidates() {
            return candidates;
        }

        public void setCandidates(int v) {
            this.candidates = v;
        }

        public int getDomains() {
            return domains;
        }

        public void setDomains(int v) {
            this.domains = v;
        }
    }

    /** 通道分数归一化参数。相对归一会抹掉绝对匹配强度，使 top1Min 阈值失效，故用饱和映射。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Normalization {
        private double bm25Saturation = 8.0;

        public double getBm25Saturation() {
            return bm25Saturation;
        }

        public void setBm25Saturation(double v) {
            this.bm25Saturation = v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MultiTask {
        private List<String> conjunctions = List.of();
        private List<String> conditionals = List.of();
        private List<String> sequentialMarkers = List.of();
        private int minDistinctCapabilities = 2;

        public List<String> getConjunctions() {
            return conjunctions;
        }

        public void setConjunctions(List<String> v) {
            this.conjunctions = v == null ? List.of() : List.copyOf(v);
        }

        public List<String> getConditionals() {
            return conditionals;
        }

        public void setConditionals(List<String> v) {
            this.conditionals = v == null ? List.of() : List.copyOf(v);
        }

        /**
         * {@link #getConjunctions()} 中表示「先后」而非「并列」的那一部分。
         *
         * <p>刻意做成子集而不是另立一张全量表：连词表是多任务**检测**的依据，检测口径已随
         * 15 条负例冻结，再复制一份出来两边一定会漂。切分时凡不在本表内的连词一律按并列处理，
         * 判错的代价是把「先查后转」当成两件可并发的事，而并列在下游只是不共享输出，不会错序执行。
         */
        public List<String> getSequentialMarkers() {
            return sequentialMarkers;
        }

        public void setSequentialMarkers(List<String> v) {
            this.sequentialMarkers = v == null ? List.of() : List.copyOf(v);
        }

        public int getMinDistinctCapabilities() {
            return minDistinctCapabilities;
        }

        public void setMinDistinctCapabilities(int v) {
            this.minDistinctCapabilities = v;
        }
    }

    /** 多意图步骤进入 Planner 前的规则锚定策略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Planning {
        private double preferredMin = 0.20;
        private int maxCandidatesPerStep = 3;
        private List<String> navigationMarkers = List.of("打开", "进入", "跳转", "菜单", "页面", "去");
        private List<String> diagnosticMarkers = List.of("排查原因", "帮我排查", "查明原因", "为什么");

        public double getPreferredMin() {
            return preferredMin;
        }

        public void setPreferredMin(double v) {
            this.preferredMin = v;
        }

        public int getMaxCandidatesPerStep() {
            return maxCandidatesPerStep;
        }

        public void setMaxCandidatesPerStep(int v) {
            this.maxCandidatesPerStep = Math.max(1, v);
        }

        public List<String> getNavigationMarkers() {
            return navigationMarkers;
        }

        public void setNavigationMarkers(List<String> v) {
            this.navigationMarkers = v == null ? List.of() : List.copyOf(v);
        }

        public List<String> getDiagnosticMarkers() {
            return diagnosticMarkers;
        }

        public void setDiagnosticMarkers(List<String> v) {
            this.diagnosticMarkers = v == null ? List.of() : List.copyOf(v);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Clarify {
        private int maxRounds = 2;

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int v) {
            this.maxRounds = v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cache {
        private int ttlSeconds = 600;
        private List<String> userStateDimensions = List.of();

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int v) {
            this.ttlSeconds = v;
        }

        public List<String> getUserStateDimensions() {
            return userStateDimensions;
        }

        public void setUserStateDimensions(List<String> v) {
            this.userStateDimensions = v == null ? List.of() : List.copyOf(v);
        }
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights v) {
        this.weights = v;
    }

    public Channels getChannels() {
        return channels;
    }

    public void setChannels(Channels v) {
        this.channels = v;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds v) {
        this.thresholds = v;
    }

    public TopN getTopN() {
        return topN;
    }

    public void setTopN(TopN v) {
        this.topN = v;
    }

    public Normalization getNormalization() {
        return normalization;
    }

    public void setNormalization(Normalization v) {
        this.normalization = v;
    }

    public MultiTask getMultiTask() {
        return multiTask;
    }

    public void setMultiTask(MultiTask v) {
        this.multiTask = v;
    }

    public Planning getPlanning() {
        return planning;
    }

    public void setPlanning(Planning v) {
        this.planning = v == null ? new Planning() : v;
    }

    public Clarify getClarify() {
        return clarify;
    }

    public void setClarify(Clarify v) {
        this.clarify = v;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache v) {
        this.cache = v;
    }
}
