package com.huawei.finance.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.orchestrator.task.IllegalTransitionException;
import com.huawei.finance.orchestrator.task.TaskState;
import com.huawei.finance.orchestrator.task.TaskStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 任务状态机的迁移表。 */
class TaskStateMachineTest {

    @Test
    @DisplayName("确认态不能直达成功：必须先进 RUNNING 才有执行记录")
    void confirmPendingCannotJumpToSucceeded() {
        assertThat(TaskStateMachine.allowed(TaskState.CONFIRM_PENDING, TaskState.SUCCEEDED)).isFalse();
        assertThat(TaskStateMachine.allowed(TaskState.CONFIRM_PENDING, TaskState.RUNNING)).isTrue();

        assertThatThrownBy(() -> TaskStateMachine.assertAllowed(
                "task-1", TaskState.CONFIRM_PENDING, TaskState.SUCCEEDED))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("CONFIRM_PENDING");
    }

    @Test
    @DisplayName("终态不再迁出")
    void terminalStatesAreFinal() {
        for (TaskState terminal : new TaskState[]{
                TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED, TaskState.GUARDRAIL_BLOCKED}) {
            assertThat(terminal.terminal()).isTrue();
            for (TaskState to : TaskState.values()) {
                assertThat(TaskStateMachine.allowed(terminal, to)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("执行中可以回到澄清态：领域 Agent 返回 NEED_USER 时要接得住")
    void runningCanReturnToClarify() {
        assertThat(TaskStateMachine.allowed(TaskState.RUNNING, TaskState.CLARIFY_PENDING)).isTrue();
    }
}
