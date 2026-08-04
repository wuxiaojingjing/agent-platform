package com.huawei.finance.common.event;

import com.huawei.finance.stability.Api;

/**
 * 事件分类结论。
 *
 * <p>{@code classifierVersion} 独立于意图资产版本：v0.7 §3.5 要求事件分类有独立规则版本、
 * 独立评测集与独立回归门槛，版本不独立就无法单独评测。
 *
 * @param event             事件类型
 * @param confidence        置信度
 * @param classifierVersion 分类器规则版本
 * @param matchedRule       命中的规则标识，用于打点与回归定位
 */
@Api
public record EventClassification(
        InputEvent event,
        double confidence,
        String classifierVersion,
        String matchedRule) {

    /**
     * 置信是否足以据此短路。
     *
     * <p>v0.7 §3.3：分类置信不足时必须重新进入完整快路径，不得凭低置信短路，
     * 否则会把新任务误判为续轮。
     */
    public boolean confidentEnoughToShortCircuit(double threshold) {
        return event.allowsContinuationShortCircuit() && confidence >= threshold;
    }
}
