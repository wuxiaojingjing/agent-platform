package com.huawei.finance.intent;

import com.huawei.finance.common.event.EventClassification;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.stability.Api;
import java.util.Map;

/**
 * 意图引擎出参（架构草案 §3 门面）。
 *
 * <p>必须能带出 {@link #intentPlan()} 与带分数的召回候选（见草案 §4.1）：
 * 缓存路径若丢拆解，多意图会退化成列不出选项的澄清。
 *
 * <p><b>刻意是接口，且刻意不是 record。</b>这条约束前后经过三轮，记下来免得被人「简化」回去：
 *
 * <ol>
 *   <li>最初它是 record，包装一个引擎内部的 {@code FastPathResult}。record 会给每个组件
 *       生成公开访问器，于是 {@code raw()} 把内部类型直接交到调用方手里，门面就白设了。
 *   <li>于是改成 final 类、字段私有，只留下面这组访问器，用一个包内可见的
 *       {@code of(FastPathResult)} 做「内部结果 → 门面结果」的适配。这依赖门面与实现同包。
 *   <li>门面独立成模块后同包没了。改成接口反而更彻底：本类型现在连一个字段都没有，
 *       没有任何内部形状可漏；实现是 {@code com.huawei.finance.fastpath.FastPathIntentResult}，
 *       依赖方看不到它。
 * </ol>
 *
 * <p>「公开签名上不出现未标 {@code @Api} 的 agent-platform 类型」这条由 {@code StabilityBoundaryTest} 守住——
 * 也正是它逼着 {@link PathSummary} 必须一起搬进本模块，而不是留在实现那边。
 */
@Api
public interface IntentResult {

    RouteDecision decision();

    Map<String, Object> slots();

    RecallResult recall();

    /**
     * 用户原话。
     *
     * <p>刻意只交出这两个字符串，而不是整个 {@code RewriteResult}。后者带着
     * {@code ChineseAnalyzer.Analysis} 与 {@code Entity}——HanLP 分词器的产物。
     * 把它放在门面上，等于连分词器的数据形状一起承诺；换掉 HanLP 就成了破坏性变更，
     * 而分词实现本该是引擎可以随时替换的内部选择。
     *
     * <p>它同时是本模块能只依赖三个基础模块的原因之一：{@code RewriteResult} 留在实现那边，
     * 门面上就不会出现 HanLP 的类型，依赖方也就不必把 HanLP 拖进 classpath。
     *
     * <p>实测的调用需求也只有这么多：{@code ChatService} 用的是 {@code originalQuery()}。
     */
    String originalQuery();

    /** 归一化后的检索文本。展示「按 X 为你查到」这类回执时用。 */
    String normalizedQuery();

    EventClassification event();

    String templateKey();

    IntentPlan intentPlan();

    PathSummary path();
}
