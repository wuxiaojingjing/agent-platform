package com.huawei.finance.orchestrator.task;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/**
 * 任务在中控侧的完整真值。
 *
 * @param taskId          任务标识
 * @param agentId         所属 Agent（架构草案阶段 1）
 * @param traceId         创建时的链路标识
 * @param sessionId       会话标识
 * @param userId          用户标识
 * @param capabilityId    目标能力
 * @param domain          领域
 * @param goal            用户原始诉求，供审计与转人工时还原上下文
 * @param state           生命周期状态
 * @param riskLevel       风险等级
 * @param source          FAST_PATH / SLOW_PATH
 * @param invocationOrigin LOCAL / A2A 调用来源
 * @param parameters      已确认参数
 * @param pendingSlot     待澄清槽位
 * @param expectedAnswers 待澄清槽位的候选取值
 * @param clarifyRounds   已澄清轮数
 * @param guardrail       护栏结论
 * @param idempotencyKey  幂等键，护栏通过前恒为 null
 */
@Api
public record TaskRecord(
        String taskId,
        String agentId,
        String traceId,
        String sessionId,
        String userId,
        String capabilityId,
        String domain,
        String goal,
        TaskState state,
        RiskLevel riskLevel,
        Enums.TaskSource source,
        Enums.InvocationOrigin invocationOrigin,
        Map<String, Object> parameters,
        String pendingSlot,
        List<String> expectedAnswers,
        int clarifyRounds,
        GuardrailCheck guardrail,
        String idempotencyKey,
        String sourceInvocationId,
        long stateVersion) {

    public TaskRecord {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        expectedAnswers = expectedAnswers == null ? List.of() : List.copyOf(expectedAnswers);
        guardrail = guardrail == null ? GuardrailCheck.pending() : guardrail;
        source = source == null ? Enums.TaskSource.FAST_PATH : source;
        invocationOrigin = invocationOrigin == null
                ? Enums.InvocationOrigin.LOCAL : invocationOrigin;
    }

    public TaskRecord(
            String taskId, String agentId, String traceId, String sessionId, String userId,
            String capabilityId, String domain, String goal, TaskState state, RiskLevel riskLevel,
            Enums.TaskSource source, Enums.InvocationOrigin invocationOrigin,
            Map<String, Object> parameters, String pendingSlot, List<String> expectedAnswers,
            int clarifyRounds, GuardrailCheck guardrail, String idempotencyKey,
            String sourceInvocationId) {
        this(taskId, agentId, traceId, sessionId, userId, capabilityId, domain, goal, state,
                riskLevel, source, invocationOrigin, parameters, pendingSlot, expectedAnswers,
                clarifyRounds, guardrail, idempotencyKey, sourceInvocationId, 0);
    }

    public TaskRecord(
            String taskId, String agentId, String traceId, String sessionId, String userId,
            String capabilityId, String domain, String goal, TaskState state, RiskLevel riskLevel,
            Enums.TaskSource source, Map<String, Object> parameters, String pendingSlot,
            List<String> expectedAnswers, int clarifyRounds, GuardrailCheck guardrail,
            String idempotencyKey, String sourceInvocationId) {
        this(taskId, agentId, traceId, sessionId, userId, capabilityId, domain, goal, state,
                riskLevel, source, Enums.InvocationOrigin.LOCAL, parameters, pendingSlot,
                expectedAnswers, clarifyRounds, guardrail, idempotencyKey, sourceInvocationId, 0);
    }

    public TaskRecord(
            String taskId, String agentId, String traceId, String sessionId, String userId,
            String capabilityId, String domain, String goal, TaskState state, RiskLevel riskLevel,
            Enums.TaskSource source, Map<String, Object> parameters, String pendingSlot,
            List<String> expectedAnswers, int clarifyRounds, GuardrailCheck guardrail,
            String idempotencyKey) {
        this(taskId, agentId, traceId, sessionId, userId, capabilityId, domain, goal, state,
                riskLevel, source, Enums.InvocationOrigin.LOCAL, parameters, pendingSlot,
                expectedAnswers, clarifyRounds, guardrail, idempotencyKey, null, 0);
    }
}
