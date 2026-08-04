package com.huawei.finance.common.event;

import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/**
 * 事件分类所需的活跃任务轻读视图（实施架构 §2.1.1 注 5：任务帧走中控轻读）。
 *
 * <p>刻意只暴露分类所需字段。事件分类库不得依赖中控的完整任务实体，
 * 否则共享库会被任务模型的演进反向绑架。
 *
 * @param taskId          任务标识
 * @param status          任务状态，取 RUNNING / SUSPENDED / CONFIRM_PENDING / CLARIFY_PENDING
 * @param domain          任务所属科技领域，用于判定话题是否切换
 * @param capabilityId    任务对应的能力。续轮短路要用它重跑必填槽位校验——
 *                        补充了一个槽位不等于槽位齐了，不重跑就会带着空槽去执行
 * @param pendingSlot     待澄清槽位名，无则为 null
 * @param expectedAnswers 待澄清槽位的候选取值，用于识别 SUPPLEMENT
 * @param filledSlots     任务已确认的槽位，续轮时与本轮抽取结果合并
 * @param clarifyRounds   已发生的澄清轮数
 */
@Api
public record ActiveTaskView(
        String taskId,
        String status,
        String domain,
        String capabilityId,
        String pendingSlot,
        List<String> expectedAnswers,
        Map<String, Object> filledSlots,
        int clarifyRounds) {

    public ActiveTaskView {
        expectedAnswers = expectedAnswers == null ? List.of() : List.copyOf(expectedAnswers);
        filledSlots = filledSlots == null ? Map.of() : Map.copyOf(filledSlots);
    }

    public boolean awaitingConfirmation() {
        return "CONFIRM_PENDING".equals(status);
    }

    public boolean awaitingReview() {
        return "REVIEW_PENDING".equals(status);
    }

    public boolean awaitingClarification() {
        return "CLARIFY_PENDING".equals(status);
    }
}
