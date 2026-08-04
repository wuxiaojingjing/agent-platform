package com.huawei.finance.orchestrator.task;

/** 非法状态迁移。属于代码缺陷而非用户输入问题，因此是运行期异常而不是返回值。 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(String taskId, TaskState from, TaskState to) {
        super("任务 " + taskId + " 不允许从 " + from + " 迁移到 " + to);
    }
}
