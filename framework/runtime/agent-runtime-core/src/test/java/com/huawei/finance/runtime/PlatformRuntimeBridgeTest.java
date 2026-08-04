package com.huawei.finance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.RouteTarget;
import com.huawei.finance.orchestrator.context.PlatformTaskContextManager;
import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.task.TaskRepository;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PlatformRuntimeBridgeTest {

    @Test
    void terminalContinuationClosesAnAlreadyBoundForegroundRuntime() {
        AtomicBoolean taskClosed = new AtomicBoolean();
        AtomicBoolean frameClosed = new AtomicBoolean();
        FocusFrame frame = new FocusFrame("tenant-a", "frame-1", "agent-a", "session-a",
                FocusSubjectType.PLATFORM_TASK, "pt-1", FocusState.FOREGROUND, 4,
                Instant.now(), null, null);
        PlatformTask task = new PlatformTask("tenant-a", "pt-1", "agent-a", "session-a",
                new RouteTarget(RouteTarget.Type.CAPABILITY, "cap.card.bill"), RuntimeType.TASK,
                "task-1", PlatformTaskStatus.OPEN, BindingState.BOUND, null, "trace-1", 3,
                Instant.now(), Instant.now());
        TaskContextStore store = proxy(TaskContextStore.class, (method, args, returnType) -> switch (method) {
            case "focus" -> new FocusView(frameClosed.get() ? null : frame, List.of());
            case "task" -> Optional.of(task);
            case "closeTask" -> {
                taskClosed.set(true);
                yield task;
            }
            case "closeFrame" -> {
                frameClosed.set(true);
                yield frame;
            }
            default -> defaultValue(returnType);
        });
        TaskRepository tasks = proxy(TaskRepository.class,
                (method, args, returnType) -> defaultValue(returnType));
        PlatformRuntimeBridge bridge = new PlatformRuntimeBridge(
                store, new PlatformTaskContextManager(store), tasks);
        RequestContext context = new RequestContext("trace-1", "session-a", "user-a", "tenant-a",
                "agent-a", "APP", "home", "AUTH", false);

        PlatformTask result = bridge.register(context,
                RouteDecision.builder().decision(Decision.RESUME_TASK)
                        .target(new RouteTarget(RouteTarget.Type.CAPABILITY, "cap.card.bill")).build(),
                RuntimeType.TASK, "task-1", true);

        assertThat(result).isSameAs(task);
        assertThat(taskClosed).isTrue();
        assertThat(frameClosed).isTrue();
    }

    @Test
    void registrationFailureCompensatesRuntimeAndCleansPlatformReservation() {
        AtomicBoolean runtimeCompensated = new AtomicBoolean();
        AtomicBoolean platformCleaned = new AtomicBoolean();
        PlatformTask reserved = new PlatformTask("tenant-a", "pt-1", "agent-a", "session-a",
                new RouteTarget(RouteTarget.Type.LOOP, "goal-1"), RuntimeType.AGENT_LOOP, null,
                PlatformTaskStatus.OPEN, BindingState.RESERVED, null, "trace-1", 0,
                Instant.now(), Instant.now());
        TaskContextStore store = proxy(TaskContextStore.class, (method, args, returnType) -> switch (method) {
            case "focus" -> new FocusView(null, java.util.List.of());
            case "task" -> Optional.empty();
            case "reserveTask" -> reserved;
            case "bindRuntimeAndFocus" -> throw new IllegalStateException("FOCUS_BIND_FAILED");
            case "failRuntimeRegistration" -> {
                platformCleaned.set(true);
                assertThat(args[3]).isEqualTo("pt-1");
                assertThat(args[5]).isEqualTo("PLATFORM_REGISTRATION_FAILED");
                yield null;
            }
            default -> defaultValue(returnType);
        });
        TaskRepository tasks = proxy(TaskRepository.class,
                (method, args, returnType) -> defaultValue(returnType));
        RuntimeRegistrationCompensator compensator = (context, type, ref, reason) -> {
            runtimeCompensated.set(true);
            assertThat(type).isEqualTo(RuntimeType.AGENT_LOOP);
            assertThat(ref).isEqualTo("loop-1");
            assertThat(reason).isEqualTo("PLATFORM_REGISTRATION_FAILED");
        };
        PlatformRuntimeBridge bridge = new PlatformRuntimeBridge(store,
                new PlatformTaskContextManager(store), tasks, compensator);
        RequestContext context = new RequestContext("trace-1", "session-a", "user-a", "tenant-a",
                "agent-a", "APP", "home", "AUTH", false);
        RouteDecision decision = RouteDecision.builder().decision(Decision.START_LOOP)
                .target(new RouteTarget(RouteTarget.Type.LOOP, "goal-1")).build();

        assertThatThrownBy(() -> bridge.register(context, decision, RuntimeType.AGENT_LOOP,
                "loop-1", false)).hasMessageContaining("FOCUS_BIND_FAILED");
        assertThat(runtimeCompensated.get()).isTrue();
        assertThat(platformCleaned.get()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "Proxy";
                            case "hashCode" -> System.identityHashCode(target);
                            case "equals" -> target == args[0];
                            default -> null;
                        };
                    }
                    return invocation.invoke(method.getName(), args == null ? new Object[0] : args,
                            method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == Optional.class) return Optional.empty();
        if (type == java.util.List.class) return java.util.List.of();
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args, Class<?> returnType) throws Throwable;
    }
}
