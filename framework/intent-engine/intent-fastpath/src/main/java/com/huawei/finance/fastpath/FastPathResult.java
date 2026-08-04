package com.huawei.finance.fastpath;

import com.huawei.finance.common.event.EventClassification;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.fastpath.rewrite.RewriteResult;
import com.huawei.finance.intent.PathSummary;
import java.util.Map;

/**
 * 快路径出参。
 *
 * <p>除了出口本身，还带出改写结果、槽位与召回明细。中控要用槽位组装 UnifiedTask，
 * 回复层要用它渲染模板；让每一层再算一遍，只会算出不一致的结果。
 *
 * @param decision    四出口
 * @param rewrite     改写结果
 * @param slots       合并后的槽位（会话已确认 + 本轮抽取）
 * @param recall      召回明细，短路路径为 null
 * @param event       事件分类结论
 * @param templateKey 强规则指定的模板，无则为 null，由回复层按出口自选
 * @param intentPlan  多意图拆解结果，非多意图或切不开时为 null
 * @param path        执行路径摘要（供运营观测）；由 {@link FastPathState#result} 补齐
 */
public record FastPathResult(
        RouteDecision decision,
        RewriteResult rewrite,
        Map<String, Object> slots,
        RecallResult recall,
        EventClassification event,
        String templateKey,
        IntentPlan intentPlan,
        PathSummary path) {

    public FastPathResult {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        path = path == null ? PathSummary.empty() : path;
    }

    /** 路径摘要稍后由 {@link FastPathState#result} 补齐。 */
    public FastPathResult(RouteDecision decision, RewriteResult rewrite,
                          Map<String, Object> slots, RecallResult recall,
                          EventClassification event, String templateKey, IntentPlan intentPlan) {
        this(decision, rewrite, slots, recall, event, templateKey, intentPlan, PathSummary.empty());
    }

    public static FastPathResult shortCircuit(RouteDecision decision, RewriteResult rewrite,
                                              Map<String, Object> slots, EventClassification event,
                                              String templateKey) {
        return new FastPathResult(decision, rewrite, slots, null, event, templateKey, null);
    }

    FastPathResult withPath(PathSummary path) {
        return new FastPathResult(decision, rewrite, slots, recall, event, templateKey, intentPlan, path);
    }
}
