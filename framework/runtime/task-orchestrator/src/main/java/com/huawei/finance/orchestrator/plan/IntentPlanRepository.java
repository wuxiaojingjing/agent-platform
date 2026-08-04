package com.huawei.finance.orchestrator.plan;

import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.stability.Spi;
import java.util.Optional;
import java.util.List;
import java.util.Map;

/** 多意图计划状态端口；实现必须保证一个 Agent 会话最多一个活动计划。 */
@Spi
public interface IntentPlanRepository {
    PlanRecord open(String agentId, String sessionId, String traceId, IntentPlan plan);
    Optional<PlanRecord> findActiveBySession(String agentId, String sessionId);
    default Optional<PlanRecord> findById(String agentId, String planId) { return Optional.empty(); }
    boolean advance(String planId, int from);
    /** 带运行态版本的 CAS；等待确认、恢复、取消都必须经此迁移。 */
    default boolean transition(String planId, PlanState from, PlanState to, long expectedVersion) {
        return false;
    }
    default boolean waitFor(String planId, long expectedVersion, PlanState waitingState,
                            String taskId, String pendingSlot, List<String> expectedAnswers) {
        return transition(planId, PlanState.IN_PROGRESS, waitingState, expectedVersion);
    }
    void abandonActive(String agentId, String sessionId, String reason);

    default List<PlanStepRecord> steps(String planId) {
        return List.of();
    }

    /** Static Plan 自己持有的已确认参数；平台任务表只保存 runtimeRef。 */
    default Map<String, Object> parameters(String agentId, String planId) {
        return Map.of();
    }

    /** 首轮解析后保存参数快照，续办时由 Runtime 恢复。 */
    default void saveParameters(String agentId, String planId, Map<String, Object> parameters) {
    }

    default void saveStep(PlanStepRecord step) {
    }

    default boolean saveStepAndAdvance(PlanStepRecord step, int from) {
        saveStep(step);
        return advance(step.planId(), from);
    }

    default Optional<PlanConditionResolutionRecord> findConditionResolution(
            String planId, int stepIndex, String factDigest) {
        return Optional.empty();
    }

    default void saveConditionResolution(PlanConditionResolutionRecord resolution) {
    }
}
