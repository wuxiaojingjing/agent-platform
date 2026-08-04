package com.huawei.finance.fastpath;

import com.huawei.finance.common.event.EventClassification;
import com.huawei.finance.fastpath.recall.HybridRecall;
import com.huawei.finance.fastpath.rewrite.RewriteResult;
import com.huawei.finance.intent.PathSummary;
import java.util.Map;

/**
 * 一次快路径判定的中间状态，只在一次请求内存活。
 *
 * <p>存在的理由是让「顺序执行」与「图执行」两条路径跑**同一段步骤代码**：步骤只读写这个
 * 状态，谁来决定下一步去哪由执行方式决定。两条路径若各写一份步骤逻辑，
 * 所谓「逐位一致」就只是一句承诺，而不是构造上成立的事实。
 *
 * <p>刻意是可变对象。快路径的步骤之间有真实的顺序依赖（槽位要合并、缓存键要用改写结果），
 * 用不可变对象层层传递会在每一步造一个新记录，读起来像是每步都换了个世界。
 */
final class FastPathState {

    /** 挂在图会话全局态上的键。加前缀是因为全局态是共用命名空间。 */
    static final String KEY = "__agent_fastpath_state";

    private final FastPathRequest request;

    private RewriteResult rewrite;
    private Map<String, Object> turnSlots = Map.of();
    private Map<String, Object> slots = Map.of();
    private EventClassification event;
    private String cacheKey;
    private Map<String, Object> ruleContext = Map.of();
    private HybridRecall.Output recallOutput;

    /**
     * 召回投影出的候选排名，按融合分降序。
     *
     * <p>存下来是为了让仲裁后的对照（FP-62）用的是与仲裁前**完全同一份**排名。
     * 重新投影一次也能得到结果，但那时对照的是两份各自算出来的排序，
     * 而这份数据的全部用途就是回答「模型是不是推翻了检索的第一名」——
     * 拿两份分别算的排序去对照，答案的可信度就取决于两次算法没有分歧。
     */
    private java.util.List<com.huawei.finance.obs.trace.ScoredCandidate> rankedCandidates = java.util.List.of();

    /** 各阶段累计耗时（纳秒），键与 {@link FastPathEngine} 的 PHASE_* 一致。 */
    private final java.util.Map<String, Long> phaseNanos = new java.util.LinkedHashMap<>();

    /**
     * 已经定下的出口。
     *
     * <p>非空即表示「不必再往下走」——续轮短路、缓存命中、强规则命中都会在这里落结果。
     * 图执行的条件边据此决定是直奔终点还是继续下一步。
     */
    private FastPathResult result;

    /**
     * 要搬到执行线程上的东西。
     *
     * <p>在构造状态时（也就是还在调用者线程上时）取一份快照。图引擎不在调用者线程上跑节点，
     * 而这两样都是 ThreadLocal：{@code RequestContext} 不搬，工作线程里的网关往返
     * 计不进本次请求，往返序列在看板与用例里恒为空；MDC 不搬，快路径期间的日志
     * 行首没有 traceId，而那正是出问题时最需要它的一段。
     *
     * @param ctx 调用上下文，本身是线程安全的，多个节点线程共用同一个实例即可
     * @param mdc 调用者线程的日志上下文快照，可能为 null
     */
    record Carried(com.huawei.finance.common.context.RequestContext ctx, Map<String, String> mdc) {
    }

    private final Carried carried;

    FastPathState(FastPathRequest request) {
        this.request = request;
        this.carried = new Carried(request.ctx(), org.slf4j.MDC.getCopyOfContextMap());
    }

    Carried carried() {
        return carried;
    }

    FastPathRequest request() {
        return request;
    }

    RewriteResult rewrite() {
        return rewrite;
    }

    void rewrite(RewriteResult value) {
        this.rewrite = value;
    }

    Map<String, Object> turnSlots() {
        return turnSlots;
    }

    void turnSlots(Map<String, Object> value) {
        this.turnSlots = value;
    }

    Map<String, Object> slots() {
        return slots;
    }

    void slots(Map<String, Object> value) {
        this.slots = value;
    }

    EventClassification event() {
        return event;
    }

    void event(EventClassification value) {
        this.event = value;
    }

    String cacheKey() {
        return cacheKey;
    }

    void cacheKey(String value) {
        this.cacheKey = value;
    }

    Map<String, Object> ruleContext() {
        return ruleContext;
    }

    void ruleContext(Map<String, Object> value) {
        this.ruleContext = value;
    }

    HybridRecall.Output recallOutput() {
        return recallOutput;
    }

    void recallOutput(HybridRecall.Output value) {
        this.recallOutput = value;
    }

    java.util.List<com.huawei.finance.obs.trace.ScoredCandidate> rankedCandidates() {
        return rankedCandidates;
    }

    void rankedCandidates(java.util.List<com.huawei.finance.obs.trace.ScoredCandidate> value) {
        this.rankedCandidates = value == null ? java.util.List.of() : value;
    }

    void recordPhase(String phase, long nanos) {
        phaseNanos.merge(phase, nanos, Long::sum);
    }

    java.util.Map<String, Long> phaseNanos() {
        return java.util.Map.copyOf(phaseNanos);
    }

    FastPathResult result() {
        return result;
    }

    /** 落出口并挂上路径摘要，避免调用方漏塞。 */
    void result(FastPathResult value) {
        this.result = value.withPath(PathSummaries.from(this, value.decision()));
    }

    boolean decided() {
        return result != null;
    }
}
