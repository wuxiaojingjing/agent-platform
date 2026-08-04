package com.huawei.finance.runtime;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.orchestrator.context.PlatformTaskContextManager;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.task.TaskRecord;
import com.huawei.finance.orchestrator.task.TaskRepository;
import com.huawei.finance.orchestrator.continuation.RuntimeContinuationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Keeps platform ownership/focus separate from runtime-owned state. */
public class PlatformRuntimeBridge {
    private final TaskContextStore store;
    private final PlatformTaskContextManager manager;
    private final TaskRepository tasks;
    private final RuntimeRegistrationCompensator compensator;
    private final Optional<RuntimeContinuationRegistry> continuations;

    public PlatformRuntimeBridge(TaskContextStore store, PlatformTaskContextManager manager,
                                 TaskRepository tasks) {
        this(store, manager, tasks, RuntimeRegistrationCompensator.NOOP, Optional.empty());
    }

    public PlatformRuntimeBridge(TaskContextStore store, PlatformTaskContextManager manager,
                                 TaskRepository tasks, RuntimeRegistrationCompensator compensator) {
        this(store, manager, tasks, compensator, Optional.empty());
    }

    public PlatformRuntimeBridge(TaskContextStore store, PlatformTaskContextManager manager,
                                 TaskRepository tasks, RuntimeRegistrationCompensator compensator,
                                 Optional<RuntimeContinuationRegistry> continuations) {
        this.store = store; this.manager = manager; this.tasks = tasks; this.compensator = compensator;
        this.continuations = continuations == null ? Optional.empty() : continuations;
    }

    public Optional<TaskRecord> foregroundTask(String tenantId, String agentId, String sessionId) {
        FocusFrame frame = store.focus(tenantId, agentId, sessionId).foreground();
        if (frame == null || frame.subjectType() != FocusSubjectType.PLATFORM_TASK) return Optional.empty();
        return store.task(tenantId, agentId, frame.subjectRef())
                .filter(t -> t.bindingState() == BindingState.BOUND)
                .filter(t -> t.runtimeType() == RuntimeType.TASK || t.runtimeType() == RuntimeType.WORKFLOW)
                .flatMap(t -> tasks.findById(t.runtimeRef()));
    }

    public Optional<PlatformTask> foregroundPlatformTask(String tenantId, String agentId, String sessionId) {
        FocusFrame frame = store.focus(tenantId, agentId, sessionId).foreground();
        if (frame == null || frame.subjectType() != FocusSubjectType.PLATFORM_TASK) return Optional.empty();
        return store.task(tenantId, agentId, frame.subjectRef());
    }

    public PlatformTask register(RequestContext context, RouteDecision decision, RuntimeType runtimeType,
                                 String runtimeRef, boolean terminal) {
        return register(context, decision, runtimeType, runtimeRef, terminal, null);
    }

    public PlatformTask register(RequestContext context, RouteDecision decision, RuntimeType runtimeType,
                                 String runtimeRef, boolean terminal, String pendingGoalId) {
        String tenantId = context.spaceId();
        Optional<PlatformTask> existing = foregroundPlatformTask(tenantId, context.agentId(), context.sessionId());
        Optional<PlatformTask> sameRuntime = existing
                .filter(t -> runtimeType == t.runtimeType() && runtimeRef.equals(t.runtimeRef()));
        if (sameRuntime.isPresent()) {
            if (terminal) closeForegroundIfTerminal(context, runtimeRef, true);
            return sameRuntime.get();
        }
        RouteTarget target = decision.target() != null ? decision.target()
                : new RouteTarget(targetType(decision.decision()), decision.selectedCandidateId());
        PlatformTask reserved = null;
        try {
            reserved = manager.reserve(tenantId, context.agentId(), context.sessionId(),
                    context.traceId(), target, runtimeType);
            if (terminal) {
                PlatformTask bound = manager.bind(tenantId, context.agentId(), reserved.platformTaskId(),
                        runtimeType, runtimeRef, reserved.version());
                if (pendingGoalId != null) completeDirectPendingGoal(context, pendingGoalId);
                return store.closeTask(tenantId, context.agentId(), bound.platformTaskId(),
                        "RUNTIME_TERMINAL", bound.version());
            }
            return manager.bindAndFocus(tenantId, context.agentId(), context.sessionId(),
                    reserved.platformTaskId(), runtimeType, runtimeRef, reserved.version(),
                    pendingGoalId, context.traceId());
        } catch (RuntimeException failure) {
            compensateFailure(context, runtimeType, runtimeRef, pendingGoalId, reserved, failure);
            throw failure;
        }
    }

