package com.huawei.finance.orchestrator.context;

import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import com.huawei.finance.stability.Spi;
import java.util.Optional;

@Spi
public interface TaskContextStore {
    PlatformTask reserveTask(String tenantId, String agentId, String sessionId,
                             String routeDecisionId, RouteTarget target, RuntimeType runtimeType);
    Optional<PlatformTask> task(String tenantId, String agentId, String platformTaskId);
    PlatformTask bindRuntime(String tenantId, String agentId, String platformTaskId,
                             RuntimeType runtimeType, String runtimeRef, long expectedVersion);
    PlatformTask bindRuntimeAndFocus(String tenantId, String agentId, String sessionId,
                                     String platformTaskId, RuntimeType runtimeType, String runtimeRef,
                                     long expectedTaskVersion, String pendingGoalId, String routeDecisionId);
    void failRuntimeRegistration(String tenantId, String agentId, String sessionId,
                                 String platformTaskId, String pendingGoalId, String reason);
    PlatformTask closeTask(String tenantId, String agentId, String platformTaskId,
                           String reason, long expectedVersion);
    FocusView focus(String tenantId, String agentId, String sessionId);
    FocusFrame createTaskForeground(String tenantId, String agentId, String sessionId,
                                    String platformTaskId);
    FocusTransition switchToPendingGoal(String tenantId, String agentId, String sessionId,
                                        String frameId, long expectedVersion, String pendingGoalId);
    FocusFrame bindPendingGoal(String tenantId, String agentId, String frameId,
                               String pendingGoalId, String platformTaskId, long expectedVersion);
    FocusTransition failPendingGoalAndRestore(String tenantId, String agentId, String sessionId,
                                              String frameId, long expectedVersion, String previousFrameId);
    FocusFrame closeFrame(String tenantId, String agentId, String frameId, long expectedVersion);
    FocusFrame resume(String tenantId, String agentId, String sessionId,
                      String frameId, long expectedVersion);
    PendingSwitch proposeSwitch(PendingSwitch value);
    Optional<PendingSwitch> pendingSwitch(String tenantId, String agentId, String sessionId);
    PendingSwitch resolveSwitch(String tenantId, String agentId, String switchId,
                                SwitchState state, String resolvedTurnId, long expectedVersion);
    PendingGoal acceptSwitchAtomically(PendingSwitch pending, PendingGoal goal,
                                       String resolvedTurnId, long expectedSwitchVersion);
    PendingGoal createPendingGoal(PendingGoal value);
    Optional<PendingGoal> pendingGoal(String tenantId, String agentId, String pendingGoalId);
    PendingGoal transitionPendingGoal(String tenantId, String agentId, String pendingGoalId,
                                      PendingGoalState from, PendingGoalState to,
                                      String routeDecisionId, String platformTaskId, long expectedVersion);
}
