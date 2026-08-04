package com.huawei.finance.orchestrator.plan;

import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.stability.Api;
import java.util.Optional;
import java.util.List;

/**
 * 一份在办的多意图计划。
 *
 * <p>{@link IntentPlan} 只说「用户要办哪几件事」，本记录补上「办到第几件」。两者分开的
 * 理由写在 IntentPlan 的注释里：计划是意图的转写，游标是执行的进度，混在一个对象里
 * 就会出现计划说办完了而任务表说没有的局面。
 *
 * @param planId    计划标识
 * @param agentId   所属 Agent（架构草案阶段 1）
 * @param sessionId 所属会话。一个 Agent 会话同时只有一份在办计划，由唯一索引保证
 * @param plan      计划本体
 * @param cursor    下一件待办在 {@code plan.items()} 中的下标；等于长度表示全部办完
 * @param state     计划状态
 */
@Api
public record PlanRecord(String planId, String agentId, String sessionId, String traceId,
                         IntentPlan plan, int cursor, PlanState state, long stateVersion,
                         PendingInteraction pendingInteraction) {

    public record PendingInteraction(String taskId, String slot, List<String> expectedAnswers) {
        public PendingInteraction {
            expectedAnswers = expectedAnswers == null ? List.of() : List.copyOf(expectedAnswers);
        }
    }

    public PlanRecord {
        if (cursor < 0 || cursor > plan.items().size()) {
            throw new IllegalArgumentException(
                    "游标越界：cursor=" + cursor + " 件数=" + plan.items().size()
                            + "。越界的游标会让下游按不存在的下标取事");
        }
    }

    public PlanRecord(String planId, String agentId, String sessionId, String traceId,
                      IntentPlan plan, int cursor, PlanState state) {
        this(planId, agentId, sessionId, traceId, plan, cursor, state, cursor, null);
    }

    public PlanRecord(String planId, String agentId, String sessionId, String traceId,
                      IntentPlan plan, int cursor, PlanState state, long stateVersion) {
        this(planId, agentId, sessionId, traceId, plan, cursor, state, stateVersion, null);
    }

    /** 下一件待办。全部办完时为空。 */
    public Optional<SubIntent> next() {
        return cursor >= plan.items().size() ? Optional.empty() : Optional.of(plan.items().get(cursor));
    }

    /** 上一件已办的事。首件之前为空——条件依赖要看的正是它的结果。 */
    public Optional<SubIntent> previous() {
        return cursor == 0 ? Optional.empty() : Optional.of(plan.items().get(cursor - 1));
    }

    public boolean finished() {
        return cursor >= plan.items().size();
    }

    /** 还剩几件没办。话术里要说清「还有 N 件」。 */
    public int remaining() {
        return plan.items().size() - cursor;
    }
}