    public void completeDirectPendingGoal(RequestContext context, String pendingGoalId) {
        if (pendingGoalId == null) return;
        PendingGoal goal = store.pendingGoal(context.spaceId(), context.agentId(), pendingGoalId).orElse(null);
        FocusFrame frame = store.focus(context.spaceId(), context.agentId(), context.sessionId()).foreground();
        if (goal == null || frame == null || frame.subjectType() != FocusSubjectType.PENDING_GOAL) return;
        store.transitionPendingGoal(context.spaceId(), context.agentId(), pendingGoalId, PendingGoalState.ROUTING,
                PendingGoalState.COMPLETED, context.traceId(), null, goal.version());
        store.closeFrame(context.spaceId(), context.agentId(), frame.frameId(), frame.version());
    }

    public Optional<PlatformTask> resumeByRuntimeRef(RequestContext context, String runtimeRef) {
        FocusView view = store.focus(context.spaceId(), context.agentId(), context.sessionId());
        for (FocusFrame frame : view.suspended()) {
            PlatformTask task = store.task(context.spaceId(), context.agentId(), frame.subjectRef()).orElse(null);
            if (task != null && runtimeRef.equals(task.runtimeRef())) {
                manager.resume(context.spaceId(), context.agentId(), context.sessionId(), frame.frameId(), frame.version());
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    public Optional<PlatformTask> runtimeForFrame(RequestContext context, String frameId) {
        return store.focus(context.spaceId(), context.agentId(), context.sessionId()).suspended().stream()
                .filter(frame -> frame.frameId().equals(frameId)).findFirst()
                .flatMap(frame -> store.task(context.spaceId(), context.agentId(), frame.subjectRef()));
    }

    public long runtimeStateVersion(RequestContext context, PlatformTask task) {
        if (task == null || task.runtimeRef() == null) return 0;
        return continuations.map(registry -> registry.describe(task.runtimeType(), context.spaceId(),
                        context.agentId(), task.runtimeRef()).stateVersion())
                .orElseGet(() -> task.runtimeType() == RuntimeType.TASK || task.runtimeType() == RuntimeType.WORKFLOW
                        ? tasks.findById(task.runtimeRef()).map(TaskRecord::stateVersion).orElse(0L) : 0L);
    }

    /** Returns ephemeral resume actions only when there is no foreground task to conflict with. */
    public List<ResumeTarget> availableResumeTargets(RequestContext context) {
        FocusView focus = store.focus(context.spaceId(), context.agentId(), context.sessionId());
        if (focus.foreground() != null) return List.of();
        List<ResumeTarget> targets = new ArrayList<>();
        for (FocusFrame frame : focus.suspended()) {
            PlatformTask task = store.task(context.spaceId(), context.agentId(), frame.subjectRef()).orElse(null);
            if (task == null || task.bindingState() != BindingState.BOUND || task.runtimeRef() == null) continue;
            String label = task.routeTarget() == null || task.routeTarget().id() == null
                    ? task.runtimeRef() : task.routeTarget().id();
            long version = task.runtimeType() == RuntimeType.TASK || task.runtimeType() == RuntimeType.WORKFLOW
                    ? tasks.findById(task.runtimeRef()).map(TaskRecord::stateVersion).orElse(0L) : 0L;
            if (continuations.isPresent()) {
                try {
                    var snapshot = continuations.get().describe(task.runtimeType(), context.spaceId(),
                            context.agentId(), task.runtimeRef());
                    if (snapshot.displaySummary() != null && !snapshot.displaySummary().isBlank()) {
                        label = snapshot.displaySummary();
                    }
                    version = snapshot.stateVersion();
                } catch (RuntimeException ignored) {
                    // A stale Runtime must not turn an otherwise successful response into an error.
                }
            }
            targets.add(new ResumeTarget(task.runtimeRef(), label, version));
        }
        return List.copyOf(targets);
    }

    public record ResumeTarget(String runtimeRef, String displaySummary, long stateVersion) {}

    public PlatformTask reserve(RequestContext context, RouteDecision decision, RuntimeType runtimeType) {
        RouteTarget target = decision.target() != null ? decision.target()
                : new RouteTarget(targetType(decision.decision()), decision.selectedCandidateId());
        return manager.reserve(context.spaceId(), context.agentId(), context.sessionId(), context.traceId(),
                target, runtimeType);
    }

    public PlatformTask bindAndFocus(RequestContext context, PlatformTask reserved,
                                     RuntimeType type, String runtimeRef) {
        return bindAndFocus(context, reserved, type, runtimeRef, null);
    }

    public PlatformTask bindAndFocus(RequestContext context, PlatformTask reserved,
                                     RuntimeType type, String runtimeRef, String pendingGoalId) {
        try {
            return manager.bindAndFocus(context.spaceId(), context.agentId(), context.sessionId(),
                    reserved.platformTaskId(), type, runtimeRef, reserved.version(), pendingGoalId,
                    context.traceId());
        } catch (RuntimeException failure) {
            compensateFailure(context, type, runtimeRef, pendingGoalId, reserved, failure);
            throw failure;
        }
    }

    public void failReservedRuntime(RequestContext context, PlatformTask reserved, String pendingGoalId,
                                    String reason) {
        store.failRuntimeRegistration(context.spaceId(), context.agentId(), context.sessionId(),
                reserved == null ? null : reserved.platformTaskId(), pendingGoalId, reason);
    }

    public void closeForegroundIfTerminal(RequestContext context, String runtimeRef, boolean terminal) {
        if (!terminal) return;
        FocusView focus = store.focus(context.spaceId(), context.agentId(), context.sessionId());
        FocusFrame frame = focus.foreground();
        if (frame == null) return;
        PlatformTask task = store.task(context.spaceId(), context.agentId(), frame.subjectRef()).orElse(null);
        if (task == null || !runtimeRef.equals(task.runtimeRef())) return;
        store.closeTask(context.spaceId(), context.agentId(), task.platformTaskId(), "RUNTIME_TERMINAL", task.version());
        store.closeFrame(context.spaceId(), context.agentId(), frame.frameId(), frame.version());
    }

    private static RouteTarget.Type targetType(Decision decision) {
        return switch (decision) {
            case START_LOOP, RESUME_LOOP -> RouteTarget.Type.LOOP;
            case STATIC_PLAN -> RouteTarget.Type.TASK;
            case START_WORKFLOW -> RouteTarget.Type.WORKFLOW;
            default -> RouteTarget.Type.CAPABILITY;
        };
    }

    private void compensateFailure(RequestContext context, RuntimeType type, String runtimeRef,
                                   String pendingGoalId, PlatformTask reserved, RuntimeException failure) {
        try {
            compensator.compensate(context, type, runtimeRef, "PLATFORM_REGISTRATION_FAILED");
        } catch (RuntimeException compensationFailure) {
            failure.addSuppressed(compensationFailure);
        }
        try {
            failReservedRuntime(context, reserved, pendingGoalId, "PLATFORM_REGISTRATION_FAILED");
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
