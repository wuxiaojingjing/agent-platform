package com.huawei.finance.orchestrator.task;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机。
 *
 * <p>迁移表显式列出而不是「凡是没禁止的都允许」：转账任务从 {@code CONFIRM_PENDING}
 * 直接跳到 {@code SUCCEEDED} 这种迁移，靠代码评审是拦不住的，靠表能。
 */
public final class TaskStateMachine {

    private static final Map<TaskState, Set<TaskState>> ALLOWED = new EnumMap<>(TaskState.class);

    static {
        ALLOWED.put(TaskState.CREATED, EnumSet.of(
                TaskState.CLARIFY_PENDING, TaskState.REVIEW_PENDING, TaskState.CONFIRM_PENDING, TaskState.RUNNING,
                TaskState.GUARDRAIL_BLOCKED, TaskState.CANCELLED));

        // 澄清可以反复发生，但轮数上限由快路径把控，状态机不重复设限
        ALLOWED.put(TaskState.CLARIFY_PENDING, EnumSet.of(
                TaskState.CLARIFY_PENDING, TaskState.REVIEW_PENDING, TaskState.CONFIRM_PENDING, TaskState.RUNNING,
                TaskState.GUARDRAIL_BLOCKED, TaskState.CANCELLED, TaskState.FAILED));

        ALLOWED.put(TaskState.REVIEW_PENDING, EnumSet.of(
                TaskState.RUNNING, TaskState.GUARDRAIL_BLOCKED, TaskState.CANCELLED, TaskState.FAILED));

        // 确认态不能直达 SUCCEEDED：必须先 RUNNING，才有执行记录可查
        ALLOWED.put(TaskState.CONFIRM_PENDING, EnumSet.of(
                TaskState.RUNNING, TaskState.GUARDRAIL_BLOCKED, TaskState.CANCELLED, TaskState.FAILED));

        ALLOWED.put(TaskState.RUNNING, EnumSet.of(
                TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED, TaskState.CLARIFY_PENDING));

        ALLOWED.put(TaskState.SUCCEEDED, EnumSet.noneOf(TaskState.class));
        ALLOWED.put(TaskState.FAILED, EnumSet.noneOf(TaskState.class));
        ALLOWED.put(TaskState.CANCELLED, EnumSet.noneOf(TaskState.class));
        ALLOWED.put(TaskState.GUARDRAIL_BLOCKED, EnumSet.noneOf(TaskState.class));
    }

    private TaskStateMachine() {
    }

    public static boolean allowed(TaskState from, TaskState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertAllowed(String taskId, TaskState from, TaskState to) {
        if (!allowed(from, to)) {
            throw new IllegalTransitionException(taskId, from, to);
        }
    }
}
