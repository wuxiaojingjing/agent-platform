package com.huawei.finance.orchestrator.context;

import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;

@Api
public final class TaskContextModels {
    private TaskContextModels() {}

    public enum RuntimeType { TASK, STATIC_PLAN, WORKFLOW, AGENT_LOOP }
    public enum PlatformTaskStatus { OPEN, CLOSED }
    public enum BindingState { RESERVED, BOUND, FAILED }
    public enum FocusState { FOREGROUND, SUSPENDED, CLOSED }
    public enum FocusSubjectType { PLATFORM_TASK, PENDING_GOAL }
    public enum SwitchState { PENDING, ACCEPTED, REJECTED, STALE }
    public enum PendingGoalState { ROUTING, STARTING_RUNTIME, BOUND, COMPLETED, FAILED }

    public record PlatformTask(
            String tenantId, String platformTaskId, String agentId, String sessionId,
            RouteTarget routeTarget, RuntimeType runtimeType, String runtimeRef,
            PlatformTaskStatus status, BindingState bindingState, String closeReason,
            String routeDecisionId, long version, Instant createdAt, Instant updatedAt) {}

    public record FocusFrame(
            String tenantId, String frameId, String agentId, String sessionId,
            FocusSubjectType subjectType, String subjectRef, FocusState state,
            long version, Instant lastFocusedAt, Instant suspendedAt, Instant closedAt) {}

    public record PendingSwitch(
            String tenantId, String switchId, String agentId, String sessionId,
            String foregroundFrameId, long foregroundFrameVersion, String sourceTurnId,
            int spanStart, int spanEnd, String spanHash, SwitchState state,
            String resolvedTurnId, long version, Instant createdAt, Instant resolvedAt) {}

    public record PendingGoal(
            String tenantId, String pendingGoalId, String agentId, String sessionId,
            String switchId, String previousFrameId, String sourceTurnId,
            int spanStart, int spanEnd, String spanHash, PendingGoalState state,
            String routeDecisionId, String boundPlatformTaskId, long version,
            Instant createdAt, Instant updatedAt) {}

    public record FocusView(FocusFrame foreground, List<FocusFrame> suspended) {
        public FocusView { suspended = suspended == null ? List.of() : List.copyOf(suspended); }
    }

    public record FocusTransition(FocusFrame previous, FocusFrame foreground, FocusView view) {}
}
