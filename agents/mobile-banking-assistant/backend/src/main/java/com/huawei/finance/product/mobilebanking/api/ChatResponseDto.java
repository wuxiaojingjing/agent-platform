package com.huawei.finance.product.mobilebanking.api;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.model.ResponseAction;
import java.util.List;

/**
 * 会话应答。
 *
 * <p>把 {@code RouteDecision} 与 {@code ResponsePlan} 原样带出来，不只返回一句文本：
 * 演示与联调时要看的正是「为什么走了这个出口」，藏起来就只能翻日志。
 *
 * <p>执行路径摘要不进本契约——那是观测体系（{@code /internal/console/recent}）的事，
 * 面客/联调应答不应被观测字段膨胀。
 *
 * @param traceId       链路标识
 * @param text          渲染后的面客文本
 * @param decision      四出口全量结构
 * @param plan          回复计划
 * @param taskId        任务标识
 * @param usedTemplate  实际使用的模板
 * @param fellBack      是否走了模板兜底
 * @param degradedChannels 本轮被摘除的召回通道
 */
public record ChatResponseDto(
        String traceId,
        String text,
        RouteDecision decision,
        ResponsePlan plan,
        String taskId,
        String usedTemplate,
        boolean fellBack,
        List<String> degradedChannels,
        List<ResponseAction> actions) {
}
