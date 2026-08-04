package com.huawei.finance.runtime.task;

import com.huawei.finance.orchestrator.OrchestrationOutcome;
import com.huawei.finance.orchestrator.OrchestrationRequest;
import com.huawei.finance.orchestrator.TaskOrchestrator;
import java.util.Objects;

/** 将 Runtime 公开执行门面适配到平台任务中控。 */
public final class DefaultAgentTaskExecutor implements AgentTaskExecutor {

    private final TaskOrchestrator orchestrator;

    public DefaultAgentTaskExecutor(TaskOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @Override
    public AgentTaskOutcome execute(AgentTaskRequest request) {
        OrchestrationOutcome outcome = orchestrator.handle(new OrchestrationRequest(
                request.context(), request.decision(), request.capability(), request.parameters(),
                request.goal(), request.confirmed(), request.expectedAnswers(), request.lease(),
                request.source(), request.invocationOrigin(), request.sourceInvocationId()));
        return new AgentTaskOutcome(outcome.taskId(), outcome.result(), outcome.guardrail(),
                outcome.state() == null ? null : outcome.state().name());
    }
}
