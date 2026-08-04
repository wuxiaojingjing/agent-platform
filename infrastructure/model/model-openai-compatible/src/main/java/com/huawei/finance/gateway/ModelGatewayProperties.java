package com.huawei.finance.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型网关配置。
 *
 * <p>密钥解析顺序：环境变量 {@link #apiKeyEnv} → 可选的 {@link #apiKey} 配置项。
 * 生产仍优先走环境变量；本地可把 key 写进配置文件以便免 source。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.model")
public class ModelGatewayProperties {

    /** OpenAI 兼容网关地址。行内替换为统一推理入口。默认承载 embedding / rerank。 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** 存放密钥的环境变量名（主网关：embedding / rerank）。 */
    private String apiKeyEnv = "SILICONFLOW_API_KEY";

    /**
     * 主网关密钥（可选）。环境变量未设置时才用；不要把生产密钥提交进仓库。
     */
    private String apiKey = "";

    private int connectTimeoutMs = 3000;

    /** 连接池上限。连接复用是硬要求：实测复用后 embedding 0.35s，每次新建 1.2-2.2s。 */
    private int maxConnections = 32;
    private int maxConnectionsPerRoute = 32;

    /** 空闲连接存活时间。设得太短会退化成每次重新握手，等于没有连接池。 */
    private int keepAliveSeconds = 300;

    /**
     * 连接建立失败的重试次数。
     *
     * <p>与「请求级重试」分开：连接根本没建立起来时，请求从未到达服务端，重试不存在重复
     * 执行风险。当前公网链路握手失败率约 40%，不重试则功能不可用。链路修好后应调回 1。
     */
    private int connectRetries = 3;

    /**
     * 连接重试之间的等待时长（毫秒）。
     *
     * <p>默认 0，即立刻重试：快路径的毫秒预算里挤不出退避时间，而握手失败通常是瞬时抖动，
     * 等待并不会提高下一次的成功率。链路稳定后若改为按 5xx 退避，应在此设置非零值。
     */
    private int retryBackoffMs = 0;

    /** 请求已发出但返回 5xx 时的重试次数。v0.7 §3.3「重试一次」即此项。 */
    private int requestRetries = 1;

    /** 连续失败达到该比例时打开熔断器。 */
    private float circuitBreakerFailureRateThreshold = 60f;

    /** 熔断打开后的等待时长（秒）。 */
    private int circuitBreakerWaitSeconds = 20;

    /**
     * Chat / tools 独立端点（仲裁、agent loop）。
     *
     * <p>{@code baseUrl} / {@code apiKeyEnv} 任一留空则与主网关共用。典型拆法：embedding
     * 留硅基流动，仲裁与慢路径规划走 DeepSeek。
     */
    private final Chat chat = new Chat();

    private final Embedding embedding = new Embedding();
    private final Arbitration arbitration = new Arbitration();
    private final LogicalModel contextRewrite = new LogicalModel();
    private final LogicalModel continuation = new LogicalModel();
    private final LogicalModel loop = new LogicalModel();
    private final LogicalModel response = new LogicalModel();
    private final Rerank rerank = new Rerank();

    /** Chat 端点覆盖。字段空 = 回落到主 {@link #baseUrl} / {@link #apiKeyEnv}。 */
    public static class Chat {
        private String baseUrl = "";
        private String apiKeyEnv = "";
        /** 可选直写密钥；环境变量未设置时才用。 */
        private String apiKey = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /** 解析后的 chat 基址：未单独配置时等于主网关。 */
    public String resolveChatBaseUrl() {
        String configured = chat.getBaseUrl();
        return configured == null || configured.isBlank() ? baseUrl : configured;
    }

    /** 解析后的 chat 密钥环境变量名。 */
    public String resolveChatApiKeyEnv() {
        String configured = chat.getApiKeyEnv();
        return configured == null || configured.isBlank() ? apiKeyEnv : configured;
    }

    /**
     * 解析主网关密钥：环境变量优先，其次 {@code huawei.finance.agent.model.api-key}。
     */
    public String resolveApiKey() {
        return firstNonBlank(System.getenv(apiKeyEnv), apiKey);
    }

    /**
     * 解析 chat 密钥：环境变量优先，其次 {@code huawei.finance.agent.model.chat.api-key}，
     * 再回落主网关密钥（未拆端点时共用一把 key）。
     */
    public String resolveChatApiKey() {
        String fromEnv = System.getenv(resolveChatApiKeyEnv());
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromChat = chat.getApiKey();
        if (fromChat != null && !fromChat.isBlank()) {
            return fromChat;
        }
        // chat 未单独配 key 时，与主网关共用（含主网关的 yaml api-key）
        return resolveApiKey();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    /** 语义召回模型（实施架构 §2.5.6 定版）。 */
    public static class Embedding {
        private String model = "Qwen/Qwen3-Embedding-0.6B";
        /** 向量维度，必须与 OpenSearch knn_vector 的 dimension 一致。 */
        private int dimensions = 1024;
        private int timeoutMs = 2000;

        /**
         * Qwen3-Embedding 训练一致的 query 前缀。
         *
         * <p>任务说明使用英文；查询保留用户原语言。格式严格为
         * {@code Instruct: <task>\nQuery:<query>}，{@code Query:} 后不额外插入空格。
         * 该格式适用于 Qwen3-Embedding 全系列，包括 8B。
         * 文档侧不拼该指令。改这个字段会切换索引版本与出口缓存键。
         */
        private String queryInstruction =
                "Instruct: Given a user request in a mobile banking application, retrieve the most "
                        + "relevant banking capability that can fulfill the request\nQuery:";

        /** 指令模板版本，随 queryInstruction 一同变更。 */
        private String instructionVersion = "emb-instruct-v2";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getQueryInstruction() {
            return queryInstruction;
        }

        public void setQueryInstruction(String queryInstruction) {
            this.queryInstruction = queryInstruction;
        }

        public String formatQuery(String query) {
            return queryInstruction + (query == null ? "" : query);
        }

        public String getInstructionVersion() {
            return instructionVersion;
        }

        public void setInstructionVersion(String instructionVersion) {
            this.instructionVersion = instructionVersion;
        }
    }

    /** 仲裁模型。 */
    public static class Arbitration {
        private String model = "Qwen/Qwen3-30B-A3B-Instruct-2507";
        private int timeoutMs = 5000;
        private int maxTokens = 256;
        /** 固定为 0：v0.7 §3.3 要求接近确定性解码，避免重复运行跳变。 */
        private double temperature = 0.0;
        /** 提示词版本，进 RouteDecision.promptVersion。 */
        private String promptVersion = "arb-skill-v1";

        /**
         * 是否发送 {@code enable_thinking} 参数。
         *
         * <p>null 表示不发送。定版的 Instruct-2507 本身不带思考模式，多发一个参数无益；
         * 但换成 Qwen3-8B 这类混合模型时必须显式设为 false，否则会先吐一大段思考再出 JSON，
         * 时延翻倍且解析容易失败。
         */
        private Boolean enableThinking;

        /**
         * 进入 prompt 的候选能力数上限。
         *
         * <p>与融合的 {@code topN.candidates} 是两回事，不能省。融合那个是检索侧的取数，
         * 归知识部调；这个是模型侧的花钱上限，归算法组调。两者恰好都是 5 是巧合，
         * 把它们合成一个配置项，等于让调检索的人顺手改掉了模型单价。
         */
        private int maxPromptCandidates = 5;

        /**
         * 仲裁 prompt（system + user）的字符数上限。
         *
         * <p>这是 token 上限的代理量：没有 Qwen 分词器就算不出准确 token 数，而中文下
         * 1 token 约当 1–1.5 字，按字符设限是偏保守的一侧。真值由服务端回报的
         * {@code usage.prompt_tokens} 指标给出，用于事后校准这个数。
         *
         * <p>它防的不是今天——今天 5 个候选拼出来约千字。它防的是有人往候选渲染里加
         * description 或 inputSchema：外部同类系统就是这么从几百 token 涨到 7913 的。
         */
        private int maxPromptChars = 4000;

        /**
         * 仲裁 chat 是否走 SSE 流式以便拆出首帧 / 首 token / 均 token（FP-63）。
         *
         * <p>默认开。关掉则退回整包 JSON，只剩端到端 {@code huawei.finance.agent.gateway.latency}——
         * 那是供应商不支持 stream、或排障时怀疑流式解析本身的出口。
         */
        private boolean streamTiming = true;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxPromptCandidates() {
            return maxPromptCandidates;
        }

        public void setMaxPromptCandidates(int maxPromptCandidates) {
            this.maxPromptCandidates = maxPromptCandidates;
        }

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
        }

        public boolean isStreamTiming() {
            return streamTiming;
        }

        public void setStreamTiming(boolean streamTiming) {
            this.streamTiming = streamTiming;
        }

        public Boolean getEnableThinking() {
            return enableThinking;
        }

        public void setEnableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
        }
    }

    /** Independent logical-model settings; blank model reuses arbitration's model name. */
    public static class LogicalModel {
        private String model = "";
        private int maxTokens = 256;
        private double temperature = 0.0;
        private int timeoutMs = 5000;
        private int cacheTtlSeconds = 60;
        private int cacheMaxEntries = 1000;
        private int maxAttempts = 1;
        private int retryBackoffMs = 0;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getCacheTtlSeconds() { return cacheTtlSeconds; }
        public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
        public int getCacheMaxEntries() { return cacheMaxEntries; }
        public void setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(int retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
    }

    /** 重排。默认关闭。 */
    public static class Rerank {
        private boolean enabled = false;
        private String model = "Qwen/Qwen3-Reranker-0.6B";
        private int timeoutMs = 2000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    public void setApiKeyEnv(String apiKeyEnv) {
        this.apiKeyEnv = apiKeyEnv;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerRoute() {
        return maxConnectionsPerRoute;
    }

    public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
        this.maxConnectionsPerRoute = maxConnectionsPerRoute;
    }

    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public int getConnectRetries() {
        return connectRetries;
    }

    public void setConnectRetries(int connectRetries) {
        this.connectRetries = connectRetries;
    }

    public int getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(int retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getRequestRetries() {
        return requestRetries;
    }

    public void setRequestRetries(int requestRetries) {
        this.requestRetries = requestRetries;
    }

    public float getCircuitBreakerFailureRateThreshold() {
        return circuitBreakerFailureRateThreshold;
    }

    public void setCircuitBreakerFailureRateThreshold(float v) {
        this.circuitBreakerFailureRateThreshold = v;
    }

    public int getCircuitBreakerWaitSeconds() {
        return circuitBreakerWaitSeconds;
    }

    public void setCircuitBreakerWaitSeconds(int v) {
        this.circuitBreakerWaitSeconds = v;
    }

    public Chat getChat() {
        return chat;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Arbitration getArbitration() {
        return arbitration;
    }

    public LogicalModel getContextRewrite() { return contextRewrite; }

    public LogicalModel getContinuation() { return continuation; }

    public LogicalModel getLoop() { return loop; }

    public LogicalModel getResponse() { return response; }

    public String resolveLogicalModel(LogicalModel logical) {
        return logical.getModel() == null || logical.getModel().isBlank()
                ? arbitration.getModel() : logical.getModel();
    }

    public Rerank getRerank() {
        return rerank;
    }
}
