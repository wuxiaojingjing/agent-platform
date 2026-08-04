package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.task.TaskRepository;

/** Workflow entry currently persists through its owning task runtime; this adapter keeps the platform type explicit. */
public class WorkflowContinuationPort extends TaskContinuationPort {
    public WorkflowContinuationPort(TaskRepository tasks) { super(tasks); }
    @Override public RuntimeType runtimeType() { return RuntimeType.WORKFLOW; }
}
